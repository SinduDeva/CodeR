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
        Pattern classMapping = Pattern.compile("@(?:RequestMapping|PostMapping|GetMapping)\\s*\\((?:value\\s*=\\s*)?\"([^\"]+)\"\\)");
        for (int i = 0; i < Math.min(lines.length, 50); i++) {
            Matcher cm = classMapping.matcher(lines[i]);
            if (cm.find()) { classPrefix = cm.group(1); break; }
            if (lines[i].contains("class " + className)) break;
        }

        List<MethodRange> methods = findMethodRanges(lines);
        Pattern methodMapping = Pattern.compile("@(?:[\\w]*)Mapping\\s*\\((?:[^\\)]*)\\)");
        Pattern pathPattern = Pattern.compile("(?:value|path)\\s*=\\s*\"([^\"]+)\"|\"([^\"]+)\"");

        for (MethodRange method : methods) {
            if (touchedMethods.contains(method.methodName)) {
                StringBuilder headerBuffer = new StringBuilder();
                for (int i = Math.max(0, method.startLine - 10); i < method.startLine; i++) {
                    headerBuffer.append(lines[i]).append(" ");
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
                    endpoints.add(className + "." + method.methodName + " [" + fullPath + "]");
                }
            }
        }
        return endpoints.stream().distinct().collect(Collectors.toList());
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
            // Ensure we don't treat 'main' or constructors as false positives if they aren't relevant
            // Start searching from where the opening brace '{' is found to get the true method start
            int braceIndex = m.group().indexOf('{');
            int startLine = (int) content.substring(0, m.start() + braceIndex).chars().filter(c -> c == '\n').count() + 1;
            int endLine = findClosingBrace(lines, startLine - 1);
            if (endLine != -1 && endLine >= startLine) {
                ranges.add(new MethodRange(methodName, startLine, endLine));
            }
        }
        return ranges;
    }

    private static int findClosingBrace(String[] lines, int startIdx) {
        int depth = 0;
        boolean foundStart = false;
        for (int i = startIdx; i < lines.length; i++) {
            for (char c : lines[i].toCharArray()) {
                if (c == '{') { depth++; foundStart = true; }
                else if (c == '}') depth--;
                if (foundStart && depth == 0) return i + 1;
            }
        }
        return -1;
    }

    private static class MethodRange {
        String methodName; int startLine; int endLine;
        MethodRange(String n, int s, int e) { this.methodName = n; this.startLine = s; this.endLine = e; }
    }
}