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
            int endLine = findMethodEndLine(lines, startLine);
            System.out.println("[DEBUG] ImpactAnalyzer found method: " + methodName + " at lines " + startLine + "-" + endLine);
            if (endLine == -1) endLine = startLine;

            boolean intersects = file.changedLines.contains(startLine);
            if (!intersects) {
                for (int cl : file.changedLines) {
                    if (cl >= startLine && cl <= endLine) { intersects = true; break; }
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

    private static int findMethodEndLine(String[] lines, int startLine) {
        int depth = 0;
        for (int i = startLine - 1; i < lines.length; i++) {
            String l = lines[i];
            for (int c = 0; c < l.length(); c++) {
                char ch = l.charAt(c);
                if (ch == '{') depth++;
                else if (ch == '}') {
                    depth--;
                    if (depth == 0) return i + 1;
                }
            }
        }
        return -1;
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
