package com.reviewer.model;

import java.util.*;
import java.util.regex.*;

public class Models {
    public enum Severity {
        MUST_FIX, SHOULD_FIX, CONSIDER
    }

    public enum Category {
        NULL_SAFETY("Null Safety"),
        EXCEPTION_HANDLING("Exception Handling"),
        LOGGING("Logging"),
        SPRING_BOOT("Spring Boot"),
        PERFORMANCE("Performance"),
        CODE_QUALITY("Code Quality");
        
        public final String displayName;
        Category(String displayName) { this.displayName = displayName; }
    }

    public static class Finding {
        public final Severity severity;
        public final Category category;
        public final String file;
        public final int line;
        public final String code;
        public final String message;
        public final String explanation;
        public final String suggestedFix;
        
        public Finding(Severity severity, Category category, String file, int line, String code,
                       String message, String explanation, String suggestedFix) {
            this.severity = severity;
            this.category = category;
            this.file = file;
            this.line = line;
            this.code = code;
            this.message = message;
            this.explanation = explanation;
            this.suggestedFix = suggestedFix;
        }
    }

    public static class Range {
        public final int start;
        public final int end;
        public Range(int start, int end) {
            this.start = start;
            this.end = end;
        }
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Range range = (Range) o;
            return start == range.start && end == range.end;
        }
        @Override
        public int hashCode() {
            return Objects.hash(start, end);
        }
    }

    public static class AnalysisContext {
        public String className;
        public Set<String> classAnnotations = new HashSet<>();
        public Map<Range, Set<String>> methodAnnotations = new HashMap<>();
        public boolean isController;
        public boolean isService;
        public boolean isRepository;
        public boolean isEntity;
        
        public void analyze(String content, List<Range> methodRanges, String[] lines) {
            Pattern classAnnotationPattern = Pattern.compile("@(\\w+)(?:\\(.*?\\))?\\s+(?:public|class|interface|enum)");
            Matcher m = classAnnotationPattern.matcher(content);
            while (m.find()) {
                classAnnotations.add(m.group(1));
            }
            
            isController = classAnnotations.contains("RestController") || classAnnotations.contains("Controller");
            isService = classAnnotations.contains("Service");
            isRepository = classAnnotations.contains("Repository");
            isEntity = classAnnotations.contains("Entity") || classAnnotations.contains("Table");

            for (Range r : methodRanges) {
                Set<String> annos = new HashSet<>();
                // Look at lines immediately preceding the method start
                for (int i = Math.max(0, r.start - 3); i < r.start; i++) {
                    String line = lines[i].trim();
                    if (line.startsWith("@")) {
                        Matcher am = Pattern.compile("@(\\w+)").matcher(line);
                        while (am.find()) annos.add(am.group(1));
                    }
                }
                methodAnnotations.put(r, annos);
            }
        }
    }

    public static class ChangedFile {
        public final String path;
        public final String name;
        public final Set<Integer> changedLines;
        
        public ChangedFile(String path, String name, Set<Integer> changedLines) {
            this.path = path;
            this.name = name;
            this.changedLines = changedLines;
        }
    }

    public static class ImpactEntry {
        public final String fileName;
        public String fullyQualifiedName;
        public final List<String> layers = new ArrayList<>();
        public final List<String> endpoints = new ArrayList<>();
        public final List<String> functions = new ArrayList<>();
        public final List<String> notes = new ArrayList<>();
        public final List<String> recommendedTests = new ArrayList<>();

        public ImpactEntry(String fileName) {
            this.fileName = fileName;
        }

        public boolean hasSignal() {
            return !layers.isEmpty() || !endpoints.isEmpty() || !functions.isEmpty() || !notes.isEmpty();
        }
    }

    public static class TestingStatus {
        public final boolean hasTests;
        public final List<String> relatedTests;

        public TestingStatus(boolean hasTests, List<String> relatedTests) {
            this.hasTests = hasTests;
            this.relatedTests = relatedTests;
        }
    }

    public static class Config {
        public boolean blockOnMustFix = true;
        public boolean onlyChangedLines = true;
        public boolean expandChangedScopeToMethod = false;
        public boolean strictJava = false;
        public boolean strictSpring = false;
        public boolean showGoodPatterns = true;
        public boolean showTestingScope = false;
        public boolean openReport = false;
        public boolean debug = false;
        public String primaryModel = "gpt-5.1-codex";
        public String secondaryModel = "swe-1.5";
        public boolean useWindsurf = true;
        public String windsurfEndpoint = "http://localhost:49321/impact";
        public boolean enablePmdAnalysis = false;
        public boolean enableStructuralImpact = false;
        public String pmdPath = "pmd";
        public String pmdRulesetPath = "config/pmd/changelens-ruleset.xml";
    }
}
