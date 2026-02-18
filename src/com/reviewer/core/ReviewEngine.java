package com.reviewer.core;

import com.reviewer.analysis.ImpactAnalyzer;
import com.reviewer.analysis.JavaSymbolIndex;
import com.reviewer.analysis.RuleEngine;
import com.reviewer.model.Models.*;
import com.reviewer.util.ColorConsole;
import com.reviewer.report.HtmlReportGenerator;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.*;
import java.util.regex.*;

public class ReviewEngine {
    private static final String VERSION = "2.1.0";
    private Config config;
    private List<Finding> findings = new ArrayList<>();
    private Map<String, TestingStatus> testingStatusByFile = new LinkedHashMap<>();
    private List<ImpactEntry> impactEntries = new ArrayList<>();
    private String currentBranch = "unknown";
    private int totalStagedFiles = 0;
    private Map<String, Set<String>> reverseDependencyGraph = new HashMap<>();
    private Map<String, JavaSymbolIndex.ClassInfo> classInfoCache = new HashMap<>();
    private JavaSymbolIndex symbolIndex;
    private Path repoRoot;
    private final Path CACHE_DIR = Paths.get(".code-reviewer-cache");
    private final Path GRAPH_CACHE = CACHE_DIR.resolve("reverse-graph.json");

    public ReviewEngine(Config config) {
        this.config = config;
        this.repoRoot = resolveRepoRoot();
        initializeBranch();
    }

    private void debug(String message) {
        if (config != null && config.debug) {
            System.out.println("[DEBUG] " + message);
        }
    }

    private Path resolveRepoRoot() {
        List<String> root = runGit("git", "rev-parse", "--show-toplevel");
        if (!root.isEmpty()) {
            return Paths.get(root.get(0)).toAbsolutePath().normalize();
        }
        return Path.of("").toAbsolutePath().normalize();
    }

    private void initializeBranch() {
        List<String> branchInfo = runGit("git", "rev-parse", "--abbrev-ref", "HEAD");
        if (!branchInfo.isEmpty()) {
            currentBranch = branchInfo.get(0);
        }
    }

    public List<ChangedFile> getStagedFiles() {
        List<String> allStaged = runGit("git", "diff", "--cached", "--name-only");
        this.totalStagedFiles = allStaged.size();
        System.out.println("[DEBUG] All staged files (" + totalStagedFiles + "): " + allStaged);
        
        List<String> javaFiles = allStaged.stream()
            .filter(f -> f.endsWith(".java"))
            .collect(Collectors.toList());
        System.out.println("[DEBUG] Java files found: " + javaFiles);
            
        return javaFiles.stream()
            .map(f -> {
                Set<Integer> lines = getChangedLines(f);
                if (config.expandChangedScopeToMethod) {
                    try {
                        lines = expandChangedLinesToMethodScope(f, lines);
                    } catch (IOException ignored) {}
                }
                return new ChangedFile(f, Paths.get(f).getFileName().toString(), lines);
            })
            .collect(Collectors.toList());
    }

    private Set<Integer> expandChangedLinesToMethodScope(String filePath, Set<Integer> changedLines) throws IOException {
        List<String> fileLines = Files.readAllLines(Path.of(filePath));
        List<LineRange> methodRanges = findMethodRanges(fileLines);
        System.out.println("[DEBUG]filepath, changedlines: " + filePath +
                changedLines);
        if (methodRanges.isEmpty()) return changedLines;

        Set<Integer> expanded = new HashSet<>(changedLines);
        for (LineRange r : methodRanges) {
            boolean intersects = false;
            for (int cl : changedLines) {
                if (cl >= r.startLine && cl <= r.endLine) {
                    intersects = true;
                    break;
                }
            }
            if (!intersects) continue;
            for (int i = r.startLine; i <= r.endLine; i++) {
                expanded.add(i);
            }
        }
        return expanded;
    }

    private List<LineRange> findMethodRanges(List<String> fileLines) {
        List<LineRange> ranges = new ArrayList<>();
        int i = 0;
        while (i < fileLines.size()) {
            int sigStart = i;
            StringBuilder sig = new StringBuilder();
            int j = i;
            boolean sawParen = false;
            boolean sawBrace = false;
            while (j < fileLines.size() && j - i < 6) {
                String l = fileLines.get(j);
                sig.append(l).append("\n");
                if (l.contains("(")) sawParen = true;
                if (l.contains("{")) {
                    sawBrace = true;
                    break;
                }
                if (l.contains(";")) break;
                j++;
            }

            if (sawParen && sawBrace) {
                String normalized = sig.toString().replaceAll("/\\*.*?\\*/", " ").replaceAll("//.*", " ").replaceAll("\\s+", " ").trim();
                if (looksLikeMethodSignature(normalized)) {
                    int braceLineIdx = j;
                    int endLine = findBlockEndLine(fileLines, braceLineIdx, fileLines.get(braceLineIdx).indexOf('{'));
                    if (endLine > 0) {
                        ranges.add(new LineRange(sigStart + 1, endLine));
                        i = endLine;
                        continue;
                    }
                }
            }
            i++;
        }
        return ranges;
    }

    private boolean looksLikeMethodSignature(String normalized) {
        if (!normalized.contains("(") || !normalized.contains(")") || !normalized.contains("{")) return false;
        if (normalized.matches("(?i)^\\s*(if|for|while|switch|catch|do|try|synchronized)\\b.*")) return false;
        return normalized.matches(".*\\b\\w+\\s*\\([^;]*\\)\\s*(throws\\s+[^\\{]+)?\\s*\\{.*");
    }

    private int findBlockEndLine(List<String> lines, int startLineIdx, int startCharIdx) {
        int depth = 0;
        for (int i = startLineIdx; i < lines.size(); i++) {
            String l = lines.get(i);
            int start = (i == startLineIdx) ? startCharIdx : 0;
            for (int c = start; c < l.length(); c++) {
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

    private Set<Integer> getChangedLines(String filePath) {
        Set<Integer> lines = new HashSet<>();
        // Get the staged diff with 0 context lines to make parsing easier
        List<String> diff = runGit("git", "diff", "--staged", "-U0", "--", filePath);
        for (String line : diff) {
            if (line.startsWith("@@")) {
                // Parse the hunk header: @@ -oldStart,oldCount +newStart,newCount @@
                // We are interested in the +newStart,newCount part
                int plusIndex = line.indexOf('+');
                if (plusIndex != -1) {
                    String hunkPart = line.substring(plusIndex + 1);
                    int spaceIndex = hunkPart.indexOf(' ');
                    if (spaceIndex != -1) {
                        hunkPart = hunkPart.substring(0, spaceIndex);
                    }
                    
                    String[] parts = hunkPart.split(",");
                    int start = Integer.parseInt(parts[0]);
                    int count = (parts.length > 1) ? Integer.parseInt(parts[1]) : 1;
                    
                    // If count is 0, it means only deletions occurred at this position
                    for (int i = 0; i < count; i++) {
                        lines.add(start + i);
                    }
                }
            }
        }
        return lines;
    }

    public String run(List<ChangedFile> allChangedFiles) throws IOException {
        System.out.println("[DEBUG] Received " + allChangedFiles.size() + " files for review.");
        List<ChangedFile> testFiles = allChangedFiles.stream()
            .filter(this::isTestFile)
            .collect(Collectors.toList());
        List<ChangedFile> changedFiles = allChangedFiles.stream()
            .filter(f -> !isTestFile(f))
            .collect(Collectors.toList());

        System.out.println("[DEBUG] Test files count: " + testFiles.size());
        System.out.println("[DEBUG] Non-test files to review: " + changedFiles.size());
        for (ChangedFile f : changedFiles) {
            System.out.println("[DEBUG] Reviewing: " + f.path);
        }

        if (changedFiles.isEmpty()) {
            printNoChanges();
            return null;
        }

        for (ChangedFile file : changedFiles) {
            reviewFile(file);
        }
        // Integration of PMD as an optional enhancement with graceful fallback
        if (config.enablePmdAnalysis) {
            try {
                System.out.println("[DEBUG] PMD Path: " + config.pmdPath);
                System.out.println("[DEBUG] PMD Ruleset: " + config.pmdRulesetPath);
                System.out.println("[DEBUG] Files to analyze: " + changedFiles.size());
                List<Finding> pmdFindings = com.reviewer.analysis.PmdAnalyzer.analyze(changedFiles, config);
                findings.addAll(pmdFindings);
                System.out.println(pmdFindings);
            } catch (Exception e) {
                System.err.println("PMD analysis failed, falling back to built-in rules: " + e.getMessage());
            }
        }

        testingStatusByFile = generateTestingStatus(changedFiles, testFiles);
        
        ensureSymbolIndex();
        loadOrBuildReverseGraph(changedFiles);
        
        debug("Tree-sitter available: " + com.reviewer.analysis.TreeSitterAnalyzer.isAvailable());
        impactEntries = analyzeImpact(changedFiles);
        enrichImpactEntriesWithTesting();

        printReport(changedFiles);
        return generateHtmlReport(changedFiles);
    }

    private boolean isTestFile(ChangedFile file) {
        String path = file.path.replace('\\', '/');
        return path.contains("/test/") || file.name.endsWith("Test.java");
    }

    private void reviewFile(ChangedFile file) throws IOException {
        Path fullPath = Path.of(file.path).toAbsolutePath();
        if (!Files.exists(fullPath)) {
            System.out.println("[DEBUG] File not found for review: " + fullPath);
            return;
        }
        String content = Files.readString(fullPath);
        String[] lines = content.split("\\R");
        RuleEngine.runRules(content, lines, file, findings, config);
    }
    private int findMatchingBrace(String content, int startIndex) {
        int depth = 0;
        for (int i = startIndex; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i + 1;
            }
        }
        return -1;
    }

    private List<ImpactEntry> analyzeImpact(List<ChangedFile> files) {
        List<ImpactEntry> impact = new ArrayList<>();
        for (ChangedFile f : files) {
            ImpactEntry entry = new ImpactEntry(f.name);
            JavaSymbolIndex.ClassInfo classInfo = resolveClassInfo(f);
            if (classInfo == null) {
                debug("No class info for " + f.path + ", skipping impact detection");
                continue;
            }
            entry.fullyQualifiedName = classInfo.fqn;
            try {
                Path path = resolvePath(f.path);
                if (path == null || !Files.exists(path)) continue;
                String content = Files.readString(path);
                String className = classInfo.simpleName;
                debug("Analyzing impact for " + classInfo.fqn + " (" + f.path + ")");
                
                List<String> touchedMethods = null;
                if (com.reviewer.analysis.TreeSitterAnalyzer.isAvailable()) {
                    touchedMethods = com.reviewer.analysis.TreeSitterAnalyzer.extractTouchedMethods(f, content);
                    debug("Tree-sitter touched methods: " + touchedMethods);
                }

                if (touchedMethods == null || touchedMethods.isEmpty()) {
                    touchedMethods = ImpactAnalyzer.extractTouchedMethods(f, content);
                    debug("Fallback regex touched methods: " + touchedMethods);
                }
                touchedMethods = ImpactAnalyzer.filterValidMethodNames(touchedMethods);
                debug("Filtered touched methods: " + touchedMethods);
                if (!touchedMethods.isEmpty()) {
                    entry.functions.addAll(touchedMethods);
                }

                if (!touchedMethods.isEmpty() && (content.contains("@RestController") || content.contains("@Controller"))) {
                    if (!entry.layers.contains("API/Web")) entry.layers.add("API/Web");

                    List<String> endpoints = null;
                    if (com.reviewer.analysis.TreeSitterAnalyzer.isAvailable()) {
                        endpoints = com.reviewer.analysis.TreeSitterAnalyzer.extractImpactedEndpoints(content, className, touchedMethods);
                        debug("Controller self endpoints (tree-sitter): " + endpoints);
                    }
                    if (endpoints == null || endpoints.isEmpty()) {
                        endpoints = ImpactAnalyzer.extractControllerEndpoints(content, className, touchedMethods);
                        debug("Controller self endpoints (regex): " + endpoints);
                    }
                    if (endpoints != null) {
                        entry.endpoints.addAll(endpoints);
                    }
                }

                Set<String> dependents = reverseDependencyGraph.getOrDefault(classInfo.fqn, Collections.emptySet());
                debug("Dependents for " + classInfo.fqn + ": " + dependents);
                for (String dependentFile : dependents) {
                    Path depPath = Path.of(dependentFile);
                    String depFileName = depPath.getFileName().toString();
                    if (isTestFile(new ChangedFile(dependentFile, depFileName, Collections.emptySet()))) {
                        debug("Skipping dependent test file " + dependentFile);
                        continue;
                    }
                    String depContent = Files.readString(depPath);
                    String depClassName = depFileName.replace(".java", "");

                    // 1. Identify WHICH methods in the controller call the service
                    List<String> callingMethods = ImpactAnalyzer.getMethodsCalling(depContent, classInfo.simpleName, classInfo.fqn, touchedMethods);
                    debug("Calling methods in " + depFileName + " -> " + callingMethods);


                    if (!callingMethods.isEmpty()) {
                        for (String caller : callingMethods) {
                            entry.notes.add("Impacted Method: " + depFileName + " -> " + caller + "()");
                        }

                        if (depContent.contains("@RestController") || depContent.contains("@Controller")) {
                            if (!entry.layers.contains("API/Web")) entry.layers.add("API/Web");

                            List<String> endpoints = null;
                            // TreeSitter needs the Controller's own methods to find mapping annotations
                            if (config.enableStructuralImpact && com.reviewer.analysis.TreeSitterAnalyzer.isAvailable()) {
                                endpoints = com.reviewer.analysis.TreeSitterAnalyzer.extractImpactedEndpoints(depContent, depClassName, callingMethods);
                                debug("Tree-sitter endpoints for " + depFileName + ": " + endpoints);
                            }

                            // Regex fallback needs the SERVICE'S touched methods to find the calls inside the Controller
                            if (endpoints == null || endpoints.isEmpty()) {
                                endpoints = ImpactAnalyzer.extractSpecificEndpoints(depContent, className, touchedMethods);
                                debug("Regex endpoints for " + depFileName + ": " + endpoints);
                            }

                            if (endpoints != null) {
                                entry.endpoints.addAll(endpoints);
                            }
                        } else if (depContent.contains("@Service")) {
                            if (!entry.layers.contains("Service")) entry.layers.add("Service");
                        }
                    }
                }

                boolean isController = content.contains("@RestController") || content.contains("@Controller");
                if (config.enableTransitiveApiDiscovery && !isController && entry.endpoints.isEmpty() && !touchedMethods.isEmpty()) {
                    debug("No endpoints found for " + classInfo.fqn + "; attempting transitive controller discovery");
                    List<String> transitiveEndpoints = discoverTransitiveControllerEndpoints(
                            classInfo,
                            config.transitiveApiDiscoveryMaxDepth,
                            config.transitiveApiDiscoveryMaxVisitedFiles,
                            config.transitiveApiDiscoveryMaxControllers
                    );
                    if (!transitiveEndpoints.isEmpty()) {
                        if (!entry.layers.contains("API/Web")) entry.layers.add("API/Web");
                        for (String ep : transitiveEndpoints) {
                            entry.endpoints.add("Potential: " + ep);
                        }
                    }
                }
                if (entry.hasSignal()) impact.add(entry);
            } catch (IOException ignored) {}
        }
        return impact;
    }

    private List<String> discoverTransitiveControllerEndpoints(JavaSymbolIndex.ClassInfo startClass, int maxDepth, int maxVisitedFiles, int maxControllers) {
        if (startClass == null || reverseDependencyGraph == null || reverseDependencyGraph.isEmpty()) {
            return Collections.emptyList();
        }
        maxDepth = Math.max(1, maxDepth);
        maxVisitedFiles = Math.max(1, maxVisitedFiles);
        maxControllers = Math.max(1, maxControllers);

        Queue<TransitiveNode> queue = new ArrayDeque<>();
        Set<String> visitedFqns = new HashSet<>();
        Set<String> visitedPaths = new HashSet<>();
        List<String> endpoints = new ArrayList<>();

        queue.add(new TransitiveNode(startClass.fqn, 0));
        visitedFqns.add(startClass.fqn);

        int visitedFiles = 0;
        int foundControllers = 0;

        while (!queue.isEmpty() && visitedFiles < maxVisitedFiles && foundControllers < maxControllers) {
            TransitiveNode node = queue.poll();
            if (node.depth >= maxDepth) continue;

            Set<String> dependents = reverseDependencyGraph.getOrDefault(node.fqn, Collections.emptySet());
            for (String dependentFile : dependents) {
                if (visitedFiles >= maxVisitedFiles || foundControllers >= maxControllers) break;
                if (!visitedPaths.add(dependentFile)) continue;

                Path depPath = Path.of(dependentFile);
                String depFileName = depPath.getFileName().toString();
                if (isTestFile(new ChangedFile(dependentFile, depFileName, Collections.emptySet()))) {
                    continue;
                }

                JavaSymbolIndex.ClassInfo depInfo = symbolIndex == null ? null : symbolIndex.getClassInfo(depPath).orElse(null);
                if (depInfo == null) {
                    continue;
                }
                if (!visitedFqns.add(depInfo.fqn)) {
                    continue;
                }

                visitedFiles++;

                String depContent;
                try {
                    depContent = Files.readString(depPath);
                } catch (IOException e) {
                    continue;
                }

                boolean isController = depContent.contains("@RestController") || depContent.contains("@Controller");
                if (isController) {
                    foundControllers++;
                    List<String> controllerEndpoints = ImpactAnalyzer.extractAllControllerEndpoints(depContent, depInfo.simpleName);
                    if (controllerEndpoints != null) {
                        endpoints.addAll(controllerEndpoints);
                    }
                }

                queue.add(new TransitiveNode(depInfo.fqn, node.depth + 1));
            }
        }
        return endpoints.stream().distinct().collect(Collectors.toList());
    }

    private static class TransitiveNode {
        final String fqn;
        final int depth;
        TransitiveNode(String fqn, int depth) {
            this.fqn = fqn;
            this.depth = depth;
        }
    }

    private Optional<String> findApiWrapper(String content, Collection<String> existingCallers) {
        Pattern headerPattern = Pattern.compile("(?ms)(@[\\w]+Mapping\\b[^\\n]*?\\)\\s*)?(?:public|protected|private)[^{]*?(\\w+)\\s*\\([^)]*\\)\\s*\\{");
        Matcher matcher = headerPattern.matcher(content);
        while (matcher.find()) {
            String methodName = matcher.group(2);
            if (existingCallers.contains(methodName)) continue;
            int bodyStart = matcher.end();
            int bodyEnd = findMatchingBrace(content, bodyStart - 1);
            if (bodyEnd == -1) continue;
            String body = content.substring(bodyStart, bodyEnd);
            for (String target : existingCallers) {
                if (body.matches("(?s).*\\b" + Pattern.quote(target) + "\\s*\\(")) {
                    return Optional.of(methodName);
                }
            }
        }
        return Optional.empty();
    }

    private void loadOrBuildReverseGraph(List<ChangedFile> changedFiles) {
        if (symbolIndex == null) {
            reverseDependencyGraph = Collections.emptyMap();
            return;
        }
        List<JavaSymbolIndex.ClassInfo> targets = changedFiles.stream()
            .map(this::resolveClassInfo)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
        reverseDependencyGraph = symbolIndex.buildReverseDependencyGraph(targets);
    }

    private void ensureSymbolIndex() {
        if (symbolIndex != null) return;
        try {
            symbolIndex = JavaSymbolIndex.build(repoRoot);
        } catch (IOException e) {
            System.err.println("[WARN] Failed to build Java symbol index: " + e.getMessage());
            symbolIndex = null;
        }
    }

    private JavaSymbolIndex.ClassInfo resolveClassInfo(ChangedFile file) {
        if (classInfoCache.containsKey(file.path)) {
            return classInfoCache.get(file.path);
        }
        Path primary = resolvePath(file.path);
        if (primary != null) {
            Optional<JavaSymbolIndex.ClassInfo> info = symbolIndex.getClassInfo(primary);
            if (info.isPresent()) {
                classInfoCache.put(file.path, info.get());
                return info.get();
            }
        }
        return null;
    }

    private Path resolvePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) return null;
        Path candidate = repoRoot.resolve(relativePath).normalize();
        if (Files.exists(candidate)) return candidate;
        Path absolute = Paths.get(relativePath).toAbsolutePath().normalize();
        if (Files.exists(absolute)) return absolute;
        return candidate;
    }

    private int getLineNumber(String content, int index) {
        return (int) content.substring(0, Math.min(index, content.length())).chars().filter(c -> c == '\n').count() + 1;
    }

    private void printNoChanges() {
        System.out.println(ColorConsole.CYAN + "\nNo Java files changed. Nothing to review.\n" + ColorConsole.RESET);
    }

    private List<String> runGit(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                return r.lines().collect(Collectors.toList());
            }
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private void printReport(List<ChangedFile> files) {
        System.out.println(ColorConsole.CYAN + "\n--- Code Review Summary ---" + ColorConsole.RESET);
        System.out.println("Branch: " + currentBranch);
        System.out.println("Staged Files: " + totalStagedFiles);
        
        long mustFix = findings.stream().filter(f -> f.severity == Severity.MUST_FIX).count();
        if (mustFix > 0) {
            System.out.println(ColorConsole.RED + "Critical Issues (MUST FIX): " + mustFix + ColorConsole.RESET);
        }
        
        System.out.println("Java Issues: " + findings.size());
    }

    private static class LineRange {
        int startLine;
        int endLine;
        LineRange(int startLine, int endLine) {
            this.startLine = startLine;
            this.endLine = endLine;
        }
    }

    private String generateHtmlReport(List<ChangedFile> files) {
        return HtmlReportGenerator.generate(files, findings, impactEntries, testingStatusByFile, currentBranch, totalStagedFiles, config, reverseDependencyGraph);
    }
    
    private Map<String, TestingStatus> generateTestingStatus(List<ChangedFile> changedFiles, List<ChangedFile> testFiles) {
        Map<String, TestingStatus> statusMap = new LinkedHashMap<>();
        for (ChangedFile f : changedFiles) {
            String baseName = f.name.replace(".java", "");
            List<String> relatedTests = testFiles.stream()
                .filter(t -> matchesTestFile(baseName, t.name))
                .map(t -> t.path)
                .collect(Collectors.toList());
            statusMap.put(f.name, new TestingStatus(!relatedTests.isEmpty(), relatedTests));
        }
        return statusMap;
    }

    private boolean matchesTestFile(String baseName, String testFileName) {
        String normalized = testFileName.replace(".java", "");
        return normalized.contains(baseName);
    }

    private void enrichImpactEntriesWithTesting() {
        if (impactEntries == null || impactEntries.isEmpty() || testingStatusByFile.isEmpty()) return;
        for (ImpactEntry entry : impactEntries) {
            TestingStatus status = testingStatusByFile.get(entry.fileName);
            if (status == null) continue;
            if (status.hasTests) {
                if (entry.recommendedTests.isEmpty()) {
                    entry.recommendedTests.addAll(status.relatedTests);
                }
            } else if (!entry.notes.stream().anyMatch(n -> n.contains("No related tests"))) {
                entry.notes.add("Testing: No related tests found for this change.");
            }
        }
    }

    public int getExitCode() {
        long mustFix = findings.stream().filter(f -> f.severity == Severity.MUST_FIX).count();
        return (mustFix > 0 && config.blockOnMustFix) ? 1 : 0;
    }
}
