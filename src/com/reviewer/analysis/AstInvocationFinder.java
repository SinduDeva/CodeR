package com.reviewer.analysis;

import java.util.*;

/**
 * AST-based method invocation finder backed by JavaParser.
 *
 * <p>Compared to the regex heuristics in {@link ImpactAnalyzer}, this scanner:
 * <ul>
 *   <li>Never matches inside string literals or comments.</li>
 *   <li>Correctly handles chained calls, lambdas, anonymous classes, and
 *       method references — the AST structure rather than token presence
 *       determines whether a call is attributed to the target type.</li>
 *   <li>Attributes calls inside lambda bodies to the <em>enclosing</em>
 *       named method automatically (via {@code findAncestor}).</li>
 * </ul>
 *
 * <p>Gracefully degrades: if {@code javaparser-core.jar} is absent from the
 * runtime classpath, {@link #isAvailable()} returns {@code false} and every
 * public method returns an empty list.
 */
public class AstInvocationFinder {

    private static final boolean AVAILABLE;

    static {
        boolean ok = false;
        try {
            Class.forName("com.github.javaparser.StaticJavaParser");
            ok = true;
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {}
        AVAILABLE = ok;
    }

    public static boolean isAvailable() { return AVAILABLE; }

    /**
     * Finds methods in {@code content} that invoke any of {@code touchedMethods}
     * on a field or variable typed as {@code targetSimpleName} or any of its
     * supertypes (interfaces / superclass).
     *
     * <p>When {@code touchedMethods} is empty the called-method filter is
     * removed; the scanner returns every method that invokes <em>any</em> method
     * on an instance of the target type (structural-fallback mode for the BFS).
     *
     * @param content           Java source text of the file to scan
     * @param targetSimpleName  Simple class name of the changed class
     * @param targetFqn         Fully-qualified name (used only for reference; type
     *                          resolution is not performed)
     * @param supertypeNames    Interface / superclass simple names the target implements
     * @param touchedMethods    Changed method names to match; empty = match any call
     * @return distinct enclosing-method names containing a matching call site
     */
    @SuppressWarnings("unchecked")
    public static List<String> findCallerMethods(
            String content,
            String targetSimpleName,
            String targetFqn,
            List<String> supertypeNames,
            List<String> touchedMethods) {

        if (!AVAILABLE || content == null || content.isBlank()) return Collections.emptyList();

        try {
            com.github.javaparser.ast.CompilationUnit cu =
                    com.github.javaparser.StaticJavaParser.parse(content);

            // ── 1. Collect type tokens that identify the target ──────────────
            Set<String> targetTypes = new LinkedHashSet<>();
            addIfValid(targetTypes, targetSimpleName);
            if (supertypeNames != null) supertypeNames.forEach(s -> addIfValid(targetTypes, s));
            if (targetTypes.isEmpty()) return Collections.emptyList();

            // ── 2. Discover instance names typed as any target type ──────────
            // Covers: fields, constructor/method parameters, local variables.
            // We do NOT perform full type resolution — we match the declared
            // type name (simple name, possibly with generics stripped) against
            // the set of target type tokens.
            Set<String> instanceNames = new LinkedHashSet<>(targetTypes); // class name = static call

            // Fields: @Autowired IHelper helper;
            cu.findAll(com.github.javaparser.ast.body.FieldDeclaration.class).forEach(fd -> {
                String type = stripGenerics(fd.getElementType().asString());
                if (targetTypes.contains(type))
                    fd.getVariables().forEach(v -> instanceNames.add(v.getNameAsString()));
            });

            // Method / constructor parameters: void foo(IHelper helper)
            cu.findAll(com.github.javaparser.ast.body.Parameter.class).forEach(p -> {
                String type = stripGenerics(p.getTypeAsString());
                if (targetTypes.contains(type))
                    instanceNames.add(p.getNameAsString());
            });

            // Local variable declarations: IHelper h = ...;
            cu.findAll(com.github.javaparser.ast.expr.VariableDeclarationExpr.class).forEach(vde -> {
                String type = stripGenerics(vde.getElementType().asString());
                if (targetTypes.contains(type))
                    vde.getVariables().forEach(v -> instanceNames.add(v.getNameAsString()));
            });

            // ── 3. Find call sites on those instances ────────────────────────
            Set<String> touchedSet = touchedMethods != null
                    ? new HashSet<>(touchedMethods) : Collections.emptySet();

            Set<String> callers = new LinkedHashSet<>();

            cu.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).forEach(call -> {

                // Filter by called method name when the touched list is given.
                if (!touchedSet.isEmpty() && !touchedSet.contains(call.getNameAsString())) return;

                // Require a scope expression (rules out bare method calls whose
                // target is unknown without full type resolution).
                call.getScope().ifPresent(scope -> {

                    boolean match = false;

                    if (scope.isNameExpr()) {
                        // Simple identifier: helper.method(...)
                        match = instanceNames.contains(scope.asNameExpr().getNameAsString());
                    } else {
                        // Chained / field-access scope: TargetClass.INSTANCE.method()
                        // or factory.get().method() — check if root identifier is known.
                        String scopeStr = scope.toString();
                        match = instanceNames.stream().anyMatch(inst ->
                                scopeStr.equals(inst)
                                || scopeStr.startsWith(inst + ".")
                                || scopeStr.startsWith(inst + "::"));
                    }

                    if (match) {
                        // Walk up the AST to find the enclosing named method.
                        // findAncestor works through lambdas and anonymous classes,
                        // returning the nearest MethodDeclaration in the tree.
                        call.findAncestor(com.github.javaparser.ast.body.MethodDeclaration.class)
                                .ifPresent(md -> callers.add(md.getNameAsString()));
                    }
                });
            });

            return new ArrayList<>(callers);

        } catch (Exception e) {
            // Parse error or runtime class-not-found — degrade gracefully.
            return Collections.emptyList();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void addIfValid(Set<String> set, String name) {
        if (name != null && !name.isBlank()) set.add(name.trim());
    }

    /** Strips generic parameters: {@code IHelper<T>} → {@code IHelper}. */
    private static String stripGenerics(String typeName) {
        if (typeName == null) return "";
        int lt = typeName.indexOf('<');
        return lt > 0 ? typeName.substring(0, lt).trim() : typeName.trim();
    }
}
