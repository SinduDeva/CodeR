package com.reviewer.analysis;

import com.reviewer.model.Models.*;
import java.util.regex.*;
import java.util.*;
import java.util.stream.Collectors;

public class ImpactAnalyzer {
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
            System.out.println("[DEBUG] ImpactAnalyzer found method: " + methodName +
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

    public static List<String> getMethodsCalling(String content, String targetSimpleName, String targetFqn, List<String> touchedMethods, boolean allowBroadFallback) {
        if (content == null || content.isBlank() || touchedMethods == null || touchedMethods.isEmpty()) {
            return Collections.emptyList();
        }

        boolean hasAnyTouchedToken = false;
        for (String method : touchedMethods) {
            String pureMethodName = method == null ? "" : method.split("\\(")[0].trim();
            if (!isValidMethodName(pureMethodName)) {
                continue;
            }
            if (content.contains(pureMethodName + "(") ||
                content.contains("." + pureMethodName + "(") ||
                content.contains("::" + pureMethodName)) {
                hasAnyTouchedToken = true;
                break;
            }
        }
        if (!hasAnyTouchedToken) {
            return Collections.emptyList();
        }

        Set<String> callers = new HashSet<>();

        // 1. Identify ALL possible instance names (variables, constructor params, fields)
        Set<String> instanceNames = extractInstanceNames(content, targetSimpleName, targetFqn);

        // Also consider static calls like TargetClass.someMethod(...)
        if (targetSimpleName != null && !targetSimpleName.isBlank()) {
            instanceNames.add(targetSimpleName.trim());
        }

        // 2. Identify all method boundaries in the current file accurately
        List<MethodSpan> methodsInFile = new ArrayList<>();
        Pattern methodDeclPattern = Pattern.compile("(?s)(?:public|protected|private|static|final|\\s)+\\s*(?:<[^>]+>\\s+)?(?:[\\w\\<\\>\\[\\]\\.]+)\\s+(\\w+)\\s*\\(");
        Matcher mm = methodDeclPattern.matcher(content);
        while (mm.find()) {
            String name = mm.group(1);
            int paramStart = mm.end() - 1;
            
            // Find matching closing parenthesis for parameters (handles nested parens in annotations)
            int depth = 1;
            int i = paramStart + 1;
            while (i < content.length() && depth > 0) {
                char c = content.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') depth--;
                i++;
            }
            if (depth != 0) continue; // Unmatched parentheses
            
            // Now find the opening brace
            int bodyStartBrace = -1;
            for (int j = i; j < content.length(); j++) {
                char c = content.charAt(j);
                if (c == '{') {
                    bodyStartBrace = j;
                    break;
                }
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r' && !Character.isJavaIdentifierPart(c) && c != ',' && c != '.') {
                    break; // Not a method declaration
                }
            }
            if (bodyStartBrace < 0) continue;
            
            int bodyEndExclusive = findMatchingBrace(content, bodyStartBrace);
            if (bodyEndExclusive > bodyStartBrace) {
                methodsInFile.add(new MethodSpan(mm.start(), bodyEndExclusive, name));
            }
        }

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
            System.out.println("[DEBUG] getMethodsCalling: checking qualified calls with instanceNames=" + instanceNames + ", touchedMethods=" + touchedMethods + ", allowBroadFallback=" + allowBroadFallback);
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
                        System.out.println("[DEBUG] getMethodsCalling: match at position " + callPos + ", enclosing method: " + (enclosing == null ? "NULL" : enclosing));
                        if (enclosing != null) callers.add(enclosing);
                    }
                    System.out.println("[DEBUG] getMethodsCalling: pattern '" + callPattern + "' found " + matchCount + " matches");

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
        if (callers.isEmpty() && isLikelyTargetReferencedInFile(content, targetSimpleName, targetFqn)) {
            for (String method : touchedMethods) {
                String pureMethodName = method == null ? "" : method.split("\\(")[0].trim();
                if (!isValidMethodName(pureMethodName)) {
                    continue;
                }
                String anyQualifierCallPattern = "\\b(\\w+)\\s*\\.\\s*" + Pattern.quote(pureMethodName) + "\\b\\s*\\(";
                Matcher aqm = Pattern.compile(anyQualifierCallPattern).matcher(content);
                while (aqm.find()) {
                    String qualifier = aqm.group(1);
                    if (!isPlausibleQualifierIdentifier(content, qualifier, targetSimpleName)) {
                        continue;
                    }
                    int callPos = aqm.start();
                    String enclosing = findEnclosingMethod(methodsInFile, callPos);
                    if (enclosing != null) callers.add(enclosing);
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
        }

        // 5. Unqualified calls: method(...) when statically imported from targetFqn
        if (staticImportAll || !staticallyImportedMethods.isEmpty()) {
            for (String method : touchedMethods) {
                String pureMethodName = method == null ? "" : method.split("\\(")[0].trim();
                if (!isValidMethodName(pureMethodName)) {
                    continue;
                }
                if (!staticImportAll && !staticallyImportedMethods.contains(pureMethodName)) {
                    continue;
                }
                String unqualifiedPattern = "(?<![\\w\\.])" + Pattern.quote(pureMethodName) + "\\b\\s*\\(";
                Pattern up = Pattern.compile(unqualifiedPattern);
                Matcher um = up.matcher(content);
                while (um.find()) {
                    int callPos = um.start();
                    String enclosing = findEnclosingMethod(methodsInFile, callPos);
                    if (enclosing != null) callers.add(enclosing);
                }
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
            System.out.println("[DEBUG] findEnclosingMethod: spans is null");
            return null;
        }
        System.out.println("[DEBUG] findEnclosingMethod: checking position " + pos + " against " + spans.size() + " method spans");
        for (MethodSpan s : spans) {
            System.out.println("[DEBUG] findEnclosingMethod: span " + s.name + " [" + s.start + ", " + s.endExclusive + ")");
            if (pos >= s.start && pos < s.endExclusive) {
                System.out.println("[DEBUG] findEnclosingMethod: position " + pos + " is inside " + s.name);
                return s.name;
            }
        }
        System.out.println("[DEBUG] findEnclosingMethod: position " + pos + " not found in any method span");
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

    private static Set<String> extractInstanceNames(String content, String targetSimpleName, String targetFqn) {
        Set<String> instanceNames = new HashSet<>();
        Set<String> typeTokens = new LinkedHashSet<>();
        if (targetSimpleName != null && !targetSimpleName.isBlank()) typeTokens.add(targetSimpleName);
        if (targetFqn != null && !targetFqn.isBlank()) typeTokens.add(targetFqn);

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
        System.out.println("[DEBUG] extractInstanceNames: targetSimpleName=" + targetSimpleName + ", targetFqn=" + targetFqn + ", extracted=" + instanceNames);
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
        System.out.println("[DEBUG] extractControllerEndpoints: className=" + className + ", classPrefix=" + classPrefix + ", touchedMethods=" + touchedMethods);

        Pattern methodMapping = Pattern.compile("@(?:RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)\\b");
        Pattern pathPattern = Pattern.compile("(?:value|path)\\s*=\\s*\"([^\"]+)\"|\"([^\"]+)\"");

        for (String rawMethod : touchedMethods) {
            String methodName = rawMethod.split("\\(")[0].trim();
            if (!isValidMethodName(methodName)) continue;

            Pattern decl = Pattern.compile("(?s)(?:public|protected|private)\\s+[^{;=]+?\\b" + Pattern.quote(methodName) + "\\s*\\(");
            Matcher dm = decl.matcher(content);
            if (!dm.find()) {
                System.out.println("[DEBUG] extractControllerEndpoints: method declaration not found for " + methodName);
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
                System.out.println("[DEBUG] extractControllerEndpoints: unmatched parentheses for " + methodName);
                continue;
            }

            String matchedDecl = dm.group();
            System.out.println("[DEBUG] extractControllerEndpoints: matched declaration snippet: " + matchedDecl.substring(0, Math.min(150, matchedDecl.length())).replaceAll("\\s+", " "));
            
            int publicPos = matchedDecl.indexOf("public");
            if (publicPos == -1) publicPos = matchedDecl.indexOf("protected");
            if (publicPos == -1) publicPos = matchedDecl.indexOf("private");
            int methodLine = getLineNumber(content, dm.start() + publicPos);
            int methodLineIdx = Math.max(0, Math.min(lines.length - 1, methodLine - 1));
            System.out.println("[DEBUG] extractControllerEndpoints: found method " + methodName + " at line " + methodLine + " (idx=" + methodLineIdx + "), match start was at line " + getLineNumber(content, dm.start()) + ", publicPos=" + publicPos);

            int annoStart = methodLineIdx - 1;
            int lastAnnotationLine = -1;
            while (annoStart >= 0 && annoStart >= methodLineIdx - 50) {
                String l = lines[annoStart].trim();
                System.out.println("[DEBUG] extractControllerEndpoints: scanning line " + (annoStart+1) + ": '" + l + "'");
                if (l.isEmpty()) {
                    annoStart--;
                    continue;
                }
                if (l.startsWith("@")) {
                    lastAnnotationLine = annoStart;
                    System.out.println("[DEBUG] extractControllerEndpoints: found annotation at line " + (annoStart+1));
                    annoStart--;
                    continue;
                }
                if (l.contains("=") || l.endsWith(")") || l.endsWith(",")) {
                    System.out.println("[DEBUG] extractControllerEndpoints: treating line " + (annoStart+1) + " as annotation continuation");
                    annoStart--;
                    continue;
                }
                System.out.println("[DEBUG] extractControllerEndpoints: stopping backward scan at line " + (annoStart+1) + " (non-annotation, non-empty)");
                break;
            }
            if (lastAnnotationLine >= 0) {
                annoStart = lastAnnotationLine;
            } else {
                annoStart = Math.max(0, methodLineIdx - 1);
            }
            System.out.println("[DEBUG] extractControllerEndpoints: annotation block range [" + annoStart + ", " + methodLineIdx + "), lastAnnotationLine=" + lastAnnotationLine);

            for (int i = Math.max(0, annoStart); i < methodLineIdx; i++) {
                if (!methodMapping.matcher(lines[i]).find()) continue;
                System.out.println("[DEBUG] extractControllerEndpoints: found mapping annotation at line " + (i+1) + ": " + lines[i]);

                StringBuilder annoBuf = new StringBuilder(lines[i]).append(' ');
                int j = i + 1;
                while (j < methodLineIdx && !lines[j].contains(")") && (j - i) < 30) {
                    annoBuf.append(lines[j]).append(' ');
                    j++;
                }
                if (j < methodLineIdx) {
                    annoBuf.append(lines[j]).append(' ');
                }
                System.out.println("[DEBUG] extractControllerEndpoints: buffered annotation: " + annoBuf.toString().substring(0, Math.min(200, annoBuf.length())));

                String methodPath = "";
                Matcher pm = pathPattern.matcher(annoBuf.toString());
                if (pm.find()) {
                    methodPath = pm.group(1) != null ? pm.group(1) : pm.group(2);
                    System.out.println("[DEBUG] extractControllerEndpoints: extracted path=" + methodPath);
                } else {
                    System.out.println("[DEBUG] extractControllerEndpoints: NO PATH MATCH in buffered annotation");
                }
                String fullPath = (classPrefix + "/" + methodPath).replaceAll("//+", "/");
                endpoints.add(className + "." + methodName + " [" + fullPath + "]");
            }
        }

        System.out.println("[DEBUG] extractControllerEndpoints: final endpoints=" + endpoints);
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

        Pattern mappingStart = Pattern.compile("@(?:RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)\\b");
        Pattern methodSignature = Pattern.compile("(?:public|protected|private)\\s+[^{;]+?\\b(\\w+)\\s*\\([^)]*\\)\\s*(?:throws [^{]+)?\\s*\\{");
        Pattern pathPattern = Pattern.compile("(?:value|path)\\s*=\\s*\"([^\"]+)\"|\"([^\"]+)\"");

        for (int i = 0; i < lines.length; i++) {
            if (!mappingStart.matcher(lines[i]).find()) continue;

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
            endpoints.add(effectiveClassName + "." + methodName + " [" + fullPath + "]");
        }

        return endpoints.stream().distinct().collect(Collectors.toList());
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
