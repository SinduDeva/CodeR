package com.reviewer.analysis;

import java.util.*;

/**
 * AST-based method invocation finder backed by JavaParser.
 *
 * <p>Compared to the regex heuristics in {@link ImpactAnalyzer}, this scanner:
 * <ul>
 *   <li>Never matches inside string literals or comments.</li>
 *   <li>Correctly handles chained calls, lambdas, anonymous classes — the AST
 *       structure rather than token presence determines attribution.</li>
 *   <li>Attributes calls inside lambda bodies to the enclosing named method
 *       automatically via {@code findAncestor(MethodDeclaration.class)}.</li>
 * </ul>
 *
 * <p>Instance name collection is intentionally delegated to the caller
 * ({@link ImpactAnalyzer#extractInstanceNames}) so the regex-based Spring
 * heuristics (lowerCamelCase field inference) are not duplicated here.
 *
 * <p>Gracefully degrades: if {@code javaparser-core.jar} is absent,
 * {@link #isAvailable()} returns {@code false} and every method returns empty.
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
     * on an instance whose name is in {@code instanceNames}.
     *
     * <p>{@code instanceNames} is pre-computed by {@link ImpactAnalyzer} and
     * includes field names, parameter names, local variable names, the class
     * name itself (for static calls), and Spring lowerCamelCase inferences.
     *
     * <p>When {@code touchedMethods} is empty the called-method filter is
     * removed; every call on a known instance is returned (structural mode).
     *
     * @param content        Java source text of the file to scan
     * @param instanceNames  Pre-computed set of variable/field names for the target
     * @param touchedMethods Method names to match; empty = match any call
     * @return distinct enclosing-method names containing a matching call site
     */
    @SuppressWarnings("unchecked")
    public static List<String> findCallerMethods(
            String content,
            Set<String> instanceNames,
            List<String> touchedMethods) {

        if (!AVAILABLE || content == null || content.isBlank()
                || instanceNames == null || instanceNames.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            com.github.javaparser.ast.CompilationUnit cu = AstCache.get(content);

            Set<String> touchedSet = touchedMethods != null
                    ? new HashSet<>(touchedMethods) : Collections.emptySet();

            Set<String> callers = new LinkedHashSet<>();

            cu.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).forEach(call -> {

                if (!touchedSet.isEmpty() && !touchedSet.contains(call.getNameAsString())) return;

                call.getScope().ifPresent(scope -> {
                    boolean match = false;
                    if (scope.isNameExpr()) {
                        match = instanceNames.contains(scope.asNameExpr().getNameAsString());
                    } else {
                        String scopeStr = scope.toString();
                        match = instanceNames.stream().anyMatch(inst ->
                                scopeStr.equals(inst)
                                || scopeStr.startsWith(inst + ".")
                                || scopeStr.startsWith(inst + "::"));
                    }
                    if (match) {
                        call.findAncestor(com.github.javaparser.ast.body.MethodDeclaration.class)
                                .ifPresent(md -> callers.add(md.getNameAsString()));
                    }
                });
            });

            return new ArrayList<>(callers);

        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
