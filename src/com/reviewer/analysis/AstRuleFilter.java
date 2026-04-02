package com.reviewer.analysis;

import com.reviewer.model.Models.Finding;
import java.util.*;

/**
 * Post-pass false-positive filter backed by JavaParser AST.
 *
 * <p>After all regex rules have fired, this class re-examines the generated
 * {@link Finding} list and removes entries that are demonstrably wrong:
 * the regex matched inside a string literal, a comment, or a context that
 * the AST reveals as safe.
 *
 * <p>Three categories of findings are validated:
 * <ul>
 *   <li><b>CHAINED_DEREF</b> — only real method-call chains in live code are
 *       kept; chains that appear inside string literals or Javadoc are dropped.</li>
 *   <li><b>EMPTY_CATCH</b> — confirmed empty via AST statement count (regex
 *       can mistake a comment-only block for an empty catch).</li>
 *   <li><b>SQL_CONCAT</b> — string-concatenation findings are kept only when
 *       the {@code +} operator appears inside a method-call argument that
 *       contains a SQL keyword, rather than being a log message or constant.</li>
 * </ul>
 *
 * <p>Degrades gracefully: if {@code javaparser-core.jar} is absent the original
 * list is returned unchanged.
 */
public class AstRuleFilter {

    private static final boolean AVAILABLE = AstInvocationFinder.isAvailable();


    /**
     * Returns a (possibly smaller) list of findings after AST validation.
     * Never throws; on any parse error the original list is returned.
     *
     * @param content  full Java source of the file being reviewed
     * @param findings mutable list produced by the regex rules
     * @return filtered list (same instance when JavaParser is unavailable)
     */
    public static List<Finding> filter(String content, List<Finding> findings) {
        if (!AVAILABLE || content == null || content.isBlank() || findings.isEmpty()) {
            return findings;
        }
        try {
            return doFilter(content, findings);
        } catch (Exception e) {
            return findings;
        }
    }

    static com.github.javaparser.ast.CompilationUnit getCachedCu(String content) throws Exception {
        return AstCache.get(content);
    }

    @SuppressWarnings("unchecked")
    private static List<Finding> doFilter(String content, List<Finding> findings) throws Exception {

        com.github.javaparser.ast.CompilationUnit cu = AstCache.get(content);

        // ── Collect all string-literal ranges so we can detect in-string matches ──
        Set<int[]> stringRanges = new LinkedHashSet<>();
        cu.findAll(com.github.javaparser.ast.expr.StringLiteralExpr.class).forEach(sl ->
            sl.getRange().ifPresent(r ->
                stringRanges.add(new int[]{r.begin.line, r.end.line})));

        // ── Collect real chained-call lines (MethodCallExpr with a call-scope) ──
        Set<Integer> realChainLines = new HashSet<>();
        cu.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).forEach(call ->
            call.getScope().ifPresent(scope -> {
                if (scope.isMethodCallExpr()) {
                    call.getRange().ifPresent(r -> realChainLines.add(r.begin.line));
                }
            }));

        // ── Collect truly-empty catch blocks (0 statements, ignoring comments) ──
        Set<Integer> emptyCatchLines = new HashSet<>();
        cu.findAll(com.github.javaparser.ast.stmt.CatchClause.class).forEach(cc -> {
            if (cc.getBody().getStatements().isEmpty()) {
                cc.getRange().ifPresent(r -> emptyCatchLines.add(r.begin.line));
            }
        });

        // ── Collect binary-add expressions inside method-call arguments ──
        // that also contain a SQL keyword (heuristic for SQL injection check)
        Set<Integer> sqlConcatLines = new HashSet<>();
        Set<String> SQL_KEYWORDS = new HashSet<>(Arrays.asList(
                "select", "insert", "update", "delete", "where", "from", "query"));
        cu.findAll(com.github.javaparser.ast.expr.BinaryExpr.class).forEach(be -> {
            if (be.getOperator() != com.github.javaparser.ast.expr.BinaryExpr.Operator.PLUS) return;
            boolean inCall = be.findAncestor(
                    com.github.javaparser.ast.expr.MethodCallExpr.class).isPresent();
            if (!inCall) return;
            String expr = be.toString().toLowerCase();
            boolean hasSqlKeyword = SQL_KEYWORDS.stream().anyMatch(expr::contains);
            if (hasSqlKeyword) {
                be.getRange().ifPresent(r -> sqlConcatLines.add(r.begin.line));
            }
        });

        // ── Context-awareness: lines whose chained call root is null-guarded ──
        // If the root variable of a chained call is checked for null (or via Optional.isPresent /
        // Objects.requireNonNull / assert) in the same enclosing block before the call site,
        // the NULL_SAFETY finding is a false positive and should be dropped.
        Set<Integer> nullGuardedChainLines = buildNullGuardedChainLines(cu);

        // ── Context-awareness: Optional.get() lines protected by isPresent() ──
        Set<Integer> optionalGetGuardedLines = buildOptionalGetGuardedLines(cu);

        // ── Context-awareness: lines already inside a try block that catches relevant exceptions ──
        Set<Integer> handledExceptionLines = buildHandledExceptionLines(cu);

        List<Finding> result = new ArrayList<>(findings.size());
        for (Finding f : findings) {
            if (shouldDrop(f, stringRanges, realChainLines, emptyCatchLines, sqlConcatLines,
                           nullGuardedChainLines, optionalGetGuardedLines, handledExceptionLines)) {
                continue;
            }
            result.add(f);
        }
        return result;
    }

    /**
     * Finds chained-call lines where the root object is null-guarded in the enclosing block
     * before the call site. Patterns detected:
     *   if (obj != null) { obj.getX().getY(); }
     *   if (obj == null) return/throw; ... obj.getX().getY();
     *   Objects.requireNonNull(obj); obj.getX();
     *   Optional.ofNullable(obj).map(...) — the whole chain is safe
     */
    private static Set<Integer> buildNullGuardedChainLines(
            com.github.javaparser.ast.CompilationUnit cu) {
        Set<Integer> guarded = new HashSet<>();
        cu.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).forEach(call -> {
            // Only interested in chained calls
            if (!call.getScope().map(s -> s.isMethodCallExpr()).orElse(false)) return;

            // Walk up to the enclosing statement
            com.github.javaparser.ast.Node stmt = call;
            while (stmt != null && !(stmt instanceof com.github.javaparser.ast.stmt.Statement)) {
                stmt = stmt.getParentNode().orElse(null);
            }
            if (stmt == null) return;

            // Resolve the root name of the chain (leftmost identifier)
            String rootName = extractChainRoot(call);
            if (rootName == null) return;

            // Check if this statement is inside an if-block that guards rootName != null
            boolean guardedByIf = isInsideNullGuardIf(stmt, rootName);

            // Check if there's a null-guard (early return/throw or requireNonNull) before
            // this statement in the same block
            boolean guardedByEarlyExit = hasEarlyNullExitBefore(stmt, rootName);

            if (guardedByIf || guardedByEarlyExit) {
                call.getRange().ifPresent(r -> guarded.add(r.begin.line));
            }
        });
        return guarded;
    }

    /** Extracts the root identifier name from a chained call: a.b().c() → "a" */
    private static String extractChainRoot(com.github.javaparser.ast.expr.MethodCallExpr call) {
        com.github.javaparser.ast.expr.Expression current = call;
        while (current instanceof com.github.javaparser.ast.expr.MethodCallExpr) {
            java.util.Optional<com.github.javaparser.ast.expr.Expression> scope =
                    ((com.github.javaparser.ast.expr.MethodCallExpr) current).getScope();
            if (!scope.isPresent()) return null;
            current = scope.get();
        }
        if (current instanceof com.github.javaparser.ast.expr.NameExpr) {
            return ((com.github.javaparser.ast.expr.NameExpr) current).getNameAsString();
        }
        return null;
    }

    /** Returns true if stmt is a direct child of an IfStmt whose condition checks rootName != null */
    private static boolean isInsideNullGuardIf(
            com.github.javaparser.ast.Node stmt, String rootName) {
        com.github.javaparser.ast.Node parent = stmt.getParentNode().orElse(null);
        // Handle BlockStmt → IfStmt
        if (parent instanceof com.github.javaparser.ast.stmt.BlockStmt) {
            parent = parent.getParentNode().orElse(null);
        }
        if (!(parent instanceof com.github.javaparser.ast.stmt.IfStmt)) return false;
        com.github.javaparser.ast.stmt.IfStmt ifStmt =
                (com.github.javaparser.ast.stmt.IfStmt) parent;
        String cond = ifStmt.getCondition().toString();
        // Matches: obj != null  or  null != obj
        return cond.contains(rootName + " != null") || cond.contains("null != " + rootName);
    }

    /**
     * Returns true if there is a statement before stmt in the same block that:
     *   - throws/returns if rootName == null  (null guard with early exit)
     *   - calls Objects.requireNonNull(rootName)
     */
    private static boolean hasEarlyNullExitBefore(
            com.github.javaparser.ast.Node stmt, String rootName) {
        com.github.javaparser.ast.Node block = stmt.getParentNode().orElse(null);
        if (!(block instanceof com.github.javaparser.ast.stmt.BlockStmt)) return false;
        com.github.javaparser.ast.stmt.BlockStmt blockStmt =
                (com.github.javaparser.ast.stmt.BlockStmt) block;
        boolean found = false;
        for (com.github.javaparser.ast.stmt.Statement s : blockStmt.getStatements()) {
            if (s == stmt) break;
            String sStr = s.toString();
            // if (x == null) return/throw
            if ((sStr.contains(rootName + " == null") || sStr.contains("null == " + rootName))
                    && (sStr.contains("return") || sStr.contains("throw"))) {
                found = true; break;
            }
            // Objects.requireNonNull(x)
            if (sStr.contains("requireNonNull") && sStr.contains(rootName)) {
                found = true; break;
            }
        }
        return found;
    }

    /**
     * Finds Optional.get() call lines that are guarded by an isPresent() or ifPresent()
     * check in the enclosing block before the call site.
     */
    private static Set<Integer> buildOptionalGetGuardedLines(
            com.github.javaparser.ast.CompilationUnit cu) {
        Set<Integer> guarded = new HashSet<>();
        cu.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class).forEach(call -> {
            if (!"get".equals(call.getNameAsString())) return;
            // Must have a scope (the Optional variable)
            java.util.Optional<com.github.javaparser.ast.expr.Expression> scopeOpt = call.getScope();
            if (!scopeOpt.isPresent()) return;
            String varName = scopeOpt.get().toString();

            com.github.javaparser.ast.Node stmt = call;
            while (stmt != null && !(stmt instanceof com.github.javaparser.ast.stmt.Statement)) {
                stmt = stmt.getParentNode().orElse(null);
            }
            if (stmt == null) return;

            // Check enclosing if-condition: if (opt.isPresent()) { opt.get() }
            if (isInsideIsPresentIf(stmt, varName)) {
                call.getRange().ifPresent(r -> guarded.add(r.begin.line));
                return;
            }
            // Check for preceding isPresent() check in same block
            if (hasPrecedingIsPresentCheck(stmt, varName)) {
                call.getRange().ifPresent(r -> guarded.add(r.begin.line));
            }
        });
        return guarded;
    }

    private static boolean isInsideIsPresentIf(
            com.github.javaparser.ast.Node stmt, String varName) {
        com.github.javaparser.ast.Node parent = stmt.getParentNode().orElse(null);
        if (parent instanceof com.github.javaparser.ast.stmt.BlockStmt) {
            parent = parent.getParentNode().orElse(null);
        }
        if (!(parent instanceof com.github.javaparser.ast.stmt.IfStmt)) return false;
        String cond = ((com.github.javaparser.ast.stmt.IfStmt) parent).getCondition().toString();
        return cond.contains(varName + ".isPresent()") || cond.contains(varName + ".isEmpty()");
    }

    private static boolean hasPrecedingIsPresentCheck(
            com.github.javaparser.ast.Node stmt, String varName) {
        com.github.javaparser.ast.Node block = stmt.getParentNode().orElse(null);
        if (!(block instanceof com.github.javaparser.ast.stmt.BlockStmt)) return false;
        for (com.github.javaparser.ast.stmt.Statement s :
                ((com.github.javaparser.ast.stmt.BlockStmt) block).getStatements()) {
            if (s == stmt) break;
            String sStr = s.toString();
            if (sStr.contains(varName + ".isPresent()") || sStr.contains(varName + ".isEmpty()")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds lines that are already inside a try block with a catch for Exception or RuntimeException,
     * meaning the developer has explicitly handled the failure path. Drops EXCEPTION_HANDLING
     * findings where the risky call is already wrapped.
     */
    private static Set<Integer> buildHandledExceptionLines(
            com.github.javaparser.ast.CompilationUnit cu) {
        Set<Integer> handled = new HashSet<>();
        cu.findAll(com.github.javaparser.ast.stmt.TryStmt.class).forEach(tryStmt -> {
            boolean catchesException = tryStmt.getCatchClauses().stream().anyMatch(cc -> {
                String type = cc.getParameter().getType().asString();
                return type.equals("Exception") || type.equals("RuntimeException")
                        || type.equals("Throwable");
            });
            if (!catchesException) return;
            // Mark every line inside the try body as handled
            tryStmt.getTryBlock().getRange().ifPresent(r -> {
                for (int line = r.begin.line; line <= r.end.line; line++) {
                    handled.add(line);
                }
            });
        });
        return handled;
    }

    private static boolean shouldDrop(
            Finding f,
            Set<int[]> stringRanges,
            Set<Integer> realChainLines,
            Set<Integer> emptyCatchLines,
            Set<Integer> sqlConcatLines,
            Set<Integer> nullGuardedChainLines,
            Set<Integer> optionalGetGuardedLines,
            Set<Integer> handledExceptionLines) {

        String msg = f.message;

        // ── CHAINED_DEREF findings ───────────────────────────────────────────
        if (msg.contains("chained method call")) {
            if (isInsideStringLiteral(f.line, stringRanges)) return true;
            if (!realChainLines.contains(f.line)) return true;
            // Context-aware: chain root is already null-guarded in surrounding context
            if (nullGuardedChainLines.contains(f.line)) return true;
        }

        // ── NULL_SAFETY / Optional.get() findings ───────────────────────────
        if (msg.contains("Optional") && msg.contains("get")) {
            if (optionalGetGuardedLines.contains(f.line)) return true;
        }

        // ── EMPTY_CATCH findings ─────────────────────────────────────────────
        if (msg.contains("catch block is empty")) {
            if (!emptyCatchLines.contains(f.line)) return true;
        }

        // ── SQL_CONCAT findings ──────────────────────────────────────────────
        if (msg.contains("SQL query built by string concatenation")) {
            if (!sqlConcatLines.contains(f.line)) return true;
        }

        // ── EXCEPTION_HANDLING findings ──────────────────────────────────────
        // If the risky line is already inside a try-catch(Exception), the developer
        // has explicitly handled the failure path — drop to avoid noise.
        if (msg.contains("catching generic Exception") || msg.contains("catching Throwable")) {
            // These ARE the catch clauses — don't suppress them based on being "handled"
        } else if (f.category != null && f.category.name().equals("EXCEPTION_HANDLING")) {
            if (handledExceptionLines.contains(f.line)) return true;
        }

        return false;
    }

    private static boolean isInsideStringLiteral(int line, Set<int[]> stringRanges) {
        for (int[] range : stringRanges) {
            if (line >= range[0] && line <= range[1]) return true;
        }
        return false;
    }
}
