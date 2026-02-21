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
        System.out.println("PMD Analyzer");
        System.out.println("flag"+config.enablePmdAnalysis);
       if (!config.enablePmdAnalysis) return allFindings;

        for (ChangedFile file : files) {
            allFindings.addAll(analyzeSingleFile(file, config));
        }
        return allFindings;
    }

    public static List<Finding> analyzeSingleFile(ChangedFile file, Config config) {
        List<Finding> findings = new ArrayList<>();
        if (!config.enablePmdAnalysis) return findings;

        try {
            // Create a temporary file list for PMD with single file
            Path tempFileList = Files.createTempFile("pmd-files", ".txt");
            Files.write(tempFileList, file.path.getBytes());

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
                process.destroy();
                System.err.println("PMD analysis timed out after 60 seconds for file: " + file.path);
                return findings;
            }

            findings.addAll(parsePmdJson(output.toString(), java.util.Arrays.asList(file)));
            Files.deleteIfExists(tempFileList);

        } catch (Exception e) {
            System.err.println("PMD integration failed for file " + file.path + ": " + e.getMessage());
            // Fallback: Return empty findings, existing RuleEngine will still run
        }

        return findings;
    }

    private static List<Finding> parsePmdJson(String json, List<ChangedFile> changedFiles) {
        List<Finding> findings = new ArrayList<>();
        // Note: Using simple regex parsing for JSON to avoid adding a heavy JSON library dependency
        // In a production app, a real JSON parser (Jackson/Gson) would be preferred.

        try {
            // Pattern to extract file results
            Pattern filePattern = Pattern.compile("\\{\"filename\":\"(.*?)\",\"violations\":\\[(.*?)\\]\\}");
            Matcher fileMatcher = filePattern.matcher(json);

            Map<String, ChangedFile> fileMap = new HashMap<>();
            for (ChangedFile f : changedFiles) {
                // Normalize path for matching
                fileMap.put(new File(f.path).getAbsolutePath(), f);
            }

            while (fileMatcher.find()) {
                String filePath = fileMatcher.group(1).replace("\\\\", "\\");
                String violationsJson = fileMatcher.group(2);

                ChangedFile changedFile = fileMap.get(new File(filePath).getAbsolutePath());
                if (changedFile == null) continue;

                // Extract individual violations
                Pattern violationPattern = Pattern.compile("\\{\"beginLine\":(\\d+),\"endLine\":\\d+,\"beginColumn\":\\d+,\"endColumn\":\\d+,\"rule\":\"(.*?)\",\"ruleset\":\"(.*?)\",\"packageName\":\"(.*?)\",\"class\":\"(.*?)\",\"method\":\"(.*?)\",\"variable\":\"(.*?)\",\"externalInfoUrl\":\"(.*?)\",\"priority\":(\\d+),\"description\":\"(.*?)\"\\}");
                Matcher vMatcher = violationPattern.matcher(violationsJson);

                while (vMatcher.find()) {
                    int line = Integer.parseInt(vMatcher.group(1));

                    // Filter to only changed lines
                    if (!isInChangedLines(changedFile, line)) continue;

                    String ruleName = vMatcher.group(2);
                    int priority = Integer.parseInt(vMatcher.group(9));
                    String description = vMatcher.group(10);

                    Severity severity = mapPriority(priority);
                    Category category = mapCategory(vMatcher.group(3), ruleName);

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
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing PMD JSON: " + e.getMessage());
        }

        return findings;
    }

    private static boolean isInChangedLines(ChangedFile file, int line) {
        // If no changed lines are populated (e.g. in some hook modes), default to including the finding
        if (file.changedLines == null || file.changedLines.isEmpty()) {
            return true;
        }
        // Reuse same logic as RuleEngine
        for (int i = line - 1; i <= line + 1; i++) {
            if (file.changedLines.contains(i)) return true;
        }
        return false;
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
