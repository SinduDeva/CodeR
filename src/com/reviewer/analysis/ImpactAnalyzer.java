package com.reviewer.analysis;

import com.reviewer.model.Models.*;
import java.util.regex.*;
import java.util.*;
import java.util.stream.Collectors;

public class ImpactAnalyzer {
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
            // When confirmedDependent=true (reverse graph already verified dependency), run
            // structural fallback to find ANY calls on the target, enabling transitive BFS.
            // When confirmedDependent=false, respect the structural fallback config flag.
            boolean shouldRunStructural = confirmedDependent || structuralFallbackEnabled;
            if (!shouldRunStructural) {
                debug("[DEBUG] getMethodsCallingImpl: no touched tokens, structural fallback disabled, not a confirmed dependent — skipping. target=" + targetSimpleName);
                return Collections.emptyList();
            }
            // Structural analysis is enabled (either via confirmedDependent or config):
            // check whether the target is even referenced before paying the cost of a full scan.
            boolean likelyRef = isLikelyTargetReferencedInFile(content, targetSimpleName, targetFqn);
            if (!likelyRef) {
                debug("[DEBUG] getMethodsCallingImpl: no tokens, no reference — skipping. target=" + targetSimpleName);
                return Collections.emptyList();
            }
            String reason = confirmedDependent ? "confirmed dependent" : "structural fallback enabled";
            debug("[DEBUG] getMethodsCallingImpl: no touched tokens, " + reason + ". Running AST/structural scan. target=" + targetSimpleName);
            List<String> structural = getMethodsUsingTarget(content, targetSimpleName, targetFqn, supertypeSimpleNames);
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

        // 4. Qualified calls: instanceName.method(...)
        if (instanceNames != null && !instanceNames.isEmpty()) {
            debug("[DEBUG] getMethodsCalling: checking qualified calls with instanceNames=" + instanceNames + ", touchedMethods=" + touchedMethods + ", allowBroadFallback=" + allowBroadFallback);
            for (String instanceName : instanceNames) {
                if (instanceName == null || instanceName.isBlank()) continue;
                for (String method : touchedMethods) {
                    String pureMethodName = method == null ? "" : method.split("\\(")[0].trim();
                    if (!isValidMethodName(pureMethodName)) {
                        continue;
                    }
                    String callPattern = "\\b" + Pattern.quote(instanceName) + "\\s*\\.\\s*" + Pattern.quote(pureMethodName) + "\\b\\s*\\(";
                    Pattern cp = Pattern.compile(callPattern);
                    Matcher cm = cp.matcher(content);
                    int matchCount = 0;
                    while (cm.find()) {
                        matchCount++;
                        int callPos = cm.start();
                        String enclosing = findEnclosingMethod(methodsInFile, callPos);
                        debug("[DEBUG] getMethodsCalling: match at position " + callPos + ", enclosing method: " + (enclosing == null ? "NULL" : enclosing));
                        if (enclosing != null) callers.add(enclosing);
                    }
                    debug("[DEBUG] getMethodsCalling: pattern '" + callPattern + "' found " + matchCount + " matches");

                    // Method reference: instanceName::method (streams/optionals)
                    String refPattern = "\\b" + Pattern.quote(instanceName) + "\\s*::\\s*" + Pattern.quote(pureMethodName) + "\\b";
                    Pattern rp = Pattern.compile(refPattern);
                    Matcher rm = rp.matcher(content);
                    while (rm.find()) {
                        int callPos = rm.start();
                        String enclosing = findEnclosingMethod(methodsInFile, callPos);
                        if (enclosing != null) callers.add(enclosing);
                    }
                }
            }
        }

        // 4a. Broad fallback: anyQualifier.method(...)
        // This is intentionally looser than 4b. It helps when instance name extraction fails
        // (e.g., injection patterns, interface/supertype fields) but the method token exists.
        if (allowBroadFallback && callers.isEmpty()) {
            for (String method : touchedMethods) {
                String pureMethodName = method == null ? "" : method.split("\\(")[0].trim();
                if (!isValidMethodName(pureMethodName)) {
                    continue;
                }
                String anyQualifierCallPattern = "\\b\\w+\\s*\\.\\s*" + Pattern.quote(pureMethodName) + "\\b\\s*\\(";
                Matcher aqm = Pattern.compile(anyQualifierCallPattern).matcher(content);
                while (aqm.find()) {
                    int callPos = aqm.start();
                    String enclosing = findEnclosingMethod(methodsInFile, callPos);
                    if (enclosing != null) callers.add(enclosing);
                }

                String anyQualifierRefPattern = "\\b\\w+\\s*::\\s*" + Pattern.quote(pureMethodName) + "\\b";
                Matcher arm = Pattern.compile(anyQualifierRefPattern).matcher(content);
                while (arm.find()) {
                    int callPos = arm.start();
                    String enclosing = findEnclosingMethod(methodsInFile, callPos);
                    if (enclosing != null) callers.add(enclosing);
                }
            }
        }

        // 4b. Fallback qualified calls: *.method(...)
        // This helps when the target is referenced via an interface/supertype (instance name extraction may fail),
        // or when the call appears inside lambdas/streams but still uses dot-call syntax.
        // Also run when hasAnyTouchedToken=true: even if the type isn't directly referenced in the file,
        // if the file contains calls to the touched methods, they're likely inherited from a parent class
        // and we should still try to find which methods in this file make those calls.
        boolean likelyRef = isLikelyTargetReferencedInFile(content, targetSimpleName, targetFqn);
        if (callers.isEmpty() && (likelyRef || hasAnyTouchedToken)) {
            debug("[DEBUG] step 4b: running (likelyRef=" + likelyRef + ", hasAnyTouchedToken=" + hasAnyTouchedToken + ") for target=" + targetSimpleName);
            for (String method : touchedMethods) {
                String pureMethodName = method == null ? "" : method.split("\\(")[0].trim();
                if (!isValidMethodName(pureMethodName)) {
                    continue;
                }
                String anyQualifierCallPattern = "\\b(\\w+)\\s*\\.\\s*" + Pattern.quote(pureMethodName) + "\\b\\s*\\(";
                Matcher aqm = Pattern.compile(anyQualifierCallPattern).matcher(content);
                int matchCount = 0;
                while (aqm.find()) {
                    matchCount++;
                    String qualifier = aqm.group(1);
                    if (!isPlausibleQualifierIdentifier(content, qualifier, targetSimpleName)) {
                        debug("[DEBUG] step 4b: skipping qualifier '" + qualifier + "' (not plausible)");
                        continue;
                    }
                    int callPos = aqm.start();
                    String enclosing = findEnclosingMethod(methodsInFile, callPos);
                    debug("[DEBUG] step 4b: found " + qualifier + "." + pureMethodName + " at position " + callPos + ", enclosing method: " + enclosing);
                    if (enclosing != null) callers.add(enclosing);
                }
                if (matchCount > 0) {
                    debug("[DEBUG] step 4b: pattern '" + anyQualifierCallPattern + "' found " + matchCount + " matches for method " + pureMethodName);
                }

                String anyQualifierRefPattern = "\\b(\\w+)\\s*::\\s*" + Pattern.quote(pureMethodName) + "\\b";
                Matcher arm = Pattern.compile(anyQualifierRefPattern).matcher(content);
                while (arm.find()) {
                    String qualifier = arm.group(1);
                    if (!isPlausibleQualifierIdentifier(content, qualifier, targetSimpleName)) {
                        continue;
                    }
                    int callPos = arm.start();
                    String enclosing = findEnclosingMethod(methodsInFile, callPos);
                    if (enclosing != null) callers.add(enclosing);
                }
            }
            debug("[DEBUG] step 4b: found " + callers.size() + " callers for target " + targetSimpleName);
        }

        // 4c. Raw-token fallback: handles chained calls (factory.get().method(...)), lambdas, and
        // any other case where steps 1-4b failed to attribute a known touched-method call.
        // Only runs when we KNOW the method token is present in the file but all qualifier-based
        // steps came up empty.  findEnclosingMethod returns null for method *declarations*, so
        // we don't accidentally treat the declaring class as a caller of itself.
        if (callers.isEmpty() && hasAnyTouchedToken) {
            debug("[DEBUG] step 4c: raw-token fallback for target=" + targetSimpleName);
            for (String method : touchedMethods) {
                String pureMethodName = method == null ? "" : method.split("\\(")[0].trim();
                if (!isValidMethodName(pureMethodName)) continue;
                Pattern rawCall = Pattern.compile("(?<![\\w\\$])" + Pattern.quote(pureMethodName) + "\\s*\\(");
                Matcher rm = rawCall.matcher(content);
                while (rm.find()) {
                    int callPos = rm.start();
                    String enclosing = findEnclosingMethod(methodsInFile, callPos);
                    // Skip if the enclosing method IS the touched method itself
                    // (would mean we're looking at its own declaration/recursive call context)
                    if (enclosing != null && !enclosing.equals(pureMethodName)) {
                        debug("[DEBUG] step 4c: raw occurrence of '" + pureMethodName + "' at " + callPos + " enclosed by " + enclosing);
                        callers.add(enclosing);
                    }
                }
            }
            debug("[DEBUG] step 4c: found " + callers.size() + " callers");
        }

        // 6. Structural fallback — only runs when explicitly enabled via config flag
        // (transitive.caller.structural.fallback=true).  Off by default because it
        // emits edges for ANY call on the target type, not just the touched methods,
        // which can pull unrelated execution paths into the BFS and surface false-
        // positive endpoints.
        //
        // When enabled, the AST-based scanner (AstInvocationFinder) is preferred:
        // it never matches inside strings/comments, handles chains/lambdas precisely,
        // and attributes lambda-body calls to the correct enclosing named method.
        // The regex scan is used only if the AST library is not on the classpath.
        if (callers.isEmpty() && structuralFallbackEnabled && !instanceNames.isEmpty()) {
            debug("[DEBUG] step 6: structural fallback enabled, running for target=" + targetSimpleName);
            if (astCallerDetectionEnabled && AstInvocationFinder.isAvailable()) {
                // AST path: precise, no string/comment false-positives
                List<String> astCallers = AstInvocationFinder.findCallerMethods(
                        content, targetSimpleName, targetFqn,
                        supertypeSimpleNames != null ? supertypeSimpleNames : Collections.emptyList(),
                        touchedMethods);
                callers.addAll(astCallers);
                debug("[DEBUG] step 6 AST: found " + astCallers.size() + " callers: " + astCallers);
            } else {
                // Regex fallback when JavaParser is unavailable
                for (String inst : instanceNames) {
                    if (inst == null || inst.isBlank()) continue;
                    Pattern dotCall = Pattern.compile("\\b" + Pattern.quote(inst) + "\\s*\\.\\s*(\\w+)\\s*\\(");
                    Matcher dc = dotCall.matcher(content);
                    while (dc.find()) {
                        String enclosing = findEnclosingMethod(methodsInFile, dc.start());
                        if (enclosing != null) {
                            debug("[DEBUG] step 6 regex: " + inst + "." + dc.group(1) + "() in " + enclosing);
                            callers.add(enclosing);
                        }
                    }
                    Pattern ref = Pattern.compile("\\b" + Pattern.quote(inst) + "\\s*::\\s*(\\w+)\\b");
                    Matcher rm2 = ref.matcher(content);
                    while (rm2.find()) {
                        String enclosing = findEnclosingMethod(methodsInFile, rm2.start());
                        if (enclosing != null) callers.add(enclosing);
                    }
                }
                debug("[DEBUG] step 6 regex: found " + callers.size() + " callers");
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
        for (MethodSpan s : spans) {
            debug("[DEBUG] findEnclosingMethod: span " + s.name + " [" + s.start + ", " + s.endExclusive + ")");
            if (pos >= s.start && pos < s.endExclusive) {
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
     * Parses {@code content} to find every method declaration's span [headerStart, bodyEnd).
     * Used by both token-based and structural call-site finders to locate enclosing methods.
     */
    static List<MethodSpan> buildMethodSpans(String content) {
        List<MethodSpan> spans = new ArrayList<>();
        if (content == null || content.isBlank()) return spans;
        Pattern methodDeclPattern = Pattern.compile(
            "(?s)(?:public|protected|private|static|final|\\s)+\\s*(?:<[^>]+>\\s+)?(?:[\\w\\<\\>\\[\\]\\.]+)\\s+(\\w+)\\s*\\(");
        Matcher mm = methodDeclPattern.matcher(content);
        while (mm.find()) {
            String name = mm.group(1);
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
     * Structural caller discovery: finds every method in {@code content} that makes
     * <em>any</em> method call on a field or variable typed as the target class or one
     * of its supertypes (interfaces/superclass).  Unlike {@link #getMethodsCallingImpl},
     * this does NOT require knowing which specific methods were touched — it returns
     * every method that interacts with the target type at all.
     *
     * <p>This is the primary mechanism when string-token matching fails (chained calls
     * like {@code factory.get().method()}, lambda captures, interface proxies, Spring
     * delegates, etc.) because the touched-method name simply does not appear literally
     * in the dependent file's source.
     *
     * @return distinct names of methods whose bodies contain at least one
     *         {@code instance.anyMethod(} or {@code instance::anyMethod} expression
     *         on an instance of the target type
     */
    public static List<String> getMethodsUsingTarget(
            String content, String targetSimpleName, String targetFqn,
            List<String> supertypeSimpleNames) {
        if (content == null || content.isBlank()) return Collections.emptyList();
        Set<String> instanceNames = extractInstanceNames(content, targetSimpleName, targetFqn, supertypeSimpleNames);
        // Also include the class name itself for static call sites (TargetClass.staticMethod(...))
        if (targetSimpleName != null && !targetSimpleName.isBlank()) instanceNames.add(targetSimpleName.trim());
        if (instanceNames.isEmpty()) return Collections.emptyList();

        List<MethodSpan> spans = buildMethodSpans(content);
        Set<String> callers = new LinkedHashSet<>();

        for (String inst : instanceNames) {
            if (inst == null || inst.isBlank()) continue;
            // Dot-call: instance.anyMethod(
            Pattern dotCall = Pattern.compile("\\b" + Pattern.quote(inst) + "\\s*\\.\\s*(\\w+)\\s*\\(");
            Matcher dc = dotCall.matcher(content);
            while (dc.find()) {
                String enclosing = findEnclosingMethod(spans, dc.start());
                if (enclosing != null) {
                    debug("[DEBUG] getMethodsUsingTarget: " + inst + "." + dc.group(1) + "() in method " + enclosing);
                    callers.add(enclosing);
                }
            }
            // Method reference: instance::anyMethod
            Pattern ref = Pattern.compile("\\b" + Pattern.quote(inst) + "\\s*::\\s*(\\w+)\\b");
            Matcher rm = ref.matcher(content);
            while (rm.find()) {
                String enclosing = findEnclosingMethod(spans, rm.start());
                if (enclosing != null) callers.add(enclosing);
            }
        }
        debug("[DEBUG] getMethodsUsingTarget: target=" + targetSimpleName
                + " instances=" + instanceNames + " callers=" + callers);
        return new ArrayList<>(callers);
    }

    private static Set<String> extractInstanceNames(String content, String targetSimpleName, String targetFqn,
                                                     List<String> supertypeSimpleNames) {
        Set<String> instanceNames = new HashSet<>();
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
        for (int classLineIdx = 0; classLineIdx < Math.min(lines.length, 50); classLineIdx++) {
            Matcher cm = classMapping.matcher(lines[classLineIdx]);
            if (cm.find()) {
                classPrefix = cm.group(1);
                break;
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

        return true;
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
}
