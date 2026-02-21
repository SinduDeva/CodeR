package com.reviewer.analysis;

import com.reviewer.model.Models.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import java.nio.file.*;
import java.io.*;

public class TreeSitterAnalyzer {
    private static boolean isLibLoaded = true;
    public static boolean isAvailable() { return isLibLoaded; }

    public static List<String> extractTouchedMethods(ChangedFile file, String content) {
        if (!isAvailable()) return null;
        List<String> touchedMethods = new ArrayList<>();
        String[] lines = content.split("\\R");
        List<MethodRange> methodRanges = findMethodRanges(lines);

        Set<Integer> changedLinesSet = new HashSet<>(file.changedLines);
        for (MethodRange range : methodRanges) {
            for (int cl : changedLinesSet) {
                if (cl >= range.startLine && cl <= range.endLine) {
                    touchedMethods.add(range.methodName);
                    break;
                }
            }
        }
        return touchedMethods;
    }

    public static Map<String, Set<String>> buildReverseGraph(List<ChangedFile> changedFiles) {
        if (!isAvailable()) return null;
        Map<String, Set<String>> graph = new HashMap<>();
        Set<String> targetClasses = changedFiles.stream()
                .map(f -> f.name.replace(".java", ""))
                .collect(Collectors.toSet());

        try (Stream<Path> paths = Files.walk(Paths.get("").toAbsolutePath())) {
            paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/test/"))
                    .forEach(p -> {
                        try {
                            String content = Files.readString(p);
                            String fileName = p.getFileName().toString().replace(".java", "");
                            for (String target : targetClasses) {
                                if (fileName.equals(target)) continue;

                                // Enhanced usage check: handles generic types and inner class paths
                                if (content.contains("import " + target) ||
                                        content.matches("(?s).*\\b" + target + "\\b.*")) {
                                    graph.computeIfAbsent(target, k -> new HashSet<>()).add(p.toString());
                                }
                            }
                        } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
        return graph;
    }

    public static List<String> extractImpactedEndpoints(String content, String className, List<String> touchedMethods) {
        if (!isAvailable()) return null;
        List<String> endpoints = new ArrayList<>();
        String[] lines = content.split("\\R");

        // 1. Detect Class-Level Mapping
        String classPrefix = "";
        Pattern mappingStart = Pattern.compile("@(Request|Get|Post|Put|Delete|Patch)Mapping\\b");
        Pattern pathPattern = Pattern.compile("(?:value|path)\\s*=\\s*\"([^\"]+)\"|\"([^\"]+)\"");
        Pattern classMappingStart = Pattern.compile("@(?:RequestMapping|PostMapping|GetMapping|PutMapping|DeleteMapping|PatchMapping)\\s*\\((?:(?:value|path)\\s*=\\s*)?\"([^\"]+)\"");
        for (int i = 0; i < Math.min(lines.length, 80); i++) {
            Matcher cm = classMappingStart.matcher(lines[i]);
            if (cm.find()) {
                classPrefix = cm.group(1);
                break;
            }
            if (mappingStart.matcher(lines[i]).find()) {
                StringBuilder annoBuf = new StringBuilder(lines[i]).append(' ');
                int j = i + 1;
                while (j < Math.min(lines.length, 80) && !lines[j].contains(")") && !lines[j].contains("class ") && !lines[j].contains("{") && (j - i) < 30) {
                    annoBuf.append(lines[j]).append(' ');
                    j++;
                }
                if (j < Math.min(lines.length, 80)) {
                    annoBuf.append(lines[j]).append(' ');
                }
                Matcher pm = pathPattern.matcher(annoBuf.toString());
                if (pm.find()) {
                    classPrefix = pm.group(1) != null ? pm.group(1) : pm.group(2);
                    break;
                }
            }
            if (lines[i].contains("class " + className)) break;
        }

        List<MethodRange> methods = findMethodRanges(lines);
        Pattern methodMapping = Pattern.compile("@(Request|Get|Post|Put|Delete|Patch)Mapping\\b");

        for (MethodRange method : methods) {
            if (!touchedMethods.contains(method.methodName)) {
                continue;
            }

            int methodLineIdx = Math.max(0, Math.min(lines.length - 1, method.headerLine - 1));
            int annoStart = methodLineIdx - 1;
            while (annoStart >= 0) {
                String l = lines[annoStart].trim();
                if (l.isEmpty()) {
                    annoStart--;
                    continue;
                }
                if (l.startsWith("@")) {
                    annoStart--;
                    continue;
                }
                break;
            }
            annoStart = Math.min(methodLineIdx - 1, annoStart + 1);

            for (int i = Math.max(0, annoStart); i < methodLineIdx; i++) {
                Matcher mm = methodMapping.matcher(lines[i]);
                if (!mm.find()) continue;
                String annotationType = mm.group(1);

                StringBuilder annoBuf = new StringBuilder(lines[i]).append(' ');
                int j = i + 1;
                while (j < methodLineIdx && !lines[j].contains(")") && (j - i) < 30) {
                    annoBuf.append(lines[j]).append(' ');
                    j++;
                }
                if (j < methodLineIdx) {
                    annoBuf.append(lines[j]).append(' ');
                }

                String methodPath = "";
                Matcher pm = pathPattern.matcher(annoBuf.toString());
                if (pm.find()) {
                    methodPath = pm.group(1) != null ? pm.group(1) : pm.group(2);
                }
                String httpVerb = verbFromAnnotationType(annotationType, annoBuf.toString());
                String fullPath = (classPrefix + "/" + methodPath).replaceAll("//+", "/");
                endpoints.add(className + "." + method.methodName + " [" + httpVerb + " " + fullPath + "]");
            }
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

    private static List<MethodRange> findMethodRanges(String[] lines) {
        List<MethodRange> ranges = new ArrayList<>();
        // Improved regex: handles annotations, multi-line signatures, and filters control keywords
        String regex = "(?s)(?<!\\b(?:if|for|while|switch|catch|synchronized|static)\\s)(?:@[\\w\\.]+(?:\\([^)]*\\))?\\s+)*(?:public|protected|private|static|final|\\s)+\\s*(?:<[^>]+>\\s+)?(?:[\\w\\<\\>\\[\\]\\.]+)\\s+(\\w+)\\s*\\([^\\)]*\\)\\s*(?:throws [\\w\\.,\\s<>]+)?\\s*\\{";
        Pattern p = Pattern.compile(regex);

        StringBuilder content = new StringBuilder();
        for (String line : lines) content.append(line).append("\n");
        Matcher m = p.matcher(content.toString());

        while (m.find()) {
            String methodName = m.group(1);
            int nameLine = (int) content.substring(0, m.start(1)).chars().filter(c -> c == '\n').count() + 1;
            int braceIndex = m.group().indexOf('{');
            int braceLine = (int) content.substring(0, m.start() + braceIndex).chars().filter(c -> c == '\n').count() + 1;
            int endLine = findClosingBrace(lines, braceLine - 1);
            if (endLine != -1 && endLine >= braceLine) {
                ranges.add(new MethodRange(methodName, nameLine, endLine, nameLine));
            }
        }
        return ranges;
    }

    private static int findClosingBrace(String[] lines, int startIdx) {
        int depth = 0;
        boolean foundStart = false;
        boolean inBlockComment = false;
        boolean inStringLiteral = false;
        boolean inCharLiteral = false;

        for (int i = startIdx; i < lines.length; i++) {
            String line = lines[i];
            char[] chars = line.toCharArray();
            boolean inLineComment = false;

            for (int c = 0; c < chars.length; ) {
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
                    if (foundStart && depth == 0) {
                        return i + 1;
                    }
                }
                c++;
            }
        }
        return -1;
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

    private static class MethodRange {
        String methodName; int startLine; int endLine; int headerLine;
        MethodRange(String n, int s, int e, int h) { this.methodName = n; this.startLine = s; this.endLine = e; this.headerLine = h; }
    }
}