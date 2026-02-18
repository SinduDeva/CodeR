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
        if (touchedMethods.isEmpty()) return Collections.emptyList();
        Set<String> callers = new HashSet<>();

        // 1. Identify ALL possible instance names (variables, constructor params, fields)
        Set<String> instanceNames = extractInstanceNames(content, targetSimpleName, targetFqn);

        if (instanceNames.isEmpty()) return Collections.emptyList();

        // 2. Identify all method boundaries in the current file accurately
        TreeMap<Integer, String> methodsInFile = new TreeMap<>();
        // Robust regex for Java method declarations
        Pattern methodDeclPattern = Pattern.compile("(?:public|protected|private|static|final|\\s)+\\s*(?:<[^>]+>\\s+)?(?:[\\w\\<\\>\\[\\]\\.]+)\\s+(\\w+)\\s*\\([^\\)]*\\)\\s*(?:throws [\\w\\.,\\s<>]+)?\\s*\\{");
        Matcher mm = methodDeclPattern.matcher(content);
        while (mm.find()) {
            methodsInFile.put(mm.start(), mm.group(1));
        }

        // 3. Scan for every call site of every touched method using every instance name
        for (String instanceName : instanceNames) {
            for (String method : touchedMethods) {
                // Handle method name cleanup (remove params if present)
                String pureMethodName = method.split("\\(")[0].trim();
                if (!isValidMethodName(pureMethodName)) {
                    continue;
                }
                String callPattern = "\\b" + Pattern.quote(instanceName) + "\\." + Pattern.quote(pureMethodName) + "\\b\\s*\\(";
                Pattern cp = Pattern.compile(callPattern);
                Matcher cm = cp.matcher(content);

                while (cm.find()) {
                    Map.Entry<Integer, String> callerEntry = methodsInFile.floorEntry(cm.start());
                    if (callerEntry != null) {
                        callers.add(callerEntry.getValue());
                    }
                }
            }
        }
        return new ArrayList<>(callers);
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
        }
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
        Pattern classMapping = Pattern.compile("@(?:RequestMapping|PostMapping|GetMapping|PutMapping|DeleteMapping|PatchMapping)\\s*\\((?:value\\s*=\\s*)?\"([^\"]+)\"");
        for (int i = 0; i < Math.min(lines.length, 50); i++) {
            Matcher cm = classMapping.matcher(lines[i]);
            if (cm.find()) {
                classPrefix = cm.group(1);
                break;
            }
            if (lines[i].contains("class " + className)) break;
        }

        Pattern methodMapping = Pattern.compile("@(?:RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)\\b[^\\n]*");
        Pattern pathPattern = Pattern.compile("(?:value|path)\\s*=\\s*\"([^\"]+)\"|\"([^\"]+)\"");

        for (String rawMethod : touchedMethods) {
            String methodName = rawMethod.split("\\(")[0].trim();
            if (!isValidMethodName(methodName)) continue;

            Pattern decl = Pattern.compile("\\b" + Pattern.quote(methodName) + "\\s*\\(");
            Matcher dm = decl.matcher(content);
            if (!dm.find()) continue;

            int methodLine = getLineNumber(content, dm.start());
            StringBuilder headerBuffer = new StringBuilder();
            for (int i = Math.max(0, methodLine - 11); i < methodLine - 1 && i < lines.length; i++) {
                headerBuffer.append(lines[i]).append(' ');
            }

            Matcher mm = methodMapping.matcher(headerBuffer.toString());
            while (mm.find()) {
                String annotation = mm.group();
                Matcher pm = pathPattern.matcher(annotation);
                String methodPath = "";
                if (pm.find()) {
                    methodPath = pm.group(1) != null ? pm.group(1) : pm.group(2);
                }
                String fullPath = (classPrefix + "/" + methodPath).replaceAll("//+", "/");
                endpoints.add(className + "." + methodName + " [" + fullPath + "]");
            }
        }

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
            while (j < lines.length && !lines[j].contains(")") && !lines[j].contains("class ") && !lines[j].contains("{") && (j - i) < 10) {
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
