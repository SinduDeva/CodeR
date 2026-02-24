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
    private final Map<String, String> fileContentCache = new HashMap<>();
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
        debug("All staged files (" + totalStagedFiles + "): " + allStaged);

        List<String> javaFiles = allStaged.stream()
            .filter(f -> f.endsWith(".java"))
            .collect(Collectors.toList());
        debug("Java files found: " + javaFiles);

        // One git process for all files instead of one per file
        Map<String, Set<Integer>> changedLinesByFile = batchGetChangedLines(javaFiles);

        return javaFiles.stream()
            .map(f -> {
                Set<Integer> lines = changedLinesByFile.getOrDefault(f, new HashSet<>());
                if (config.expandChangedScopeToMethod) {
                    try {
                        lines = expandChangedLinesToMethodScope(f, lines);
                    } catch (IOException ignored) {}
                }
                return new ChangedFile(f, Paths.get(f).getFileName().toString(), lines);
            })
            .collect(Collectors.toList());
    }

    /** Single git diff --staged -U0 call; parses per-file hunk headers for all Java files at once. */
    private Map<String, Set<Integer>> batchGetChangedLines(List<String> javaFiles) {
        Map<String, Set<Integer>> result = new LinkedHashMap<>();
        for (String f : javaFiles) result.put(f, new HashSet<>());

        List<String> diff = runGit("git", "diff", "--staged", "-U0");
        String currentFile = null;
        for (String line : diff) {
            if (line.startsWith("+++ b/")) {
                currentFile = line.substring(6); // strip "+++ b/" prefix
            } else if (line.startsWith("@@") && currentFile != null && result.containsKey(currentFile)) {
                int plusIndex = line.indexOf('+');
                if (plusIndex == -1) continue;
                String hunkPart = line.substring(plusIndex + 1);
                int spaceIndex = hunkPart.indexOf(' ');
                if (spaceIndex != -1) hunkPart = hunkPart.substring(0, spaceIndex);
                String[] parts = hunkPart.split(",");
                try {
                    int start = Integer.parseInt(parts[0]);
                    int count = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
                    Set<Integer> fileLines = result.get(currentFile);
                    // count == 0 means a pure-deletion hunk: @@ -old,N +new,0 @@
                    // No new lines were added, so the loop body never runs and changedLines
                    // stays empty, causing touched-method detection to return [].
                    // Fix: record 'start' (the new-file position of the deletion) so the
                    // intersection check in extractTouchedMethods() can still find the method.
                    if (count == 0) {
                        fileLines.add(start);
                    } else {
                        for (int i = 0; i < count; i++) fileLines.add(start + i);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return result;
    }

    private Set<Integer> expandChangedLinesToMethodScope(String filePath, Set<Integer> changedLines) throws IOException {
        List<String> fileLines = Files.readAllLines(Path.of(filePath));
        List<LineRange> methodRanges = findMethodRanges(fileLines);
        debug("filepath, changedlines: " + filePath + changedLines);
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

                    // If count is 0, it means only deletions occurred at this position.
                    // Record 'start' so touched-method detection can still find the method.
                    if (count == 0) {
                        lines.add(start);
                    } else {
                        for (int i = 0; i < count; i++) {
                            lines.add(start + i);
                        }
                    }
                }
            }
        }
        return lines;
    }

    public String run(List<ChangedFile> allChangedFiles) throws IOException {
        debug("Received " + allChangedFiles.size() + " files for review.");
        List<ChangedFile> testFiles = allChangedFiles.stream()
            .filter(this::isTestFile)
            .collect(Collectors.toList());
        List<ChangedFile> changedFiles = allChangedFiles.stream()
            .filter(f -> !isTestFile(f))
            .collect(Collectors.toList());

        debug("Test files count: " + testFiles.size());
        debug("Non-test files to review: " + changedFiles.size());
        for (ChangedFile f : changedFiles) {
            debug("Reviewing: " + f.path);
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
                debug("PMD Path: " + config.pmdPath);
                debug("PMD Ruleset: " + config.pmdRulesetPath);
                debug("Files to analyze: " + changedFiles.size());
                List<Finding> pmdFindings = com.reviewer.analysis.PmdAnalyzer.analyze(changedFiles, config);
                findings.addAll(pmdFindings);
                debug("PMD findings: " + pmdFindings);
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

    /** Reads a file once per session; subsequent calls return the cached content. */
    private String readFileCached(Path path) throws IOException {
        String key = path.toAbsolutePath().normalize().toString();
        String cached = fileContentCache.get(key);
        if (cached != null) return cached;
        String content = Files.readString(path);
        fileContentCache.put(key, content);
        return content;
    }

    private void reviewFile(ChangedFile file) throws IOException {
        Path fullPath = Path.of(file.path).toAbsolutePath();
        if (!Files.exists(fullPath)) {
            debug("File not found for review: " + fullPath);
            return;
        }
        String content = readFileCached(fullPath);
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
        ImpactAnalyzer.setDebugEnabled(config != null && config.debug);
        ImpactAnalyzer.setStructuralFallbackEnabled(config != null && config.transitiveCallerStructuralFallback);
        ImpactAnalyzer.setAstCallerDetectionEnabled(config == null || config.useAstCallerDetection);
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
                String content = readFileCached(path);
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
                    String depContent = readFileCached(depPath);
                    String depClassName = depFileName.replace(".java", "");

                    // 1. Identify WHICH methods in the controller call the service.
                    // Pass supertypeSimpleNames so injection via interface type is also detected.
                    List<String> callingMethods = ImpactAnalyzer.getMethodsCalling(depContent, classInfo.simpleName, classInfo.fqn, classInfo.supertypeSimpleNames, touchedMethods, true);
                    debug("Calling methods in " + depFileName + " -> " + callingMethods);


                    if (!callingMethods.isEmpty()) {
                        // Track method-scoped dependents for the graph display.
                        entry.methodScopedDependents.add(dependentFile);
                        String depType = classifyDependencyType(depContent, classInfo);
                        for (String caller : callingMethods) {
                            entry.notes.add("Impacted Method [" + depType + "]: " + depFileName + " -> " + caller + "()");
                        }

                        if (depContent.contains("@RestController") || depContent.contains("@Controller")) {
                            if (!entry.layers.contains("API/Web")) entry.layers.add("API/Web");

                            List<String> endpoints = null;
                            // TreeSitter needs the Controller's own methods to find mapping annotations
                            if (config.enableStructuralImpact && com.reviewer.analysis.TreeSitterAnalyzer.isAvailable()) {
                                endpoints = com.reviewer.analysis.TreeSitterAnalyzer.extractImpactedEndpoints(depContent, depClassName, callingMethods);
                                debug("Tree-sitter endpoints for " + depFileName + ": " + endpoints);
                            }

                            // Regex fallback needs the CONTROLLER methods to find the impacted endpoints
                            if (endpoints == null || endpoints.isEmpty()) {
                                endpoints = ImpactAnalyzer.extractControllerEndpoints(depContent, depClassName, callingMethods);
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
                if (config.enableTransitiveApiDiscovery && !isController && !touchedMethods.isEmpty()) {
                    debug("Attempting transitive controller discovery for " + classInfo.fqn);
                    Set<String> existingEndpoints = new HashSet<>(entry.endpoints);
                    List<String> callChainNotes = new ArrayList<>();
                    List<String> transitiveEndpoints = discoverTransitiveControllerEndpoints(
                            classInfo,
                            touchedMethods,
                            config.transitiveApiDiscoveryMaxDepth,
                            config.transitiveApiDiscoveryMaxVisitedFiles,
                            config.transitiveApiDiscoveryMaxControllers,
                            callChainNotes
                    );
                    if (!transitiveEndpoints.isEmpty()) {
                        if (!entry.layers.contains("API/Web")) entry.layers.add("API/Web");
                        for (String ep : transitiveEndpoints) {
                            if (!existingEndpoints.contains(ep)) {
                                entry.endpoints.add(ep);
                            }
                        }
                    }
                    // Always surface the verified call-graph hops as notes so the developer
                    // can see how far impact propagates even when no endpoint is reached.
                    entry.notes.addAll(callChainNotes);
                }
                if (entry.hasSignal()) impact.add(entry);
            } catch (IOException ignored) {}
        }
        return impact;
    }

    private List<String> discoverTransitiveControllerEndpoints(JavaSymbolIndex.ClassInfo startClass, List<String> initialTouchedMethods, int maxDepth, int maxVisitedFiles, int maxControllers, List<String> outCallChainNotes) {
        if (startClass == null || reverseDependencyGraph == null || reverseDependencyGraph.isEmpty()) {
            return Collections.emptyList();
        }
        if (initialTouchedMethods == null || initialTouchedMethods.isEmpty()) {
            return Collections.emptyList();
        }
        maxDepth = Math.max(1, maxDepth);
        maxVisitedFiles = Math.max(1, maxVisitedFiles);
        maxControllers = Math.max(1, maxControllers);

        Queue<TransitiveNode> queue = new ArrayDeque<>();
        Set<String> visitedFqns = new HashSet<>();
        Set<String> visitedPaths = new HashSet<>();
        List<String> endpoints = new ArrayList<>();

        queue.add(new TransitiveNode(startClass.fqn, ImpactAnalyzer.filterValidMethodNames(initialTouchedMethods), 0, startClass.supertypeSimpleNames, null));
        visitedFqns.add(startClass.fqn);

        int visitedFiles = 0;
        int foundControllers = 0;

        while (!queue.isEmpty() && visitedFiles < maxVisitedFiles && foundControllers < maxControllers) {
            TransitiveNode node = queue.poll();
            if (node.depth >= maxDepth) continue;

            // Emit the call-chain note here (not at enqueue) so notes only appear for nodes
            // that actually pass the depth guard and are processed.
            if (node.callChainNote != null) outCallChainNotes.add(node.callChainNote);

            if (node.impactedMethods == null || node.impactedMethods.isEmpty()) {
                continue;
            }

            Set<String> dependents = getOrComputeDependents(node.fqn);
            debug("Transitive: processing node " + node.fqn + " at depth " + node.depth + " with methods " + node.impactedMethods + ", found " + dependents.size() + " dependents");
            for (String dependentFile : dependents) {
                if (visitedFiles >= maxVisitedFiles || foundControllers >= maxControllers) break;
                if (visitedPaths.contains(dependentFile)) {
                    debug("Transitive: skipping already-visited " + dependentFile);
                    continue;
                }

                Path depPath = Path.of(dependentFile);
                String depFileName = depPath.getFileName().toString();
                if (isTestFile(new ChangedFile(dependentFile, depFileName, Collections.emptySet()))) {
                    continue;
                }

                JavaSymbolIndex.ClassInfo depInfo = symbolIndex == null ? null : symbolIndex.getClassInfo(depPath).orElse(null);
                if (depInfo == null) {
                    continue;
                }
                String depContent;
                try {
                    depContent = readFileCached(depPath);
                } catch (IOException e) {
                    continue;
                }

                // Only traverse/record endpoints if we can prove a call chain to the impacted methods.
                // For intermediate (non-controller) hops, confirmedDependent=true allows the structural
                // fallback (step 6) to fire when the method name doesn't appear literally — e.g. calls
                // through an interface type or delegate — so the BFS can traverse the full chain.
                // For controllers, confirmedDependent=false forces token-based matching only: we must
                // find the exact impacted method name in the controller method body, otherwise the
                // structural scan would return every controller method that injects the service and
                // surface unrelated endpoints.
                boolean isController = depContent.contains("@RestController") || depContent.contains("@Controller");
                String currentSimpleName = simpleNameFromFqn(node.fqn);
                debug("Transitive: checking " + depFileName + " for calls to " + currentSimpleName + "." + node.impactedMethods);
                List<String> callingMethods = ImpactAnalyzer.getMethodsCalling(depContent, currentSimpleName, node.fqn, node.supertypeSimpleNames, node.impactedMethods, false, !isController);
                callingMethods = ImpactAnalyzer.filterValidMethodNames(callingMethods);
                debug("Transitive: found calling methods in " + depFileName + ": " + callingMethods);

                if (callingMethods.isEmpty()) {
                    continue;
                }

                // Mark visited only when we have a proven call-chain.
                // For controllers, we use a different key (fqn + methods) so we can revisit with different calling methods
                String visitKey = isController ? (depInfo.fqn + ":" + callingMethods.toString()) : dependentFile;
                if (!visitedPaths.add(visitKey)) {
                    debug("Transitive: skipping already-processed " + depFileName + " with methods " + callingMethods);
                    continue;
                }

                visitedFiles++;

                if (isController) {
                    foundControllers++;
                    List<String> controllerEndpoints = null;
                    if (config.enableStructuralImpact && com.reviewer.analysis.TreeSitterAnalyzer.isAvailable()) {
                        controllerEndpoints = com.reviewer.analysis.TreeSitterAnalyzer.extractImpactedEndpoints(depContent, depInfo.simpleName, callingMethods);
                    }
                    if (controllerEndpoints == null || controllerEndpoints.isEmpty()) {
                        controllerEndpoints = ImpactAnalyzer.extractControllerEndpoints(depContent, depInfo.simpleName, callingMethods);
                    }
                    if (controllerEndpoints != null) endpoints.addAll(controllerEndpoints);
                } else {
                    // Key by (fqn + sorted touched methods) so the same class can be re-enqueued
                    // when reached via a different call path carrying different methods.
                    // E.g., if ClassA is reachable via methodX (path 1) AND methodY (path 2),
                    // both paths must be explored or we lose the endpoints reachable via path 2.
                    // The depth limit is the cycle-guard; the FQN-only key was too coarse.
                    List<String> sortedMethods = new java.util.ArrayList<>(callingMethods);
                    java.util.Collections.sort(sortedMethods);
                    String fqnMethodsKey = depInfo.fqn + "|" + sortedMethods;
                    if (!visitedFqns.add(fqnMethodsKey)) {
                        continue;
                    }
                    // Record this hop in the call chain regardless of whether it reaches an endpoint.
                    // The reverse graph confirmed it depends on the target; the token search confirmed
                    // it calls the touched methods — so it is a real, verified hop in the impact path.
                    String methodSummary = callingMethods.size() == 1
                            ? callingMethods.get(0) + "()"
                            : callingMethods.subList(0, Math.min(2, callingMethods.size()))
                                    .stream().map(m -> m + "()").collect(Collectors.joining(", "))
                              + (callingMethods.size() > 2 ? ", +" + (callingMethods.size() - 2) + " more" : "");
                    String note = "Transitive caller [depth " + (node.depth + 1) + "]: "
                            + depInfo.simpleName + "." + methodSummary;
                    queue.add(new TransitiveNode(depInfo.fqn, callingMethods, node.depth + 1, depInfo.supertypeSimpleNames, note));
                }
            }
        }
        return endpoints.stream().distinct().collect(Collectors.toList());
    }

    private Set<String> getOrComputeDependents(String fqn) {
        if (fqn == null || fqn.isBlank() || symbolIndex == null) {
            return Collections.emptySet();
        }
        Set<String> existing = reverseDependencyGraph.get(fqn);
        if (existing != null) {
            return existing;
        }
        JavaSymbolIndex.ClassInfo targetInfo = null;
        for (JavaSymbolIndex.ClassInfo info : symbolIndex.getAllClasses()) {
            if (fqn.equals(info.fqn)) {
                targetInfo = info;
                break;
            }
        }
        if (targetInfo == null) {
            return Collections.emptySet();
        }
        // Pass the shared content cache to avoid re-reading files already in memory.
        Map<String, Set<String>> computed = symbolIndex.buildReverseDependencyGraph(
                Collections.singletonList(targetInfo), fileContentCache);
        Set<String> deps = computed.getOrDefault(fqn, Collections.emptySet());
        reverseDependencyGraph.put(fqn, new LinkedHashSet<>(deps));
        return reverseDependencyGraph.get(fqn);
    }

    /**
     * Classifies how a candidate file depends on the target class.
     * Returns one of: "INJECTED" (Spring @Autowired/@Inject), "EXTENDS" (inheritance),
     * or "CALLS" (direct method/constructor call usage).
     */
    private String classifyDependencyType(String candidateContent, JavaSymbolIndex.ClassInfo target) {
        boolean hasInjectionAnno = candidateContent.contains("@Autowired") || candidateContent.contains("@Inject");
        if (hasInjectionAnno) {
            // Field uses the target type directly
            if (containsWordToken(candidateContent, target.simpleName)) return "INJECTED";
            // Field uses one of the target's interfaces (most common Spring pattern)
            for (String supertype : target.supertypeSimpleNames) {
                if (containsWordToken(candidateContent, supertype)) return "INJECTED";
            }
        }
        // Check extends/implements
        Pattern inheritPattern = Pattern.compile(
            "(?:extends|implements)[^{]*\\b" + Pattern.quote(target.simpleName) + "\\b");
        if (inheritPattern.matcher(candidateContent).find()) return "EXTENDS";
        for (String supertype : target.supertypeSimpleNames) {
            Pattern supertypeInherit = Pattern.compile(
                "(?:extends|implements)[^{]*\\b" + Pattern.quote(supertype) + "\\b");
            if (supertypeInherit.matcher(candidateContent).find()) return "EXTENDS";
        }
        return "CALLS";
    }

    private static boolean containsWordToken(String content, String token) {
        if (token == null || token.isEmpty()) return false;
        Pattern p = Pattern.compile("\\b" + Pattern.quote(token) + "\\b");
        return p.matcher(content).find();
    }

    private static String simpleNameFromFqn(String fqn) {
        if (fqn == null) return null;
        String trimmed = fqn.trim();
        if (trimmed.isEmpty()) return null;
        int idx = trimmed.lastIndexOf('.');
        String simple = idx >= 0 ? trimmed.substring(idx + 1) : trimmed;
        return simple.isEmpty() ? null : simple;
    }

    private static class TransitiveNode {
        final String fqn;
        final List<String> impactedMethods;
        final int depth;
        /** Simple names of the target's supertypes/interfaces for interface-injection detection. */
        final List<String> supertypeSimpleNames;
        /** Human-readable call-chain note for this node; emitted only if the node passes the depth guard. */
        final String callChainNote;
        TransitiveNode(String fqn, List<String> impactedMethods, int depth, List<String> supertypeSimpleNames, String callChainNote) {
            this.fqn = fqn;
            this.impactedMethods = impactedMethods;
            this.depth = depth;
            this.supertypeSimpleNames = supertypeSimpleNames != null ? supertypeSimpleNames : Collections.emptyList();
            this.callChainNote = callChainNote;
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

        // Try loading the graph from the disk cache first (avoids re-reading all source files).
        Map<String, Set<String>> cached = tryLoadGraphCache(changedFiles);
        if (cached != null) {
            reverseDependencyGraph = cached;
            debug("Dependency graph loaded from disk cache.");
            return;
        }

        List<JavaSymbolIndex.ClassInfo> targets = changedFiles.stream()
            .map(this::resolveClassInfo)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());

        // Pass the in-memory content cache so JavaSymbolIndex does not re-read files
        // that ReviewEngine has already loaded via readFileCached().
        reverseDependencyGraph = symbolIndex.buildReverseDependencyGraph(targets, fileContentCache);

        saveGraphCache(changedFiles, reverseDependencyGraph);
    }

    // ── Disk cache helpers ─────────────────────────────────────────────────────────────────────

    /**
     * Attempts to load the dependency graph from the disk cache.
     * Returns null if the cache is missing, stale, or corrupt.
     * Cache format (one entry per line):
     *   V=1
     *   KEY=<csv of path:mtime pairs for changed files>
     *   DEP=<targetFqn>=<dep1|dep2|…>
     */
    private Map<String, Set<String>> tryLoadGraphCache(List<ChangedFile> changedFiles) {
        try {
            if (config.rebuildGraphCache) {
                debug("Graph cache rebuild forced via flag — skipping cache.");
                return null;
            }
            if (!Files.exists(GRAPH_CACHE)) return null;
            List<String> lines = Files.readAllLines(GRAPH_CACHE, java.nio.charset.StandardCharsets.UTF_8);
            // V=3 format: line 0 = V=3, line 1 = TS=<epoch-ms>, line 2 = KEY=..., lines 3+ = DEP=...
            // (V=2 caches used the wrong package for interface supertypes and must be discarded.)
            if (lines.size() < 3) return null;
            if (!"V=3".equals(lines.get(0))) return null;

            String tsLine = lines.get(1);
            if (!tsLine.startsWith("TS=")) return null;
            long cachedAt = Long.parseLong(tsLine.substring(3));
            long ttlMs = (long) config.graphCacheTtlHours * 3_600_000L;
            if (System.currentTimeMillis() - cachedAt > ttlMs) {
                debug("Graph cache expired (age > " + config.graphCacheTtlHours + "h) — rebuilding.");
                return null;
            }

            String expectedKey = "KEY=" + buildCacheKey(changedFiles);
            if (!expectedKey.equals(lines.get(2))) return null;

            Map<String, Set<String>> graph = new LinkedHashMap<>();
            for (int i = 3; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!line.startsWith("DEP=")) continue;
                String rest = line.substring(4);
                int eq = rest.indexOf('=');
                if (eq == -1) continue;
                String fqn = rest.substring(0, eq);
                String pathsPart = rest.substring(eq + 1);
                Set<String> paths = new LinkedHashSet<>();
                if (!pathsPart.isEmpty()) {
                    for (String p : pathsPart.split("\\|")) {
                        if (!p.isEmpty()) paths.add(p);
                    }
                }
                graph.put(fqn, paths);
            }
            debug("Graph cache hit — key: " + expectedKey);
            return graph;
        } catch (Exception e) {
            debug("Graph cache load failed: " + e.getMessage());
            return null;
        }
    }

    private void saveGraphCache(List<ChangedFile> changedFiles, Map<String, Set<String>> graph) {
        try {
            Files.createDirectories(CACHE_DIR);
            StringBuilder sb = new StringBuilder();
            sb.append("V=3\n");
            sb.append("TS=").append(System.currentTimeMillis()).append('\n');
            sb.append("KEY=").append(buildCacheKey(changedFiles)).append('\n');
            for (Map.Entry<String, Set<String>> entry : graph.entrySet()) {
                sb.append("DEP=").append(entry.getKey()).append('=');
                sb.append(String.join("|", entry.getValue()));
                sb.append('\n');
            }
            Files.writeString(GRAPH_CACHE, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
            debug("Graph cache saved (TTL=" + config.graphCacheTtlHours + "h).");
        } catch (Exception e) {
            debug("Graph cache save failed: " + e.getMessage());
        }
    }

    /**
     * Builds a stable cache key from the changed files' paths and modification times.
     * Any file content change or set change invalidates the cache.
     */
    private String buildCacheKey(List<ChangedFile> changedFiles) {
        return changedFiles.stream()
            .sorted(Comparator.comparing(f -> f.path))
            .map(f -> {
                try {
                    Path p = resolvePath(f.path);
                    long mtime = (p != null && Files.exists(p))
                        ? Files.getLastModifiedTime(p).toMillis() : 0L;
                    return f.path + ":" + mtime;
                } catch (IOException e) {
                    return f.path + ":0";
                }
            })
            .collect(Collectors.joining(","));
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
