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
        OPEN_API("OpenAPI / Swagger"),
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
        /**
         * Files that were proven to call at least one touched method (method-scoped graph).
         * Populated during impact analysis; used in the HTML report when
         * {@link Config#methodScopedDependencyGraph} is {@code true}.
         */
        public final List<String> methodScopedDependents = new ArrayList<>();

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
        public boolean enableTransitiveApiDiscovery = true;
        public int transitiveApiDiscoveryMaxDepth = 6;
        public int transitiveApiDiscoveryMaxVisitedFiles = 30;
        public int transitiveApiDiscoveryMaxControllers = 5;
        public boolean enablePmdAnalysis = false;
        public boolean enableStructuralImpact = true;
        /**
         * When false (default), the BFS only emits a caller edge when the touched method
         * name is literally present in the dependent file (token-based search, steps 1-5).
         * This is precise but misses calls hidden behind chained expressions or delegates.
         *
         * When true, enables the structural fallback (step 6 / JavaParser AST scan):
         * if the token search finds nothing, the tool looks for ANY method call on the
         * target's typed instances.  More recall, but may surface endpoints whose
         * execution path does not actually invoke the changed method.
         *
         * Set via property: transitive.caller.structural.fallback=true
         */
        public boolean transitiveCallerStructuralFallback = false;
        /**
         * When true (default), step 6 of the transitive caller scan uses the
         * JavaParser AST engine (AstInvocationFinder) when it is available on
         * the classpath — giving precise, comment/string-safe call detection.
         * Set to false to force the regex-based path even when JavaParser is
         * present (useful for comparing results or when AST parsing is slow).
         *
         * Set via property: use.ast.caller.detection=false
         */
        public boolean useAstCallerDetection = true;
        public String pmdPath = "pmd";
        public String pmdRulesetPath = "config/pmd/changelens-ruleset.xml";
        // Target Java source version; used to gate version-specific rules (e.g. pattern matching >= 16)
        public int javaSourceVersion = 17;
        /**
         * Controls what the "Dependency Mapping" graph in the HTML report displays.
         * {@code true}  (default) — method-scoped: only files that call a changed method.
         * {@code false}           — class-level:   all files that import/reference the class.
         * Falls back to class-level automatically when no method-scoped dependents were found.
         */
        public boolean methodScopedDependencyGraph = true;
        /**
         * When true, discards the on-disk dependency graph cache and rebuilds it from scratch.
         * Equivalent to passing {@code --rebuild-graph} on the command line.
         */
        public boolean rebuildGraphCache = false;
        /**
         * Maximum age of the on-disk dependency graph cache in hours.
         * Once the cache is older than this threshold it is discarded and rebuilt.
         * 12 hours is the default — long enough to survive a normal working session
         * without rebuilding on every commit, short enough to pick up structural
         * refactors done earlier in the same day.
         */
        public int graphCacheTtlHours = 12;
    }
}
