package com.reviewer.analysis;

import com.reviewer.model.Models.*;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;
import java.util.*;
import java.util.stream.Collectors;

public class ImpactAnalyzer {
    /** Cache for compiled call/ref patterns keyed by "instance\u0000method" or "method\u0000suffix" for static/fallback patterns. */
    private static final Map<String, Pattern> CALL_PATTERN_CACHE = new ConcurrentHashMap<>();

    private static Pattern callPattern(String instanceName, String methodName) {
        String key = instanceName + "\u0000" + methodName + "\u0000call";
        return CALL_PATTERN_CACHE.computeIfAbsent(key, k ->
            Pattern.compile("\\b" + Pattern.quote(instanceName) + "\\s*\\.\\s*" + Pattern.quote(methodName) + "\\b\\s*\\("));
    }

    private static Pattern refPattern(String instanceName, String methodName) {
        String key = instanceName + "\u0000" + methodName + "\u0000ref";
        return CALL_PATTERN_CACHE.computeIfAbsent(key, k ->
            Pattern.compile("\\b" + Pattern.quote(instanceName) + "\\s*::\\s*" + Pattern.quote(methodName) + "\\b"));
    }

    private static Pattern unqualifiedCallPattern(String methodName) {
        String key = methodName + "\u0000unqcall";
        return CALL_PATTERN_CACHE.computeIfAbsent(key, k ->
            Pattern.compile("(?<![.\\w])" + Pattern.quote(methodName) + "\\s*\\("));
    }

    private static Pattern unqualifiedRefPattern(String methodName) {
        String key = methodName + "\u0000unqref";
        return CALL_PATTERN_CACHE.computeIfAbsent(key, k ->
            Pattern.compile("(?<![.\\w])::\\s*" + Pattern.quote(methodName) + "\\b"));
    }

    private static Pattern anyCallPattern(String methodName) {
        String key = methodName + "\u0000anycall";
        return CALL_PATTERN_CACHE.computeIfAbsent(key, k ->
            Pattern.compile("\\b\\w+\\s*\\.\\s*" + Pattern.quote(methodName) + "\\b\\s*\\("));
    }

    private static Pattern anyRefPattern(String methodName) {
        String key = methodName + "\u0000anyref";
        return CALL_PATTERN_CACHE.computeIfAbsent(key, k ->
            Pattern.compile("\\b\\w+\\s*::\\s*" + Pattern.quote(methodName) + "\\b"));
    }

    private static volatile boolean debugEnabled = false;
    /**
     * Controls whether the structural fallback (step 6 / AST scan) is active.
     * Off by default — only touches methods that literally contain the touched-method
     * token are followed, keeping the impacted endpoint list precise.
     * Set to true via {@code transitive.caller.structural.fallback=true} in the
     * properties file to enable broader discovery at the cost of potential
     * false-positive endpoints.
     */
    private static volatile boolean structuralFallbackEnabled = false;
    /**
     * When true (default), step 6 prefers the JavaParser AST engine for caller
     * detection when javaparser-core.jar is on the classpath.
     * When false, skips AST even if available and uses the regex path instead.
     * Controlled by {@code use.ast.caller.detection=false} in the properties file.
     */
    private static volatile boolean astCallerDetectionEnabled = true;

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    public static void setStructuralFallbackEnabled(boolean enabled) {
        structuralFallbackEnabled = enabled;
    }

    public static void setAstCallerDetectionEnabled(boolean enabled) {
        astCallerDetectionEnabled = enabled;
    }

    private static void debug(String msg) {
        if (debugEnabled) System.out.println(msg);
    }

    private static final Set<String> NON_METHOD_TOKENS;

    static {
        Set<String> tokens = new HashSet<>(Arrays.asList(
            "if", "for", "while", "switch", "case", "default", "catch", "try", "finally"
        ));
        NON_METHOD_TOKENS = Collections.unmodifiableSet(tokens);
    }
    public static List<String> extractTouchedMethods(ChangedFile file, String content) {
        List<String> methods = new ArrayList<>();
        String[] lines = content.split("\\R");

        // Support multi-line signatures, annotations, and complex throws/generics
        Pattern methodPattern = Pattern.compile(
                "(?s)(?:@[\\w\\.]+(?:\\([^)]*\\))?\\s+)*(?:public|protected|private|static|final|\\s)+\\s*(?:<[^>]+>\\s+)?(?:[\\w\\<\\>\\[\\]\\.]+)\\s+(\\w+)\\s*\\([^\\)]*\\)\\s*(?:throws [\\w\\.,\\s<>]+)?\\s*\\{",
                Pattern.MULTILINE
        );
        Matcher m = methodPattern.matcher(content);

        while (m.find()) {
            String methodName = m.group(1);
            int startLine = getLineNumber(content, m.start());
            // Capture the line where the actual method name is found, not just the start of annotations
            int methodNameIndex = m.start(1);
            int methodNameLine = getLineNumber(content, methodNameIndex);
            // Start brace matching from the method BODY opening brace (the regex ends with '{')
            int bodyBracePos = m.end() - 1;
            int bodyBraceLine = getLineNumber(content, bodyBracePos);
            int bodyBraceCharIdx = getCharIndexInLine(content, bodyBracePos);
            int endLine = findMethodEndLine(lines, bodyBraceLine, bodyBraceCharIdx);
            debug("[DEBUG] ImpactAnalyzer found method: " + methodName +
                    " (sig start: " + startLine + ", name line: " + methodNameLine + ", body brace line: " + bodyBraceLine + ", end: " + endLine + ")");
            if (endLine == -1) endLine = startLine;

            boolean intersects = false;
            for (int cl : file.changedLines) {
                if (cl >= startLine && cl <= endLine) {
                    intersects = true;
                    break;
                }
            }

            if (intersects) {
                if (isValidMethodName(methodName)) {
                    methods.add(methodName);
                }
            }
        }
        return methods;
    }

    private static int findMethodEndLine(String[] lines, int startLine, int startCharIdx) {
        int depth = 0;
        boolean inBlockComment = false;
        boolean inStringLiteral = false;
        boolean inCharLiteral = false;

        boolean foundStart = false;
        for (int i = startLine - 1; i < lines.length; i++) {
            String line = lines[i];
            char[] chars = line.toCharArray();
            boolean inLineComment = false;

            int c = 0;
            if (i == startLine - 1) {
                c = Math.max(0, Math.min(startCharIdx, chars.length));
            }

            for (; c < chars.length; ) {
                if (inLineComment) break;

                if (inBlockComment) {
                    int end = line.indexOf("*/", c);
                    if (end == -1) {
                        break;
                    }
                    inBlockComment = false;
                    c = end + 2;
                    continue;
                }

                if (inStringLiteral) {
                    int end = findClosingQuote(chars, c, '"');
                    if (end == -1) {
                        c = chars.length;
                        break;
                    }
                    inStringLiteral = false;
                    c = end + 1;
                    continue;
                }

                if (inCharLiteral) {
                    int end = findClosingQuote(chars, c, '\'');
                    if (end == -1) {
                        c = chars.length;
                        break;
                    }
                    inCharLiteral = false;
                    c = end + 1;
                    continue;
                }

                char ch = chars[c];
                char next = (c + 1 < chars.length) ? chars[c + 1] : '\0';

                if (ch == '/' && next == '/') {
                    inLineComment = true;
                    break;
                }

                if (ch == '/' && next == '*') {
                    inBlockComment = true;
                    c = c + 2;
                    continue;
                }

                if (ch == '"' && !isEscaped(chars, c)) {
                    inStringLiteral = true;
                    c++;
                    continue;
                }

                if (ch == '\'' && !isEscaped(chars, c)) {
                    inCharLiteral = true;
                    c++;
                    continue;
                }

                if (ch == '{') {
                    depth++;
                    foundStart = true;
                } else if (ch == '}') {
                    depth--;
                    if (foundStart && depth == 0) return i + 1;
                }
                c++;
            }
        }
        return -1;
    }

    private static int getCharIndexInLine(String content, int absoluteIndex) {
        if (absoluteIndex <= 0) return 0;
        int lastNewline = content.lastIndexOf('\n', Math.min(absoluteIndex, content.length() - 1));
        return absoluteIndex - lastNewline - 1;
    }

    private static int findClosingQuote(char[] chars, int startIndex, char quoteChar) {
        for (int i = startIndex; i < chars.length; i++) {
            if (chars[i] == quoteChar && !isEscaped(chars, i)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isEscaped(char[] chars, int index) {
        int backslashCount = 0;
        for (int i = index - 1; i >= 0 && chars[i] == '\\'; i--) {
            backslashCount++;
        }
        return (backslashCount & 1) == 1;
    }

    private static int getLineNumber(String content, int index) {
        return (int) content.substring(0, Math.min(index, content.length())).chars().filter(c -> c == '\n').count() + 1;
    }

    public static List<String> getMethodsCalling(String content, String className, List<String> touchedMethods) {
        return getMethodsCalling(content, className, null, touchedMethods);
    }

    public static List<String> getMethodsCalling(String content, String targetSimpleName, String targetFqn, List<String> touchedMethods) {
        return getMethodsCalling(content, targetSimpleName, targetFqn, touchedMethods, true);
    }

    /**
     * Finds methods in {@code content} that call any of {@code touchedMethods} on the target class.
     * {@code supertypeSimpleNames} lists the target's interfaces/superclass simple names so that
     * injection via interface type (e.g. {@code @Autowired IFoo foo;}) is also detected.
     * {@code confirmedDependent} indicates whether this file is confirmed as a dependent by the
     * reverse dependency graph; when true, structural fallback is enabled for this file even if
     * literal method names don't appear, allowing the BFS to discover transitive dependencies.
     */
    public static List<String> getMethodsCalling(String content, String targetSimpleName, String targetFqn,
                                                  List<String> supertypeSimpleNames,
                                                  List<String> touchedMethods, boolean allowBroadFallback, boolean confirmedDependent) {
        return getMethodsCallingImpl(content, targetSimpleName, targetFqn, supertypeSimpleNames, touchedMethods, allowBroadFallback, confirmedDependent);
    }

    public static List<String> getMethodsCalling(String content, String targetSimpleName, String targetFqn,
                                                  List<String> supertypeSimpleNames,
                                                  List<String> touchedMethods, boolean allowBroadFallback) {
        return getMethodsCallingImpl(content, targetSimpleName, targetFqn, supertypeSimpleNames, touchedMethods, allowBroadFallback, false);
    }

    public static List<String> getMethodsCalling(String content, String targetSimpleName, String targetFqn, List<String> touchedMethods, boolean allowBroadFallback) {
        return getMethodsCallingImpl(content, targetSimpleName, targetFqn, Collections.emptyList(), touchedMethods, allowBroadFallback, false);
    }

    private static List<String> getMethodsCallingImpl(String content, String targetSimpleName, String targetFqn,
                                                       List<String> supertypeSimpleNames,
                                                       List<String> touchedMethods, boolean allowBroadFallback, boolean confirmedDependent) {
        if (content == null || content.isBlank() || touchedMethods == null || touchedMethods.isEmpty()) {
            return Collections.emptyList();
        }

        boolean hasAnyTouchedToken = false;
        String matchedToken = null;
        for (String method : touchedMethods) {
            String pureMethodName = method == null ? "" : method.split("\\(")[0].trim();
            if (!isValidMethodName(pureMethodName)) {
                continue;
            }
            if (content.contains(pureMethodName + "(") ||
                content.contains("." + pureMethodName + "(") ||
                content.contains("::" + pureMethodName)) {
                hasAnyTouchedToken = true;
                matchedToken = pureMethodName;
                break;
            }
        }
        if (!hasAnyTouchedToken) {
            // None of the touched method names appear literally in this file.
            // When structural fallback is disabled, we require exact method name matches to prevent
            // false positives in the transitive call chain. Even if this is a confirmed dependent
            // (reverse graph shows a dependency), we don't want to propagate methods that don't
            // actually call the touched methods - that would pollute the BFS with unrelated paths.
            if (!structuralFallbackEnabled) {
                debug("[DEBUG] getMethodsCallingImpl: no touched tokens, structural fallback disabled — skipping. target=" + targetSimpleName);
                return Collections.emptyList();
            }
            // Structural analysis is enabled: check whether the target is even referenced before
            // paying the cost of a full scan.
            boolean likelyRef = isLikelyTargetReferencedInFile(content, targetSimpleName, targetFqn);
            if (!likelyRef) {
                debug("[DEBUG] getMethodsCallingImpl: no tokens, no reference — skipping. target=" + targetSimpleName);
                return Collections.emptyList();
            }
            debug("[DEBUG] getMethodsCallingImpl: no touched tokens, structural fallback enabled. Running AST/structural scan. target=" + targetSimpleName);
            // Pass touchedMethods to structural scan so it can filter to only methods calling those specific methods
            List<String> structural = getMethodsUsingTarget(content, targetSimpleName, targetFqn, supertypeSimpleNames, touchedMethods);
            debug("[DEBUG] getMethodsCallingImpl: structural scan found " + structural.size() + " callers for target=" + targetSimpleName + ": " + structural);
            return structural;
        }
        debug("[DEBUG] getMethodsCallingImpl: found token '" + matchedToken + "' in content, targetSimpleName=" + targetSimpleName);

        Set<String> callers = new HashSet<>();

        // 1. Identify ALL possible instance names (variables, constructor params, fields).
        // supertypeSimpleNames allows detection when the field is typed as an interface the target implements.
        Set<String> instanceNames = extractInstanceNames(content, targetSimpleName, targetFqn, supertypeSimpleNames);

        // Also consider static calls like TargetClass.someMethod(...)
        if (targetSimpleName != null && !targetSimpleName.isBlank()) {
            instanceNames.add(targetSimpleName.trim());
        }

        // 2. Identify all method boundaries in the current file accurately
        List<MethodSpan> methodsInFile = buildMethodSpans(content);

        // 3. Check whether touched methods can be called unqualified due to static import
        boolean staticImportAll = false;
        Set<String> staticallyImportedMethods = new HashSet<>();
        if (targetFqn != null && !targetFqn.isBlank()) {
            Pattern staticImportPattern = Pattern.compile("(?m)^\\s*import\\s+static\\s+([\\w\\.\\*]+)\\s*;\\s*$");
            Matcher sim = staticImportPattern.matcher(content);
            while (sim.find()) {
                String imp = sim.group(1);
                if (imp == null) continue;
                if (imp.equals(targetFqn + ".*")) {
                    staticImportAll = true;
                } else if (imp.startsWith(targetFqn + ".")) {
                    String m = imp.substring((targetFqn + ".").length());
                    if (isValidMethodName(m)) {
                        staticallyImportedMethods.add(m);
                    }
                }
            }
        }

        // 4. Caller detection: regex (qualified calls + method refs) merged with AST when available.
        // Regex handles static imports and method references (obj::method) that AST misses.
        // AST handles chained calls, lambdas, and anonymous classes that regex misattributes.
        // Both run in parallel on the pre-computed instanceNames; results are merged.
        if (instanceNames != null && !instanceNames.isEmpty()) {
            debug("[DEBUG] step 4 regex: instanceNames=" + instanceNames + ", touchedMethods=" + touchedMethods);
            for (String instanceName : instanceNames) {
                if (instanceName == null || instanceName.isBlank()) continue;
                for (String method : touchedMethods) {
                    String pureMethodName = method == null ? "" : method.split("\\(")[0].trim();
                    if (!isValidMethodName(pureMethodName)) continue;
                    // Qualified dot-call: instance.method(
                    Matcher cm = callPattern(instanceName, pureMethodName).matcher(content);
                    while (cm.find()) {
                        String enclosing = findEnclosingMethod(methodsInFile, cm.start());
                        debug("[DEBUG] step 4 regex: " + instanceName + "." + pureMethodName + "() enclosed by " + enclosing);
                        if (enclosing != null) callers.add(enclosing);
                    }
                    // Method reference: instance::method
                    Matcher rm = refPattern(instanceName, pureMethodName).matcher(content);
                    while (rm.find()) {
                        String enclosing = findEnclosingMethod(methodsInFile, rm.start());
                        if (enclosing != null) callers.add(enclosing);
                    }
                }
            }
            // Static import unqualified calls:
            // Covers static utility classes called via `import static Foo.*` or `import static Foo.method`.
            if (staticImportAll || !staticallyImportedMethods.isEmpty()) {
                for (String method : touchedMethods) {
                    String pureMethodName = method == null ? "" : method.split("\\(")[0].trim();
                    if (!isValidMethodName(pureMethodName)) continue;
                    if (!staticImportAll && !staticallyImportedMethods.contains(pureMethodName)) continue;
                    // Match unqualified call: methodName( not preceded by a dot (to avoid double-counting qualified calls)
                    Matcher um = unqualifiedCallPattern(pureMethodName).matcher(content);
                    while (um.find()) {
                        String enclosing = findEnclosingMethod(methodsInFile, um.start());
                        debug("[DEBUG] step 4 static-import: " + pureMethodName + "() enclosed by " + enclosing);
                        if (enclosing != null && !enclosing.equals(pureMethodName)) callers.add(enclosing);
                    }
                    // Method reference: ::methodName
                    Matcher ur = unqualifiedRefPattern(pureMethodName).matcher(content);
                    while (ur.find()) {
                        String enclosing = findEnclosingMethod(methodsInFile, ur.start());
                        if (enclosing != null && !enclosing.equals(pureMethodName)) callers.add(enclosing);
                    }
                }
                debug("[DEBUG] step 4 static-import: callers so far=" + callers.size());
            }
            debug("[DEBUG] step 4 regex: found " + callers.size() + " callers");

            // AST parallel path: precise handling of chained calls, lambdas, anonymous classes.
            // Uses the same instanceNames already computed above — no duplicate extraction.
            // Runs unconditionally when JavaParser is available (no separate flag needed —
            // it only adds results, never removes what regex already found).
            if (astCallerDetectionEnabled && AstInvocationFinder.isAvailable()) {
                List<String> astCallers = AstInvocationFinder.findCallerMethods(
                        content, instanceNames, touchedMethods);
                int before = callers.size();
                callers.addAll(astCallers);
                debug("[DEBUG] step 4 AST: added " + (callers.size() - before) + " new callers: " + astCallers);
            }
        }

        // 4a. Broad fallback — only when both regex and AST came up empty and the caller
        // explicitly allows looser matching (allowBroadFallback=true, confirmedDependent=false).
        // Matches any qualifier: anyVar.method(...) — higher false-positive risk.
        if (callers.isEmpty() && allowBroadFallback) {
            boolean likelyRef = isLikelyTargetReferencedInFile(content, targetSimpleName, targetFqn);
            if (likelyRef) {
                debug("[DEBUG] step 4a: broad fallback for target=" + targetSimpleName);
                for (String method : touchedMethods) {
                    String pureMethodName = method == null ? "" : method.split("\\(")[0].trim();
                    if (!isValidMethodName(pureMethodName)) continue;
                    Matcher aqm = anyCallPattern(pureMethodName).matcher(content);
                    while (aqm.find()) {
                        String enclosing = findEnclosingMethod(methodsInFile, aqm.start());
                        if (enclosing != null) callers.add(enclosing);
                    }
                    Matcher arm = anyRefPattern(pureMethodName).matcher(content);
                    while (arm.find()) {
                        String enclosing = findEnclosingMethod(methodsInFile, arm.start());
                        if (enclosing != null) callers.add(enclosing);
                    }
                }
                debug("[DEBUG] step 4a: found " + callers.size() + " callers");
            }
        }

        return new ArrayList<>(callers);
    }

    private static final class MethodSpan {
        final int start;
        final int endExclusive;
        final String name;
        MethodSpan(int start, int endExclusive, String name) {
            this.start = start;
            this.endExclusive = endExclusive;
            this.name = name;
        }
    }

    private static String findEnclosingMethod(List<MethodSpan> spans, int pos) {
        if (spans == null) {
            debug("[DEBUG] findEnclosingMethod: spans is null");
            return null;
        }
        debug("[DEBUG] findEnclosingMethod: checking position " + pos + " against " + spans.size() + " method spans");
        // Binary search: find the last span whose start <= pos, then verify pos < endExclusive.
        // Spans are in source order (sorted ascending by start), so this is valid.
        int lo = 0, hi = spans.size() - 1, candidate = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (spans.get(mid).start <= pos) {
                candidate = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (candidate >= 0) {
            MethodSpan s = spans.get(candidate);
            debug("[DEBUG] findEnclosingMethod: span " + s.name + " [" + s.start + ", " + s.endExclusive + ")");
            if (pos < s.endExclusive) {
                debug("[DEBUG] findEnclosingMethod: position " + pos + " is inside " + s.name);
                return s.name;
            }
        }
        debug("[DEBUG] findEnclosingMethod: position " + pos + " not found in any method span");
        return null;
    }

    private static int findMatchingBrace(String content, int openBracePos) {
        if (content == null || openBracePos < 0 || openBracePos >= content.length()) return -1;
        int depth = 0;
        boolean inBlockComment = false;
        boolean inLineComment = false;
        boolean inStringLiteral = false;
        boolean inCharLiteral = false;

        for (int i = openBracePos; i < content.length(); i++) {
            char ch = content.charAt(i);
            char next = (i + 1 < content.length()) ? content.charAt(i + 1) : '\0';

            if (inLineComment) {
                if (ch == '\n') inLineComment = false;
                continue;
            }
            if (inBlockComment) {
                if (ch == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inStringLiteral) {
                if (ch == '"' && !isEscaped(content, i)) inStringLiteral = false;
                continue;
            }
            if (inCharLiteral) {
                if (ch == '\'' && !isEscaped(content, i)) inCharLiteral = false;
                continue;
            }

            if (ch == '/' && next == '/') {
                inLineComment = true;
                i++;
                continue;
            }
            if (ch == '/' && next == '*') {
                inBlockComment = true;
                i++;
                continue;
            }
            if (ch == '"' && !isEscaped(content, i)) {
                inStringLiteral = true;
                continue;
            }
            if (ch == '\'' && !isEscaped(content, i)) {
                inCharLiteral = true;
                continue;
            }

            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) return i + 1;
            }
        }
        return -1;
    }

    private static boolean isEscaped(String content, int index) {
        int backslashCount = 0;
        for (int i = index - 1; i >= 0 && content.charAt(i) == '\\'; i--) {
            backslashCount++;
        }
        return (backslashCount & 1) == 1;
    }

    /**
     * Expands {@code methodNames} to include every method in the same class that
     * transitively calls any of them (intra-class delegation resolution).
     *
     * <p>This handles the common pattern where a private helper directly invokes an
     * external dependency, while a public method delegates to that private helper.
     * Without expansion the BFS only tracks the private name, which no upstream
     * caller ever invokes, so the chain dies.  With expansion the public method is
     * also included and the next BFS hop succeeds.
     *
     * <p>Bounded to 5 passes; terminates early when no new callers are found.
     */
    public static List<String> expandWithIntraClassCallers(String content, List<String> methodNames) {
        if (content == null || methodNames == null || methodNames.isEmpty()) return methodNames;
        List<MethodSpan> spans = buildMethodSpans(content);
        if (spans.isEmpty()) return methodNames;

        Set<String> expanded = new LinkedHashSet<>(methodNames);
        Set<String> frontier = new LinkedHashSet<>(methodNames);

        for (int pass = 0; pass < 5 && !frontier.isEmpty(); pass++) {
            Set<String> nextFrontier = new LinkedHashSet<>();
            for (MethodSpan span : spans) {
                if (expanded.contains(span.name)) continue;
                String body = content.substring(span.start, span.endExclusive);
                for (String tm : frontier) {
                    String pure = tm.split("\\(")[0].trim();
                    if (!isValidMethodName(pure)) continue;
                    // Unqualified call  pure(  or method-reference  ::pure
                    if (body.contains(pure + "(") || body.contains("::" + pure)) {
                        if (expanded.add(span.name)) {
                            nextFrontier.add(span.name);
                        }
                        break;
                    }
                }
            }
            frontier = nextFrontier;
        }
        return new ArrayList<>(expanded);
    }

    /**
     * Parses {@code content} to find every method declaration's span [headerStart, bodyEnd).
     * Used by both token-based and structural call-site finders to locate enclosing methods.
     */
    private static final Set<String> JAVA_KEYWORDS = new HashSet<>(Arrays.asList(
        "if", "else", "for", "while", "do", "switch", "case", "try", "catch",
        "finally", "synchronized", "return", "throw", "new", "instanceof",
        "assert", "break", "continue", "yield"
    ));

    static List<MethodSpan> buildMethodSpans(String content) {
        List<MethodSpan> spans = new ArrayList<>();
        if (content == null || content.isBlank()) return spans;
        Pattern methodDeclPattern = Pattern.compile(
            "(?s)(?:public|protected|private|static|final|\\s)+\\s*(?:<[^>]+>\\s+)?(?:[\\w\\<\\>\\[\\]\\.]+)\\s+(\\w+)\\s*\\(");
        Matcher mm = methodDeclPattern.matcher(content);
        while (mm.find()) {
            String name = mm.group(1);
            if (JAVA_KEYWORDS.contains(name)) continue;
            int paramStart = mm.end() - 1;
            // Skip past parameter list
            int depth = 1;
            int i = paramStart + 1;
            while (i < content.length() && depth > 0) {
                char c = content.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') depth--;
                i++;
            }
            if (depth != 0) continue;
            // Find the opening body brace
            int bodyStartBrace = -1;
            for (int j = i; j < content.length(); j++) {
                char c = content.charAt(j);
                if (c == '{') { bodyStartBrace = j; break; }
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r'
                        && !Character.isJavaIdentifierPart(c) && c != ',' && c != '.'
                        && c != '<' && c != '>' && c != '[' && c != ']') break;
            }
            if (bodyStartBrace < 0) continue;
            int bodyEndExclusive = findMatchingBrace(content, bodyStartBrace);
            if (bodyEndExclusive > bodyStartBrace) {
                spans.add(new MethodSpan(mm.start(), bodyEndExclusive, name));
            }
        }
        return spans;
    }

    /**
     * Finds methods that use the target class in any way (broad structural scan).
     * This is used as a fallback when touched method names don't appear literally.
     * To avoid over-reporting, this now accepts touchedMethods and only returns methods
     * that call at least one of those specific methods (if provided).
     */
    private static List<String> getMethodsUsingTarget(String content, String targetSimpleName, String targetFqn, List<String> supertypeSimpleNames, List<String> touchedMethods) {
        if (content == null || content.isBlank()) {
            return Collections.emptyList();
        }
        Set<String> instanceNames = extractInstanceNames(content, targetSimpleName, targetFqn, supertypeSimpleNames);
        if (targetSimpleName != null && !targetSimpleName.isBlank()) {
            instanceNames.add(targetSimpleName.trim());
        }
        if (instanceNames.isEmpty()) {
            return Collections.emptyList();
        }
        List<MethodSpan> methodsInFile = buildMethodSpans(content);
        Set<String> callers = new HashSet<>();
        
        // If touchedMethods are provided, try to match them specifically first
        if (touchedMethods != null && !touchedMethods.isEmpty()) {
            // Check for static import in this file — covers static utility classes
            boolean hasStaticImportAll = false;
            Set<String> hasStaticImportMethods = new HashSet<>();
            if (targetFqn != null && !targetFqn.isBlank()) {
                Pattern siPat = Pattern.compile("(?m)^\\s*import\\s+static\\s+([\\w\\.\\*]+)\\s*;\\s*$");
                Matcher sim = siPat.matcher(content);
                while (sim.find()) {
                    String imp = sim.group(1);
                    if (imp == null) continue;
                    if (imp.equals(targetFqn + ".*")) hasStaticImportAll = true;
                    else if (imp.startsWith(targetFqn + ".")) {
                        String m = imp.substring((targetFqn + ".").length());
                        if (isValidMethodName(m)) hasStaticImportMethods.add(m);
                    }
                }
            }

            for (String instanceName : instanceNames) {
                if (instanceName == null || instanceName.isBlank()) continue;
                for (String method : touchedMethods) {
                    String pureMethodName = method == null ? "" : method.split("\\(")[0].trim();
                    if (!isValidMethodName(pureMethodName)) continue;
                    
                    // Qualified dot-call: instance.method( or ClassName.method(
                    String specificCallPattern = "\\b" + Pattern.quote(instanceName) + "\\s*\\.\\s*" + Pattern.quote(pureMethodName) + "\\b\\s*\\(";
                    Matcher scm = Pattern.compile(specificCallPattern).matcher(content);
                    while (scm.find()) {
                        String enclosing = findEnclosingMethod(methodsInFile, scm.start());
                        debug("[DEBUG] getMethodsUsingTarget: " + instanceName + "." + pureMethodName + "() in method " + enclosing);
                        if (enclosing != null) callers.add(enclosing);
                    }
                    
                    // Method reference: instance::method
                    String specificRefPattern = "\\b" + Pattern.quote(instanceName) + "\\s*::\\s*" + Pattern.quote(pureMethodName) + "\\b";
                    Matcher srm = Pattern.compile(specificRefPattern).matcher(content);
                    while (srm.find()) {
                        String enclosing = findEnclosingMethod(methodsInFile, srm.start());
                        if (enclosing != null) callers.add(enclosing);
                    }
                }
            }

            // Unqualified static-import calls: getTULNCCRScore(...) with no qualifier.
            // Only runs when a static import of the target class is detected.
            if (hasStaticImportAll || !hasStaticImportMethods.isEmpty()) {
                for (String method : touchedMethods) {
                    String pureMethodName = method == null ? "" : method.split("\\(")[0].trim();
                    if (!isValidMethodName(pureMethodName)) continue;
                    if (!hasStaticImportAll && !hasStaticImportMethods.contains(pureMethodName)) continue;
                    Pattern unqualifiedCall = Pattern.compile("(?<![.\\w])" + Pattern.quote(pureMethodName) + "\\s*\\(");
                    Matcher um = unqualifiedCall.matcher(content);
                    while (um.find()) {
                        String enclosing = findEnclosingMethod(methodsInFile, um.start());
                        debug("[DEBUG] getMethodsUsingTarget static-import: " + pureMethodName + "() in method " + enclosing);
                        if (enclosing != null && !enclosing.equals(pureMethodName)) callers.add(enclosing);
                    }
                }
            }
        }
        
        // If we found specific matches, return them
        if (!callers.isEmpty()) {
            debug("[DEBUG] getMethodsUsingTarget: target=" + targetSimpleName + " instances=" + instanceNames + " callers=" + callers);
            return new ArrayList<>(callers);
        }
        
        // If touchedMethods were provided but none matched, return empty (don't fall back to broad scan)
        // This prevents over-reporting when the file uses the target class but doesn't call the changed methods
        if (touchedMethods != null && !touchedMethods.isEmpty()) {
            debug("[DEBUG] getMethodsUsingTarget: target=" + targetSimpleName + " touchedMethods provided but none found, returning empty");
            return Collections.emptyList();
        }
        
        // Fallback: find ANY usage of the target (original broad behavior)
        // This is only used when no specific methods are provided (backward compatibility)
        for (String instanceName : instanceNames) {
            if (instanceName == null || instanceName.isBlank()) continue;
            String callPattern = "\\b" + Pattern.quote(instanceName) + "\\s*\\.\\s*\\w+\\s*\\(";
            Pattern cp = Pattern.compile(callPattern);
            Matcher cm = cp.matcher(content);
            while (cm.find()) {
                int callPos = cm.start();
                String enclosing = findEnclosingMethod(methodsInFile, callPos);
                debug("[DEBUG] getMethodsUsingTarget: " + instanceName + "." + content.substring(cm.start(), Math.min(cm.end() + 20, content.length())) + " in method " + enclosing);
                if (enclosing != null) callers.add(enclosing);
            }
            String refPattern = "\\b" + Pattern.quote(instanceName) + "\\s*::\\s*\\w+";
            Pattern rp = Pattern.compile(refPattern);
            Matcher rm = rp.matcher(content);
            while (rm.find()) {
                int callPos = rm.start();
                String enclosing = findEnclosingMethod(methodsInFile, callPos);
                if (enclosing != null) callers.add(enclosing);
            }
        }
        debug("[DEBUG] getMethodsUsingTarget: target=" + targetSimpleName + " instances=" + instanceNames + " callers=" + callers);
        return new ArrayList<>(callers);
    }
    
    // Backward compatibility wrapper
    private static List<String> getMethodsUsingTarget(String content, String targetSimpleName, String targetFqn, List<String> supertypeSimpleNames) {
        return getMethodsUsingTarget(content, targetSimpleName, targetFqn, supertypeSimpleNames, null);
    }

    /**
     * Extracts all possible instance names (field/variable/parameter names) that could
     * reference the target class or one of its supertypes.
     */
    static Set<String> extractInstanceNames(String content, String targetSimpleName, String targetFqn, List<String> supertypeSimpleNames) {
        Set<String> instanceNames = new LinkedHashSet<>();
        Set<String> typeTokens = new LinkedHashSet<>();
        if (targetSimpleName != null && !targetSimpleName.isBlank()) typeTokens.add(targetSimpleName);
        if (targetFqn != null && !targetFqn.isBlank()) typeTokens.add(targetFqn);
        // Include interface/superclass names so injection via interface type is detected.
        // e.g. @Autowired ITargetService svc; — 'ITargetService' resolves 'svc' as an instance name.
        if (supertypeSimpleNames != null) {
            for (String s : supertypeSimpleNames) {
                if (s != null && !s.isBlank()) typeTokens.add(s);
            }
        }

        for (String token : typeTokens) {
            Pattern instancePattern = Pattern.compile("\\b" + Pattern.quote(token) + "\\b(?:<[^>]*>)?\\s+(\\w+)\\b");
            Matcher im = instancePattern.matcher(content);
            while (im.find()) {
                instanceNames.add(im.group(1));
            }

            // Heuristic: common Spring style uses a lowerCamelCase field/param name based on the type.
            // This helps when the type is referenced via interface/supertype and the declaration doesn't match token.
            String simple = token;
            int lastDot = simple.lastIndexOf('.');
            if (lastDot >= 0 && lastDot + 1 < simple.length()) {
                simple = simple.substring(lastDot + 1);
            }
            if (!simple.isBlank() && Character.isJavaIdentifierStart(simple.charAt(0))) {
                String inferred = Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
                Pattern inferredToken = Pattern.compile("\\b" + Pattern.quote(inferred) + "\\b");
                if (inferredToken.matcher(content).find()) {
                    instanceNames.add(inferred);
                }
            }
        }
        debug("[DEBUG] extractInstanceNames: targetSimpleName=" + targetSimpleName + ", targetFqn=" + targetFqn + ", extracted=" + instanceNames);
        return instanceNames;
    }

    public static List<String> extractSpecificEndpoints(String content, String className, List<String> touchedMethods) {
        List<String> endpoints = new ArrayList<>();
        String instanceName = null;
        Pattern instancePattern = Pattern.compile("\\b" + Pattern.quote(className) + "\\s+(\\w+)\\b");
        Matcher im = instancePattern.matcher(content);
        if (im.find()) instanceName = im.group(1);
        if (instanceName == null) return endpoints;

        String[] sections = content.split("@(?:Get|Post|Put|Delete|Patch)Mapping");
        for (int i = 1; i < sections.length; i++) {
            String section = sections[i];
            for (String method : touchedMethods) {
                String methodName = method.replaceAll(".*\\s+(\\w+)\\(.*", "$1");
                if (!isValidMethodName(methodName)) {
                    continue;
                }
                if (section.contains(instanceName + "." + methodName)) {
                    Pattern p = Pattern.compile("\"([^\"]+)\"");
                    Matcher pm = p.matcher(section);
                    if (pm.find()) endpoints.add(pm.group(1));
                    break;
                }
            }
        }
        return endpoints;
    }

    public static List<String> extractControllerEndpoints(String content, String className, List<String> touchedMethods) {
        if (touchedMethods == null || touchedMethods.isEmpty()) return Collections.emptyList();
        List<String> endpoints = new ArrayList<>();
        String[] lines = content.split("\\R");

        String classPrefix = "";
        Pattern classMapping = Pattern.compile("@(?:RequestMapping|PostMapping|GetMapping|PutMapping|DeleteMapping|PatchMapping)\\s*\\((?:(?:value|path)\\s*=\\s*)?\"([^\"]+)\"");
        Pattern mappingStart = Pattern.compile("@(?:RequestMapping|PostMapping|GetMapping|PutMapping|DeleteMapping|PatchMapping)\\b");
        Pattern pathPattern0 = Pattern.compile("(?:value|path)\\s*=\\s*\"([^\"]+)\"|\"([^\"]+)\"");
        for (int classLineIdx = 0; classLineIdx < Math.min(lines.length, 80); classLineIdx++) {
            // Fast path: single-line @RequestMapping("/prefix")
            Matcher cm = classMapping.matcher(lines[classLineIdx]);
            if (cm.find()) {
                classPrefix = cm.group(1);
                break;
            }
            // Multi-line class-level mapping: buffer continuation lines until ')' or class/brace
            if (mappingStart.matcher(lines[classLineIdx]).find()) {
                StringBuilder annoBuf = new StringBuilder(lines[classLineIdx]).append(' ');
                int j = classLineIdx + 1;
                while (j < Math.min(lines.length, classLineIdx + 30)
                        && !lines[j].contains(")")
                        && !lines[j].contains("class ")
                        && !lines[j].contains("{")) {
                    annoBuf.append(lines[j]).append(' ');
                    j++;
                }
                if (j < lines.length) annoBuf.append(lines[j]).append(' ');
                Matcher pm = pathPattern0.matcher(annoBuf.toString());
                if (pm.find()) {
                    classPrefix = pm.group(1) != null ? pm.group(1) : pm.group(2);
                    break;
                }
            }
            if (lines[classLineIdx].contains("class " + className)) break;
        }
        debug("[DEBUG] extractControllerEndpoints: className=" + className + ", classPrefix=" + classPrefix + ", touchedMethods=" + touchedMethods);

        Pattern methodMapping = Pattern.compile("@(Request|Get|Post|Put|Delete|Patch)Mapping\\b");
        Pattern pathPattern = Pattern.compile("(?:value|path)\\s*=\\s*\"([^\"]+)\"|\"([^\"]+)\"");

        for (String rawMethod : touchedMethods) {
            String methodName = rawMethod.split("\\(")[0].trim();
            if (!isValidMethodName(methodName)) continue;

            Pattern decl = Pattern.compile("(?s)(?:public|protected|private)\\s+[^{;=]+?\\b" + Pattern.quote(methodName) + "\\s*\\(");
            Matcher dm = decl.matcher(content);
            if (!dm.find()) {
                debug("[DEBUG] extractControllerEndpoints: method declaration not found for " + methodName);
                continue;
            }

            // Find matching closing parenthesis (handles nested parens in annotations)
            int paramStart = dm.end() - 1;
            int depth = 1;
            int charIdx = paramStart + 1;
            while (charIdx < content.length() && depth > 0) {
                char c = content.charAt(charIdx);
                if (c == '(') depth++;
                else if (c == ')') depth--;
                charIdx++;
            }
            if (depth != 0) {
                debug("[DEBUG] extractControllerEndpoints: unmatched parentheses for " + methodName);
                continue;
            }

            String matchedDecl = dm.group();
            debug("[DEBUG] extractControllerEndpoints: matched declaration snippet: " + matchedDecl.substring(0, Math.min(150, matchedDecl.length())).replaceAll("\\s+", " "));

            int publicPos = matchedDecl.indexOf("public");
            if (publicPos == -1) publicPos = matchedDecl.indexOf("protected");
            if (publicPos == -1) publicPos = matchedDecl.indexOf("private");
            int methodLine = getLineNumber(content, dm.start() + publicPos);
            int methodLineIdx = Math.max(0, Math.min(lines.length - 1, methodLine - 1));
            debug("[DEBUG] extractControllerEndpoints: found method " + methodName + " at line " + methodLine + " (idx=" + methodLineIdx + "), match start was at line " + getLineNumber(content, dm.start()) + ", publicPos=" + publicPos);

            // Scan backward for annotations. Expand window to 100 lines to handle comments/spacing.
            // Be lenient: if we find ANY mapping annotation, use it even if scan was interrupted.
            int annoStart = methodLineIdx - 1;
            int lastAnnotationLine = -1;
            int nonAnnotationLinesSeen = 0;
            final int ANNOTATION_SCAN_WINDOW = 100;
            final int ALLOWED_INTERRUPTIONS = 5;  // Allow up to 5 non-annotation lines before stopping

            while (annoStart >= 0 && annoStart >= methodLineIdx - ANNOTATION_SCAN_WINDOW && nonAnnotationLinesSeen < ALLOWED_INTERRUPTIONS) {
                String l = lines[annoStart].trim();
                debug("[DEBUG] extractControllerEndpoints: scanning line " + (annoStart+1) + ": '" + l + "'");
                if (l.isEmpty()) {
                    annoStart--;
                    continue;
                }
                // HARD STOP: closing brace marks end of previous method body — annotations cannot appear before it.
                // This prevents accidentally picking up annotations from preceding methods.
                if (l.equals("}")) {
                    debug("[DEBUG] extractControllerEndpoints: hit closing brace at line " + (annoStart+1) + " — stopping annotation scan (end of previous block)");
                    break;
                }
                if (l.startsWith("@")) {
                    lastAnnotationLine = annoStart;
                    nonAnnotationLinesSeen = 0;  // Reset counter when we find an annotation
                    debug("[DEBUG] extractControllerEndpoints: found annotation at line " + (annoStart+1));
                    annoStart--;
                    continue;
                }
                if (l.contains("=") || l.endsWith(")") || l.endsWith(",") || l.startsWith("*")) {
                    debug("[DEBUG] extractControllerEndpoints: treating line " + (annoStart+1) + " as annotation continuation");
                    annoStart--;
                    continue;
                }
                // Non-annotation, non-continuation line — allow a few of these (Javadoc, comments, etc.)
                nonAnnotationLinesSeen++;
                debug("[DEBUG] extractControllerEndpoints: non-annotation line at " + (annoStart+1) + " (count=" + nonAnnotationLinesSeen + ")");
                annoStart--;
            }
            if (lastAnnotationLine >= 0) {
                annoStart = lastAnnotationLine;
            } else {
                annoStart = Math.max(0, methodLineIdx - 1);
            }
            debug("[DEBUG] extractControllerEndpoints: annotation block range [" + annoStart + ", " + methodLineIdx + "), lastAnnotationLine=" + lastAnnotationLine);

            for (int i = Math.max(0, annoStart); i < methodLineIdx; i++) {
                Matcher mm = methodMapping.matcher(lines[i]);
                if (!mm.find()) continue;
                String annotationType = mm.group(1);
                debug("[DEBUG] extractControllerEndpoints: found mapping annotation at line " + (i+1) + ": " + lines[i]);

                StringBuilder annoBuf = new StringBuilder(lines[i]).append(' ');
                int j = i + 1;
                while (j < methodLineIdx && !lines[j].contains(")") && (j - i) < 30) {
                    annoBuf.append(lines[j]).append(' ');
                    j++;
                }
                if (j < methodLineIdx) {
                    annoBuf.append(lines[j]).append(' ');
                }
                debug("[DEBUG] extractControllerEndpoints: buffered annotation: " + annoBuf.toString().substring(0, Math.min(200, annoBuf.length())));

                String methodPath = "";
                Matcher pm = pathPattern.matcher(annoBuf.toString());
                if (pm.find()) {
                    methodPath = pm.group(1) != null ? pm.group(1) : pm.group(2);
                    debug("[DEBUG] extractControllerEndpoints: extracted path=" + methodPath);
                } else {
                    debug("[DEBUG] extractControllerEndpoints: NO PATH MATCH in buffered annotation");
                }
                String httpVerb = verbFromAnnotationType(annotationType, annoBuf.toString());
                String fullPath = (classPrefix + "/" + methodPath).replaceAll("//+", "/");
                endpoints.add(className + "." + methodName + " [" + httpVerb + " " + fullPath + "]");
            }
        }

        debug("[DEBUG] extractControllerEndpoints: final endpoints=" + endpoints);
        return endpoints.stream().distinct().collect(Collectors.toList());
    }

    public static List<String> extractAllControllerEndpoints(String content, String className) {
        if (content == null || content.isBlank()) return Collections.emptyList();
        List<String> endpoints = new ArrayList<>();
        String[] lines = content.split("\\R");

        String classPrefix = "";
        Pattern classMapping = Pattern.compile("@RequestMapping\\s*\\((?:value\\s*=\\s*)?\"([^\"]+)\"");
        for (int i = 0; i < Math.min(lines.length, 80); i++) {
            Matcher cm = classMapping.matcher(lines[i]);
            if (cm.find()) {
                classPrefix = cm.group(1);
                break;
            }
            if (className != null && lines[i].contains("class " + className)) break;
        }

        Pattern mappingStart = Pattern.compile("@(Request|Get|Post|Put|Delete|Patch)Mapping\\b");
        Pattern methodSignature = Pattern.compile("(?:public|protected|private)\\s+[^{;]+?\\b(\\w+)\\s*\\([^)]*\\)\\s*(?:throws [^{]+)?\\s*\\{");
        Pattern pathPattern = Pattern.compile("(?:value|path)\\s*=\\s*\"([^\"]+)\"|\"([^\"]+)\"");

        for (int i = 0; i < lines.length; i++) {
            Matcher mappingMatcher = mappingStart.matcher(lines[i]);
            if (!mappingMatcher.find()) continue;
            String annotationType = mappingMatcher.group(1);

            StringBuilder annoBuf = new StringBuilder(lines[i]).append(' ');
            int j = i + 1;
            while (j < lines.length && !lines[j].contains(")") && !lines[j].contains("class ") && !lines[j].contains("{") && (j - i) < 30) {
                annoBuf.append(lines[j]).append(' ');
                j++;
            }
            if (j < lines.length) {
                annoBuf.append(lines[j]).append(' ');
            }

            String methodPath = "";
            Matcher pm = pathPattern.matcher(annoBuf.toString());
            if (pm.find()) {
                methodPath = pm.group(1) != null ? pm.group(1) : pm.group(2);
            }
            String httpVerb = verbFromAnnotationType(annotationType, annoBuf.toString());

            int k = j;
            while (k < lines.length && lines[k].trim().startsWith("@")) {
                k++;
            }
            if (k >= lines.length) continue;

            StringBuilder sigBuf = new StringBuilder(lines[k]).append('\n');
            int s = k + 1;
            while (s < lines.length && !lines[s].contains("{") && (s - k) < 6) {
                sigBuf.append(lines[s]).append('\n');
                s++;
            }
            if (s < lines.length) sigBuf.append(lines[s]);

            Matcher sm = methodSignature.matcher(sigBuf.toString().replaceAll("\\s+", " "));
            if (!sm.find()) continue;
            String methodName = sm.group(1);
            if (!isValidMethodName(methodName)) continue;

            String fullPath = (classPrefix + "/" + methodPath).replaceAll("//+", "/");
            String effectiveClassName = (className == null || className.isBlank()) ? "Controller" : className;
            endpoints.add(effectiveClassName + "." + methodName + " [" + httpVerb + " " + fullPath + "]");
        }

        return endpoints.stream().distinct().collect(Collectors.toList());
    }

    private static String verbFromAnnotationType(String type, String annoBuf) {
        switch (type) {
            case "Get":    return "GET";
            case "Post":   return "POST";
            case "Put":    return "PUT";
            case "Delete": return "DELETE";
            case "Patch":  return "PATCH";
            case "Request":
                Matcher m = Pattern.compile("method\\s*=\\s*RequestMethod\\.(GET|POST|PUT|DELETE|PATCH)").matcher(annoBuf);
                return m.find() ? m.group(1) : "ANY";
            default: return "GET";
        }
    }

    public static List<String> filterValidMethodNames(Collection<String> methods) {
        if (methods == null || methods.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> filtered = new ArrayList<>();
        for (String method : methods) {
            if (isValidMethodName(method)) {
                filtered.add(method);
            }
        }
        return filtered;
    }

    private static boolean isLikelyTargetReferencedInFile(String content, String targetSimpleName, String targetFqn) {
        if (content == null || content.isBlank()) return false;

        // Strong signal: the fully-qualified name appears anywhere in the file.
        if (targetFqn != null && !targetFqn.isBlank() && content.contains(targetFqn)) {
            return true;
        }

        // Strong signal: explicit import of the FQN or its nested types.
        if (targetFqn != null && !targetFqn.isBlank()) {
            Pattern importPattern = Pattern.compile("(?m)^\\s*import\\s+" + Pattern.quote(targetFqn) + "(?:\\s*;|\\.[\\w\\*]+\\s*;)\\s*$");
            if (importPattern.matcher(content).find()) {
                return true;
            }
            Pattern staticImportPattern = Pattern.compile("(?m)^\\s*import\\s+static\\s+" + Pattern.quote(targetFqn) + "(?:\\.[\\w\\*]+)?\\s*;\\s*$");
            if (staticImportPattern.matcher(content).find()) {
                return true;
            }
        }

        // Weak signal: the simple name appears as an identifier token. Keep this conservative to avoid
        // enabling the expensive fallback scan for unrelated files.
        if (targetSimpleName == null || targetSimpleName.isBlank()) return false;
        String t = targetSimpleName.trim();
        if (!Character.isJavaIdentifierStart(t.charAt(0))) return false;

        Pattern simpleToken = Pattern.compile("\\b" + Pattern.quote(t) + "\\b");
        if (!simpleToken.matcher(content).find()) {
            return false;
        }

        // Prefer type-ish contexts.
        if (Pattern.compile("\\bnew\\s+" + Pattern.quote(t) + "\\b").matcher(content).find()) return true;
        if (Pattern.compile("\\b" + Pattern.quote(t) + "\\s*\\.").matcher(content).find()) return true; // static usage
        if (Pattern.compile("\\b" + Pattern.quote(t) + "\\s*<").matcher(content).find()) return true; // generics
        if (Pattern.compile("\\b" + Pattern.quote(t) + "\\b\\s+\\w+").matcher(content).find()) return true; // variable/param decl

        // If the simple name appears but not in any type-ish context, it's likely just a method name
        // or other identifier that happens to match, not an actual reference to the target class.
        return false;
    }

    private static boolean isPlausibleQualifierIdentifier(String content, String qualifier, String targetSimpleName) {
        if (content == null || qualifier == null) return false;
        String q = qualifier.trim();
        if (q.isEmpty()) return false;

        // Reject obvious non-qualifiers / keywords.
        String normalized = q.toLowerCase(Locale.ROOT);
        if (normalized.equals("this") || normalized.equals("super") || normalized.equals("new")) return false;
        if (NON_METHOD_TOKENS.contains(normalized)) return false;

        // Must be a valid Java identifier.
        if (!Character.isJavaIdentifierStart(q.charAt(0))) return false;
        for (int i = 1; i < q.length(); i++) {
            if (!Character.isJavaIdentifierPart(q.charAt(i))) return false;
        }

        // If we know the target class name, only accept qualifiers that look like a variable of that type
        // (or a static call using the class name itself).
        if (targetSimpleName != null && !targetSimpleName.isBlank()) {
            String t = targetSimpleName.trim();
            if (q.equals(t)) return true; // static call: TargetClass.method(...)

            Pattern decl = Pattern.compile("\\b" + Pattern.quote(t) + "\\b(?:<[^>]*>)?\\s+" + Pattern.quote(q) + "\\b");
            if (decl.matcher(content).find()) return true;

            Pattern assignNew = Pattern.compile("\\b" + Pattern.quote(q) + "\\b\\s*=\\s*new\\s+" + Pattern.quote(t) + "\\b");
            if (assignNew.matcher(content).find()) return true;

            // No declaration found in this file, but the qualifier may be a field inherited from a parent class
            // (e.g. `protected ServiceImpl service` declared in a base class).
            // A lowercase-starting identifier is almost always a field/variable reference, not a class name,
            // so accept it as plausible rather than silently dropping the call.
            if (Character.isLowerCase(q.charAt(0))) return true;

            return false;
        }

        return true;
    }

    private static boolean isValidMethodName(String name) {
        if (name == null) return false;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return false;
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        if (NON_METHOD_TOKENS.contains(normalized)) return false;
        char first = trimmed.charAt(0);
        return Character.isJavaIdentifierStart(first);
    }

    /**
     * Extracts SOAP operation names from a JAX-WS endpoint class that are exposed by the
     * given touched methods. Returns strings in the form "SOAP: operationName".
     * Falls back to the @WebMethod operationName attribute if set, otherwise uses the Java method name.
     */
    public static List<String> extractSoapOperations(String content, List<String> touchedMethods) {
        List<String> ops = new ArrayList<>();
        if (touchedMethods == null || touchedMethods.isEmpty()) return ops;
        String[] lines = content.split("\\R");
        Pattern webMethod = Pattern.compile("@WebMethod\\s*(?:\\(([^)]*)\\))?");
        for (String rawMethod : touchedMethods) {
            String methodName = rawMethod.split("\\(")[0].trim();
            if (!isValidMethodName(methodName)) continue;
            // Find the method declaration line
            Pattern decl = Pattern.compile("(?:public|protected)\\s+\\S+\\s+" + Pattern.quote(methodName) + "\\s*\\(");
            Matcher dm = decl.matcher(content);
            if (!dm.find()) continue;
            int methodLine = getLineNumber(content, dm.start());
            // Scan up to 5 lines above for @WebMethod
            String operationName = methodName;
            for (int i = Math.max(0, methodLine - 6); i < methodLine - 1; i++) {
                Matcher wm = webMethod.matcher(lines[i]);
                if (wm.find()) {
                    String attrs = wm.group(1);
                    if (attrs != null) {
                        Matcher nameAttr = Pattern.compile("operationName\\s*=\\s*\"([^\"]+)\"").matcher(attrs);
                        if (nameAttr.find()) operationName = nameAttr.group(1);
                    }
                    break;
                }
            }
            ops.add("SOAP: " + operationName);
        }
        return ops;
    }

    /**
     * Extracts Feign client operations from a @FeignClient interface that are called by the
     * given touched methods. Returns strings in the form "HTTP_METHOD /path (FeignClient: InterfaceName)".
     */
    public static List<String> extractFeignOperations(String content, List<String> touchedMethods) {
        List<String> ops = new ArrayList<>();
        if (touchedMethods == null || touchedMethods.isEmpty()) return ops;
        // Extract the Feign client name
        Matcher nameMatcher = Pattern.compile("@FeignClient\\s*\\([^)]*name\\s*=\\s*\"([^\"]+)\"").matcher(content);
        String clientName = nameMatcher.find() ? nameMatcher.group(1) : "unknown";

        Pattern mappingAnno = Pattern.compile("@(Get|Post|Put|Delete|Patch|Request)Mapping\\b[^\\n]*");
        Pattern pathPat = Pattern.compile("(?:value|path)\\s*=\\s*\"([^\"]+)\"|\"([^\"]+)\"");

        for (String rawMethod : touchedMethods) {
            String methodName = rawMethod.split("\\(")[0].trim();
            if (!isValidMethodName(methodName)) continue;
            Pattern decl = Pattern.compile("(?:public|default|\\s)\\s*\\S+\\s+" + Pattern.quote(methodName) + "\\s*\\(");
            Matcher dm = decl.matcher(content);
            if (!dm.find()) continue;
            int methodLine = getLineNumber(content, dm.start());
            String[] lines = content.split("\\R");
            for (int i = Math.max(0, methodLine - 5); i < methodLine - 1; i++) {
                Matcher mm = mappingAnno.matcher(lines[i]);
                if (!mm.find()) continue;
                String verb = mm.group(1).equals("Request") ? "HTTP" : mm.group(1).toUpperCase();
                Matcher pm = pathPat.matcher(mm.group(0));
                String path = pm.find() ? (pm.group(1) != null ? pm.group(1) : pm.group(2)) : methodName;
                ops.add(verb + " " + path + " (Feign: " + clientName + ")");
                break;
            }
        }
        return ops;
    }

    /**
     * Extracts gRPC RPC call names from a gRPC client class for the given touched methods.
     * Returns strings in the form "gRPC: rpcMethodName".
     */
    public static List<String> extractGrpcOperations(String content, List<String> touchedMethods) {
        List<String> ops = new ArrayList<>();
        if (touchedMethods == null || touchedMethods.isEmpty()) return ops;
        // Detect stub calls: stubVar.someRpcMethod(request)
        Pattern stubCall = Pattern.compile("\\b(\\w+)\\.(\\w+)\\s*\\(\\s*\\w*Request");
        for (String rawMethod : touchedMethods) {
            String methodName = rawMethod.split("\\(")[0].trim();
            if (!isValidMethodName(methodName)) continue;
            // Find the method body
            Pattern decl = Pattern.compile("(?:public|protected|private)\\s+\\S+\\s+" + Pattern.quote(methodName) + "\\s*\\(");
            Matcher dm = decl.matcher(content);
            if (!dm.find()) continue;
            int bodyStart = content.indexOf('{', dm.end());
            if (bodyStart == -1) continue;
            // Scan up to 3000 chars of body for stub calls
            String body = content.substring(bodyStart, Math.min(content.length(), bodyStart + 3000));
            Matcher sc = stubCall.matcher(body);
            while (sc.find()) {
                String rpcName = sc.group(2);
                // Filter out noise: newBlockingStub, build, etc.
                if (rpcName.startsWith("new") || rpcName.equals("build") || rpcName.equals("get")) continue;
                ops.add("gRPC: " + rpcName);
            }
        }
        return ops;
    }

}
