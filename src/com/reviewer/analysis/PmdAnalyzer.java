package com.reviewer.analysis;

import com.reviewer.model.Models.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.concurrent.TimeUnit;

public class PmdAnalyzer {

    public static List<Finding> analyze(List<ChangedFile> files, Config config) {
        List<Finding> allFindings = new ArrayList<>();
        if (config == null || !config.enablePmdAnalysis) return allFindings;

        try {
            // Create a temporary file list for PMD
            Path tempFileList = Files.createTempFile("pmd-files", ".txt");
            try {
                List<String> filePaths = files.stream()
                    .map(f -> {
                        try {
                            return Paths.get(f.path).toAbsolutePath().normalize().toString();
                        } catch (Exception e) {
                            return f.path;
                        }
                    })
                    .collect(java.util.stream.Collectors.toList());
                Files.write(tempFileList, filePaths);

                // Execute PMD CLI
                // Command: pmd check -f json -R <ruleset> -filelist <tempFile>
                ProcessBuilder pb = new ProcessBuilder(
                        config.pmdPath,
                        "check",
                        "-f", "json",
                        "-R", config.pmdRulesetPath,
                        "-filelist", tempFileList.toString()
                );

                pb.redirectErrorStream(true);

                Process process = pb.start();
                try {
                    StringBuilder output = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            output.append(line);
                        }
                    }

                    boolean finished = process.waitFor(60, TimeUnit.SECONDS);
                    // PMD exit codes: 0 = no violations, 4 = violations found, others = error
                    if (!finished) {
                        System.err.println("PMD analysis timed out after 60 seconds");
                        return allFindings;
                    }

                    if (config.debug) {
                        System.out.println("[DEBUG] PMD exit code: " + process.exitValue());
                    }

                    allFindings.addAll(parsePmdJson(output.toString(), files, config));
                    if (config.debug) {
                        System.out.println("[DEBUG] PMD findings parsed: " + allFindings.size());
                    }
                } finally {
                    process.destroyForcibly();
                }
            } finally {
                Files.deleteIfExists(tempFileList);
            }

        } catch (Exception e) {
            System.err.println("PMD integration failed: " + e.getMessage());
            // Fallback: Return empty findings, existing RuleEngine will still run
        }

        return allFindings;
    }

    private static List<Finding> parsePmdJson(String json, List<ChangedFile> changedFiles, Config config) {
        List<Finding> findings = new ArrayList<>();
        // Note: Using simple regex parsing for JSON to avoid adding a heavy JSON library dependency
        // In a production app, a real JSON parser (Jackson/Gson) would be preferred.

        try {
            // Pattern to extract file results (PMD 7 JSON includes extra keys besides filename/violations)
            Pattern filePattern = Pattern.compile(
                "\\{[^{}]*?\\\"filename\\\"\\s*:\\s*\\\"(.*?)\\\"[^{}]*?\\\"violations\\\"\\s*:\\s*\\[(.*?)\\]" +
                "[^{}]*?\\}",
                Pattern.DOTALL);
            Matcher fileMatcher = filePattern.matcher(json);

            Map<String, ChangedFile> fileMap = new HashMap<>();
            for (ChangedFile f : changedFiles) {
                // Normalize path for matching
                String abs;
                try {
                    abs = Paths.get(f.path).toAbsolutePath().normalize().toString();
                } catch (Exception e) {
                    abs = new File(f.path).getAbsolutePath();
                }
                fileMap.put(normalizePath(abs), f);
            }

            while (fileMatcher.find()) {
                String filePath = fileMatcher.group(1).replace("\\\\", "\\");
                String violationsJson = fileMatcher.group(2);

                ChangedFile changedFile = fileMap.get(normalizePath(filePath));
                if (changedFile == null) continue;

                // Extract individual violations
                Pattern violationPattern = Pattern.compile(
                    "\\{[^{}]*?\\\"beginLine\\\"\\s*:\\s*(\\d+)" +
                    "[^{}]*?\\\"rule\\\"\\s*:\\s*\\\"(.*?)\\\"" +
                    "[^{}]*?\\\"ruleset\\\"\\s*:\\s*\\\"(.*?)\\\"" +
                    "[^{}]*?\\\"priority\\\"\\s*:\\s*(\\d+)" +
                    "[^{}]*?\\\"description\\\"\\s*:\\s*\\\"(.*?)\\\"" +
                    "[^{}]*?\\}",
                    Pattern.DOTALL);
                Matcher vMatcher = violationPattern.matcher(violationsJson);

                boolean fileHadFindings = false;
                while (vMatcher.find()) {
                    int line = Integer.parseInt(vMatcher.group(1));

                    // Filter to only changed lines if configured
                    if (config != null && config.onlyChangedLines && !isInChangedLines(changedFile, line)) continue;

                    String ruleName = vMatcher.group(2);
                    String ruleset = vMatcher.group(3);
                    int priority = Integer.parseInt(vMatcher.group(4));
                    String description = unescapeJsonString(vMatcher.group(5));

                    Severity severity = mapPriority(priority);
                    Category category = mapCategory(ruleset, ruleName);

                    // Get the actual code line
                    String code = "";
                    try {
                        List<String> allLines = Files.readAllLines(Paths.get(filePath));
                        if (line <= allLines.size()) {
                            code = allLines.get(line - 1).trim();
                        }
                    } catch (IOException ignored) {}

                    findings.add(new Finding(
                        severity,
                        category,
                        changedFile.name,
                        line,
                        code,
                        "PMD: " + ruleName,
                        description,
                        "Refer to PMD documentation for '" + ruleName + "' best practices."
                    ));
                    fileHadFindings = true;
                }
                // Mark this file as PMD-covered so RuleEngine skips overlapping rule groups
                if (fileHadFindings && config != null) {
                    config.pmdCoveredFiles.add(changedFile.name);
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing PMD JSON: " + e.getMessage());
        }

        return findings;
    }

    private static boolean isInChangedLines(ChangedFile file, int line) {
        if (file.changedLines == null || file.changedLines.isEmpty()) {
            return true;
        }
        return file.changedLines.contains(line);
    }

    private static String normalizePath(String path) {
        if (path == null) return null;
        try {
            return Paths.get(path).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return new File(path).getAbsolutePath();
        }
    }

    private static String unescapeJsonString(String s) {
        if (s == null) return "";
        return s
            .replace("\\\\\"", "\"")
            .replace("\\\\n", "\n")
            .replace("\\\\r", "\r")
            .replace("\\\\t", "\t")
            .replace("\\\\/", "/")
            .replace("\\\\\\\\", "\\");
    }

    private static Severity mapPriority(int priority) {
        switch (priority) {
            case 1: return Severity.MUST_FIX;
            case 2: return Severity.SHOULD_FIX;
            case 3:
            default: return Severity.CONSIDER;
        }
    }

    private static Category mapCategory(String ruleset, String rule) {
        String lowerRuleset = ruleset.toLowerCase();
        if (lowerRuleset.contains("errorprone") || lowerRuleset.contains("multithreading")) return Category.CODE_QUALITY;
        if (lowerRuleset.contains("bestpractices") || lowerRuleset.contains("codestyle")) return Category.CODE_QUALITY;
        if (lowerRuleset.contains("design")) return Category.CODE_QUALITY;
        if (lowerRuleset.contains("performance")) return Category.PERFORMANCE;
        if (lowerRuleset.contains("security")) return Category.CODE_QUALITY; // Could add SECURITY category if needed

        // Match specific common rules to your categories
        if (rule.contains("Null")) return Category.NULL_SAFETY;
        if (rule.contains("Catch") || rule.contains("Exception") || rule.contains("Throwable")) return Category.EXCEPTION_HANDLING;
        if (rule.contains("Log") || rule.contains("SystemPrintln")) return Category.LOGGING;

        return Category.CODE_QUALITY;
    }
}
