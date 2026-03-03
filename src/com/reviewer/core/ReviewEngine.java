package com.reviewer.core;

import com.reviewer.analysis.ImpactAnalyzer;
import com.reviewer.analysis.JavaSymbolIndex;
import com.reviewer.analysis.OpenApiSpecParser;
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
    /**
     * Populated lazily from {@code openapi.spec.paths} config.
     * Maps operationId → "HTTP_METHOD /path" (e.g. "processAffiliateLead" → "POST /affiliate/v1/lead").
     */
    private Map<String, String> openApiOperationMap = null;

    public ReviewEngine(Config config) {
        this.config = config;
        this.repoRoot = resolveRepoRoot();
        initializeBranch();
    }

    /**
     * Lazily loads and returns the OpenAPI operation map.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>If {@code openapi.spec.paths} is set, expand each comma-separated entry as a glob
     *       pattern relative to the repo root (e.g. {@code apispec/*.yml}, {@code **\/openapi\/*.yaml}).
     *       Exact paths without wildcards are also accepted.</li>
     *   <li>If {@code openapi.spec.paths} is blank, auto-discover spec files by scanning the repo
     *       for common OpenAPI file locations and naming conventions.</li>
     * </ol>
     */
    private Map<String, String> getOpenApiOperationMap() {
        if (openApiOperationMap != null) return openApiOperationMap;
        List<Path> specPaths;
        if (config != null && config.openApiSpecPaths != null && !config.openApiSpecPaths.isBlank()) {
            specPaths = resolveOpenApiGlobs(config.openApiSpecPaths);
        } else {
            specPaths = autoDiscoverOpenApiSpecs();
        }
        openApiOperationMap = OpenApiSpecParser.parseAll(specPaths);
        if (!openApiOperationMap.isEmpty()) {
            debug("OpenAPI operation map loaded: " + openApiOperationMap.size() + " operations from " + specPaths.size() + " spec(s)");
        }
        return openApiOperationMap;
    }

    /**
     * Expands a comma-separated list of glob patterns (relative to repo root) into resolved paths.
     * Supports both exact paths and glob wildcards (* and **).
     */
    private List<Path> resolveOpenApiGlobs(String specPathsConfig) {
        List<Path> result = new ArrayList<>();
        for (String raw : specPathsConfig.split(",")) {
            String pattern = raw.trim();
            if (pattern.isEmpty()) continue;
            // Normalize path separators for glob matching on all OSes
            pattern = pattern.replace('\\', '/');
            if (!pattern.contains("*") && !pattern.contains("?")) {
                // Exact path — no glob expansion needed
                Path candidate = repoRoot.resolve(pattern).normalize();
                if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                    result.add(candidate);
                    debug("OpenAPI spec (exact): " + candidate);
                } else {
                    debug("OpenAPI spec path not found: " + candidate);
                }
            } else {
                // Glob pattern — walk the repo and collect matches
                java.nio.file.PathMatcher matcher = repoRoot.getFileSystem()
                        .getPathMatcher("glob:" + repoRoot.toString().replace('\\', '/') + "/" + pattern);
                try (Stream<Path> walk = Files.walk(repoRoot)) {
                    walk.filter(Files::isRegularFile)
                        .filter(p -> {
                            String normalized = p.toAbsolutePath().normalize().toString().replace('\\', '/');
                            return matcher.matches(java.nio.file.Paths.get(normalized));
                        })
                        .forEach(p -> {
                            result.add(p);
                            debug("OpenAPI spec (glob match): " + p);
                        });
                } catch (IOException e) {
                    debug("OpenAPI glob expansion failed for '" + pattern + "': " + e.getMessage());
                }
            }
        }
        return result;
    }

    /**
     * Auto-discovers OpenAPI/Swagger spec files in the repo by scanning well-known locations
     * and file naming conventions when no explicit {@code openapi.spec.paths} is configured.
     *
     * <p>Recognized patterns:
     * <ul>
     *   <li>Any {@code .yml} / {@code .yaml} file whose name or content signals OpenAPI:
     *       name contains {@code openapi}, {@code swagger}, {@code api-spec}, {@code apispec},
     *       or the file content starts with {@code openapi:} / {@code swagger:}.</li>
     *   <li>Common locations searched first: {@code apispec/}, {@code src/main/resources/},
     *       {@code swagger/}, {@code openapi/}, {@code api/}, {@code docs/}.</li>
     * </ul>
     * Stops at 20 files to avoid runaway scanning on large repos.
     */
    private List<Path> autoDiscoverOpenApiSpecs() {
        List<Path> found = new ArrayList<>();
        // Well-known directories to probe first (short-circuit for typical layouts)
        List<String> preferredDirs = List.of(
            "apispec", "openapi", "swagger", "api", "api-spec",
            "src/main/resources", "src/main/resources/openapi",
            "src/main/resources/swagger", "src/main/resources/static",
            "docs", "spec", "specs"
        );
        Set<Path> visited = new LinkedHashSet<>();
        // First pass: preferred directories
        for (String dir : preferredDirs) {
            Path candidate = repoRoot.resolve(dir);
            if (!Files.isDirectory(candidate)) continue;
            try (Stream<Path> stream = Files.walk(candidate, 3)) {
                stream.filter(Files::isRegularFile)
                      .filter(p -> isOpenApiFile(p))
                      .forEach(p -> { if (visited.add(p)) found.add(p); });
            } catch (IOException ignored) {}
            if (found.size() >= 20) break;
        }
        // Second pass: broad repo scan if nothing found yet (cap at 20 matches)
        if (found.isEmpty()) {
            try (Stream<Path> walk = Files.walk(repoRoot, 8)) {
                walk.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().replace('\\', '/').contains("/target/"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/build/"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/.git/"))
                    .filter(p -> isOpenApiFile(p))
                    .limit(20)
                    .forEach(p -> { if (visited.add(p)) found.add(p); });
            } catch (IOException ignored) {}
        }
        if (!found.isEmpty()) {
            debug("OpenAPI auto-discovered " + found.size() + " spec file(s): " + found);
        } else {
            debug("OpenAPI auto-discovery: no spec files found");
        }
        return found;
    }

    /**
     * Returns true if {@code path} is a YAML file that looks like an OpenAPI/Swagger spec.
     * Checks filename first; if inconclusive, peeks at the first 512 bytes of content.
     */
    private static boolean isOpenApiFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (!name.endsWith(".yml") && !name.endsWith(".yaml")) return false;
        // Filename signals
        if (name.contains("openapi") || name.contains("swagger") ||
            name.contains("api-spec") || name.contains("apispec") ||
            name.contains("api-config") || name.contains("apiconfig")) {
            return true;
        }
        // Content peek — look for top-level 'openapi:' or 'swagger:' key
        try {
            byte[] buf = new byte[512];
            int read;
            try (java.io.InputStream is = Files.newInputStream(path)) {
                read = is.read(buf);
            }
            if (read > 0) {
                String head = new String(buf, 0, read, java.nio.charset.StandardCharsets.UTF_8);
                if (head.contains("openapi:") || head.contains("swagger:") || head.contains("paths:")) {
                    return true;
                }
            }
        } catch (IOException ignored) {}
        return false;
    }

    /**
     * Returns true if {@code content} declares that the class implements one or more
     * interfaces whose name ends with {@code delegateSuffix} (e.g. "ApiDelegate").
     * Checks both the implements clause and class-level @Component/@Service annotations
     * to handle generated Spring delegate wiring.
     */
    private static boolean implementsApiDelegate(String content, String delegateSuffix) {
        if (content == null || content.isBlank() || delegateSuffix == null || delegateSuffix.isBlank()) return false;
        Pattern p = Pattern.compile("\\bimplements\\b[^{]*\\b\\w+" + Pattern.quote(delegateSuffix) + "\\b");
        return p.matcher(content).find();
    }

    /**
     * Extracts the simple names of all *ApiDelegate interfaces that {@code content} implements.
     * Example: "class Foo implements ProcessAffiliateLeadApiDelegate, OtherApiDelegate"
     * → ["ProcessAffiliateLeadApiDelegate", "OtherApiDelegate"]
     */
    private static List<String> extractDelegateInterfaces(String content, String delegateSuffix) {
        List<String> delegates = new ArrayList<>();
        if (content == null || content.isBlank() || delegateSuffix == null || delegateSuffix.isBlank()) return delegates;
        Pattern implClause = Pattern.compile("\\bimplements\\b([^{]+)");
        Matcher m = implClause.matcher(content);
        if (!m.find()) return delegates;
        String[] tokens = m.group(1).split("[,\\s]+");
        for (String token : tokens) {
            String t = token.trim().replaceAll("<.*>", "");
            if (t.endsWith(delegateSuffix)) delegates.add(t);
        }
        return delegates;
    }

    /**
     * Resolves OpenAPI endpoints for a delegate implementation class in two modes:
     *
     * <p><b>Direct mode</b> (changed file is itself the delegate impl): match {@code touchedMethods}
     * directly against operationIds — the developer changed the operationId method body itself.
     *
     * <p><b>Transitive mode</b> (delegate is a dependent that was reached via call graph):
     * {@code touchedMethods} / {@code callingMethods} are <em>internal</em> methods of the delegate
     * class (e.g. {@code findAffiliateHandlerUsingTokenAndProcessRequest}, {@code processLTFlow})
     * and will never match an operationId. Instead, scan the delegate class {@code content} for
     * method declarations whose names are known operationIds in the spec — those are the true HTTP
     * entry points regardless of which internal method was called transitively.
     *
     * <p>Both modes are tried; results are de-duplicated.
     */
    private List<String> resolveOpenApiEndpoints(List<String> delegateInterfaces,
                                                  List<String> touchedMethods,
                                                  Map<String, String> opMap,
                                                  String className,
                                                  String classContent) {
        List<String> endpoints = new ArrayList<>();
        if (delegateInterfaces.isEmpty() || opMap.isEmpty()) return endpoints;

        // Mode 1: direct — touched method name IS the operationId (changed the delegate method itself)
        for (String method : touchedMethods) {
            String pure = method.split("\\(")[0].trim();
            if (opMap.containsKey(pure)) {
                String httpPathEntry = opMap.get(pure);
                String ep = className + "." + pure + " [" + httpPathEntry + "]";
                if (!endpoints.contains(ep)) endpoints.add(ep);
                debug("OpenAPI delegate match (direct): " + pure + " → " + httpPathEntry + " in " + className);
            }
        }

        // Mode 2: transitive — scan class content for method declarations matching operationIds.
        // Used when callingMethods are internal helpers; the OpenAPI entry point method is
        // declared in the same class and matches an operationId in the spec.
        if (classContent != null && !classContent.isBlank()) {
            // Match method declarations: (public|protected|...) <returnType> <methodName>(
            Pattern methodDecl = Pattern.compile(
                "(?:^|\\n)\\s*(?:@Override\\s+)?(?:public|protected|private)?\\s+[\\w<>\\[\\],\\s]+\\s+(\\w+)\\s*\\(",
                Pattern.MULTILINE);
            Matcher m = methodDecl.matcher(classContent);
            while (m.find()) {
                String declaredMethod = m.group(1);
                if (opMap.containsKey(declaredMethod)) {
                    String httpPathEntry = opMap.get(declaredMethod);
                    String ep = className + "." + declaredMethod + " [" + httpPathEntry + "]";
                    if (!endpoints.contains(ep)) {
                        endpoints.add(ep);
                        debug("OpenAPI delegate match (declared): " + declaredMethod + " → " + httpPathEntry + " in " + className);
                    }
                }
            }
        }

        return endpoints;
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
                    boolean multiTouched = touchedMethods.size() > 1;
                    if (multiTouched) {
                        for (String tm : touchedMethods) {
                            // Expand to include annotated handlers that delegate to this touched method.
                            List<String> tmScope = ImpactAnalyzer.expandWithIntraClassCallers(content, Collections.singletonList(tm));
                            List<String> eps = null;
                            if (com.reviewer.analysis.TreeSitterAnalyzer.isAvailable()) {
                                eps = com.reviewer.analysis.TreeSitterAnalyzer.extractImpactedEndpoints(content, className, tmScope);
                                debug("Controller self endpoints (tree-sitter) for " + tm + ": " + eps);
                            }
                            if (eps == null || eps.isEmpty()) {
                                eps = ImpactAnalyzer.extractControllerEndpoints(content, className, tmScope);
                                debug("Controller self endpoints (regex) for " + tm + ": " + eps);
                            }
                            if (eps != null) {
                                for (String ep : eps) {
                                    entry.endpoints.add(ep + " [via " + tm + "()]");
                                }
                            }
                        }
                    } else {
                        // Expand to include annotated handlers that delegate to any touched method.
                        List<String> tmScope = ImpactAnalyzer.expandWithIntraClassCallers(content, touchedMethods);
                        List<String> endpoints = null;
                        if (com.reviewer.analysis.TreeSitterAnalyzer.isAvailable()) {
                            endpoints = com.reviewer.analysis.TreeSitterAnalyzer.extractImpactedEndpoints(content, className, tmScope);
                            debug("Controller self endpoints (tree-sitter): " + endpoints);
                        }
                        if (endpoints == null || endpoints.isEmpty()) {
                            endpoints = ImpactAnalyzer.extractControllerEndpoints(content, className, tmScope);
                            debug("Controller self endpoints (regex): " + endpoints);
                        }
                        if (endpoints != null) {
                            entry.endpoints.addAll(endpoints);
                        }
                    }
                }

                // OpenAPI delegate detection: changed file itself implements *ApiDelegate
                if (!touchedMethods.isEmpty() && implementsApiDelegate(content, config.openApiDelegateSuffix)) {
                    Map<String, String> opMap = getOpenApiOperationMap();
                    List<String> delegateInterfaces = extractDelegateInterfaces(content, config.openApiDelegateSuffix);
                    debug("OpenAPI delegate interfaces in " + className + ": " + delegateInterfaces);
                    // Direct mode: developer changed this file, so touched methods may be operationId methods directly.
                    // Also scan full content in case they changed an internal helper — surface all exposed operationIds.
                    List<String> openApiEndpoints = resolveOpenApiEndpoints(delegateInterfaces, touchedMethods, opMap, className, content);
                    if (!openApiEndpoints.isEmpty()) {
                        if (!entry.layers.contains("API/Web")) entry.layers.add("API/Web");
                        entry.endpoints.addAll(openApiEndpoints);
                        debug("OpenAPI endpoints added for " + className + ": " + openApiEndpoints);
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

                    // 1. Identify WHICH methods in the dependent call the service, attributed per
                    //    touched method so notes and endpoints carry [via tm()] when >1 method changed.
                    Map<String, List<String>> callerToVia = new LinkedHashMap<>();
                    for (String tm : touchedMethods) {
                        List<String> callers = ImpactAnalyzer.getMethodsCalling(
                                depContent, classInfo.simpleName, classInfo.fqn,
                                classInfo.supertypeSimpleNames,
                                Collections.singletonList(tm), true);
                        for (String caller : callers) {
                            callerToVia.computeIfAbsent(caller, k -> new ArrayList<>()).add(tm);
                        }
                    }
                    List<String> callingMethods = new ArrayList<>(callerToVia.keySet());
                    debug("Calling methods in " + depFileName + " -> " + callingMethods);

                    if (!callingMethods.isEmpty()) {
                        // Track method-scoped dependents for the graph display.
                        entry.methodScopedDependents.add(dependentFile);
                        String depType = classifyDependencyType(depContent, classInfo);
                        boolean multiTouched = touchedMethods.size() > 1;
                        for (Map.Entry<String, List<String>> e : callerToVia.entrySet()) {
                            String via = multiTouched
                                    ? " [via " + e.getValue().stream().map(m -> m + "()").collect(Collectors.joining(", ")) + "]"
                                    : "";
                            entry.notes.add("Impacted Method [" + depType + "]: " + depFileName + " -> " + e.getKey() + "()" + via);
                        }

                        if (depContent.contains("@RestController") || depContent.contains("@Controller")) {
                            if (!entry.layers.contains("API/Web")) entry.layers.add("API/Web");
                            if (multiTouched) {
                                // Extract endpoints per caller so each carries [via tm()] attribution.
                                // Expand each caller to include annotated handlers that delegate to it.
                                for (Map.Entry<String, List<String>> e : callerToVia.entrySet()) {
                                    List<String> callerScope = ImpactAnalyzer.expandWithIntraClassCallers(depContent, Collections.singletonList(e.getKey()));
                                    List<String> eps = null;
                                    if (config.enableStructuralImpact && com.reviewer.analysis.TreeSitterAnalyzer.isAvailable()) {
                                        eps = com.reviewer.analysis.TreeSitterAnalyzer.extractImpactedEndpoints(
                                                depContent, depClassName, callerScope);
                                        debug("Tree-sitter endpoints for " + depFileName + " caller " + e.getKey() + ": " + eps);
                                    }
                                    if (eps == null || eps.isEmpty()) {
                                        eps = ImpactAnalyzer.extractControllerEndpoints(
                                                depContent, depClassName, callerScope);
                                        debug("Regex endpoints for " + depFileName + " caller " + e.getKey() + ": " + eps);
                                    }
                                    if (eps != null) {
                                        String via = " [via " + e.getValue().stream().map(m -> m + "()").collect(Collectors.joining(", ")) + "]";
                                        for (String ep : eps) {
                                            entry.endpoints.add(ep + via);
                                        }
                                    }
                                }
                            } else {
                                // Expand callingMethods to include annotated handlers that delegate to them.
                                List<String> callerScope = ImpactAnalyzer.expandWithIntraClassCallers(depContent, callingMethods);
                                List<String> endpoints = null;
                                // TreeSitter needs the Controller's own methods to find mapping annotations
                                if (config.enableStructuralImpact && com.reviewer.analysis.TreeSitterAnalyzer.isAvailable()) {
                                    endpoints = com.reviewer.analysis.TreeSitterAnalyzer.extractImpactedEndpoints(depContent, depClassName, callerScope);
                                    debug("Tree-sitter endpoints for " + depFileName + ": " + endpoints);
                                }
                                // Regex fallback needs the CONTROLLER methods to find the impacted endpoints
                                if (endpoints == null || endpoints.isEmpty()) {
                                    endpoints = ImpactAnalyzer.extractControllerEndpoints(depContent, depClassName, callerScope);
                                    debug("Regex endpoints for " + depFileName + ": " + endpoints);
                                }
                                if (endpoints != null) {
                                    entry.endpoints.addAll(endpoints);
                                }
                            }
                        } else if (implementsApiDelegate(depContent, config.openApiDelegateSuffix)) {
                            // Dependent is an OpenAPI delegate impl — scan its content for declared operationId methods.
                            // callingMethods here are internal helpers (e.g. findAffiliateHandlerUsingTokenAndProcessRequest),
                            // NOT the operationId entry points. Scan the class to find which operationIds it declares.
                            Map<String, String> opMap = getOpenApiOperationMap();
                            List<String> depDelegateInterfaces = extractDelegateInterfaces(depContent, config.openApiDelegateSuffix);
                            debug("OpenAPI delegate interfaces in dependent " + depClassName + ": " + depDelegateInterfaces);
                            // Use content-scanning overload so internal callingMethods also trigger operationId scan
                            List<String> openApiEps = resolveOpenApiEndpoints(depDelegateInterfaces, callingMethods, opMap, depClassName, depContent);
                            if (!openApiEps.isEmpty()) {
                                if (!entry.layers.contains("API/Web")) entry.layers.add("API/Web");
                                if (multiTouched) {
                                    String via = " [via " + touchedMethods.stream().map(m -> m + "()").collect(Collectors.joining(", ")) + "]";
                                    for (String ep : openApiEps) entry.endpoints.add(ep + via);
                                } else {
                                    entry.endpoints.addAll(openApiEps);
                                }
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
                    Map<String, List<String>> endpointsByMethod = discoverTransitiveControllerEndpointsByMethod(
                            classInfo,
                            touchedMethods,
                            config.transitiveApiDiscoveryMaxDepth,
                            config.transitiveApiDiscoveryMaxVisitedFiles,
                            config.transitiveApiDiscoveryMaxControllers
                    );
                    if (!endpointsByMethod.isEmpty()) {
                        if (!entry.layers.contains("API/Web")) entry.layers.add("API/Web");
                        for (Map.Entry<String, List<String>> methodEntry : endpointsByMethod.entrySet()) {
                            String touchedMethod = methodEntry.getKey();
                            List<String> endpoints = methodEntry.getValue();
                            entry.endpointsByMethod.computeIfAbsent(touchedMethod, k -> new ArrayList<>()).addAll(endpoints);
                            for (String ep : endpoints) {
                                if (!existingEndpoints.contains(ep)) {
                                    entry.endpoints.add(ep);
                                }
                            }
                        }
                    }
                }
                if (entry.hasSignal()) impact.add(entry);
            } catch (IOException ignored) {}
        }
        return impact;
    }

    private Map<String, List<String>> discoverTransitiveControllerEndpointsByMethod(JavaSymbolIndex.ClassInfo startClass, List<String> initialTouchedMethods, int maxDepth, int maxVisitedFiles, int maxControllers) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (String touchedMethod : initialTouchedMethods) {
            List<String> endpoints = discoverTransitiveControllerEndpoints(startClass, Collections.singletonList(touchedMethod), maxDepth, maxVisitedFiles, maxControllers);
            if (!endpoints.isEmpty()) {
                result.put(touchedMethod, endpoints);
            }
        }
        return result;
    }

    private List<String> discoverTransitiveControllerEndpoints(JavaSymbolIndex.ClassInfo startClass, List<String> initialTouchedMethods, int maxDepth, int maxVisitedFiles, int maxControllers) {
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

        queue.add(new TransitiveNode(startClass.fqn, ImpactAnalyzer.filterValidMethodNames(initialTouchedMethods), 0, startClass.supertypeSimpleNames));
        visitedFqns.add(startClass.fqn);

        int visitedFiles = 0;
        int foundControllers = 0;

        while (!queue.isEmpty() && visitedFiles < maxVisitedFiles && foundControllers < maxControllers) {
            TransitiveNode node = queue.poll();
            if (node.depth >= maxDepth) continue;

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
                boolean isApiDelegate = implementsApiDelegate(depContent, config.openApiDelegateSuffix);
                // Only pure @RestController/@Controller is a terminal node for call-matching purposes.
                // OpenAPI delegates are BOTH endpoint sources AND intermediate nodes: they emit OpenAPI
                // endpoints AND get re-enqueued so @RestController callers above them are also found.
                // So for getMethodsCalling / intra-class expansion / visitKey, treat delegates like
                // intermediate nodes (confirmedDependent=true, expand callers, file-based visitKey).
                String currentSimpleName = simpleNameFromFqn(node.fqn);
                debug("Transitive: checking " + depFileName + " for calls to " + currentSimpleName + "." + node.impactedMethods);
                List<String> callingMethods = ImpactAnalyzer.getMethodsCalling(depContent, currentSimpleName, node.fqn, node.supertypeSimpleNames, node.impactedMethods, false, !isController);
                callingMethods = ImpactAnalyzer.filterValidMethodNames(callingMethods);
                debug("Transitive: found calling methods in " + depFileName + ": " + callingMethods);

                // For non-controller nodes (including OpenAPI delegates), expand callingMethods to
                // include any method in the same class that delegates to one of the found callers.
                // This resolves the common pattern: privateHelper() calls external.touchedMethod();
                // publicApi() calls privateHelper() — without expansion the BFS only tracks
                // privateHelper, which no upstream file calls, so the chain would die here.
                if (!isController && !callingMethods.isEmpty()) {
                    List<String> expanded = ImpactAnalyzer.expandWithIntraClassCallers(depContent, callingMethods);
                    expanded = ImpactAnalyzer.filterValidMethodNames(expanded);
                    debug("Transitive: intra-class expansion in " + depFileName + ": " + callingMethods + " -> " + expanded);
                    callingMethods = expanded;
                }

                if (callingMethods.isEmpty()) {
                    continue;
                }

                // Mark visited only when we have a proven call-chain.
                // For @RestController, use (fqn + methods) key so we can revisit with different calling methods.
                // For delegates and intermediate nodes use file path — they will be re-enqueued via visitedFqns key.
                String visitKey = isController ? (depInfo.fqn + ":" + callingMethods.toString()) : dependentFile;
                if (!visitedPaths.add(visitKey)) {
                    debug("Transitive: skipping already-processed " + depFileName + " with methods " + callingMethods);
                    continue;
                }

                visitedFiles++;

                if (isController) {
                    foundControllers++;
                    // Expand callingMethods to include annotated endpoint handlers that delegate
                    // to the found callers (e.g. @PostMapping createOrder() → private doCreate()
                    // → service.touchedMethod()). Without expansion, doCreate has no annotation
                    // and extractControllerEndpoints returns empty; createOrder would be missed.
                    List<String> callerScope = ImpactAnalyzer.expandWithIntraClassCallers(depContent, callingMethods);
                    List<String> controllerEndpoints = null;
                    if (config.enableStructuralImpact && com.reviewer.analysis.TreeSitterAnalyzer.isAvailable()) {
                        controllerEndpoints = com.reviewer.analysis.TreeSitterAnalyzer.extractImpactedEndpoints(depContent, depInfo.simpleName, callerScope);
                    }
                    if (controllerEndpoints == null || controllerEndpoints.isEmpty()) {
                        controllerEndpoints = ImpactAnalyzer.extractControllerEndpoints(depContent, depInfo.simpleName, callerScope);
                    }
                    if (controllerEndpoints != null) endpoints.addAll(controllerEndpoints);
                } else if (isApiDelegate) {
                    // Emit OpenAPI endpoints for this delegate — it IS an API entry point.
                    Map<String, String> opMap = getOpenApiOperationMap();
                    List<String> depDelegateInterfaces = extractDelegateInterfaces(depContent, config.openApiDelegateSuffix);
                    debug("Transitive: OpenAPI delegate " + depFileName + " interfaces=" + depDelegateInterfaces + " callingMethods=" + callingMethods);
                    // callingMethods are internal methods of the delegate (e.g. findAffiliateHandlerUsingTokenAndProcessRequest).
                    // Scan the delegate class content to find the operationId method(s) it declares — those are the real entry points.
                    List<String> openApiEps = resolveOpenApiEndpoints(depDelegateInterfaces, callingMethods, opMap, depInfo.simpleName, depContent);
                    endpoints.addAll(openApiEps);
                    // Also continue BFS traversal: the delegate can itself be called by @RestController
                    // methods (e.g. AffiliateController.processLTV2 → AffiliateRequestHandler.processLTFlow
                    // → GroupThreeFlow.processLead). Stopping here would miss those controller endpoints.
                    // Expand callingMethods with intra-class callers so the BFS can traverse upward through
                    // the delegate's public entry points (including the operationId methods themselves).
                    List<String> expandedForBfs = ImpactAnalyzer.expandWithIntraClassCallers(depContent, callingMethods);
                    expandedForBfs = ImpactAnalyzer.filterValidMethodNames(expandedForBfs);
                    List<String> sortedMethods = new java.util.ArrayList<>(expandedForBfs);
                    java.util.Collections.sort(sortedMethods);
                    String fqnMethodsKey = depInfo.fqn + "|" + sortedMethods;
                    if (visitedFqns.add(fqnMethodsKey)) {
                        debug("Transitive: enqueuing OpenAPI delegate " + depFileName + " for further BFS with methods " + expandedForBfs);
                        queue.add(new TransitiveNode(depInfo.fqn, expandedForBfs, node.depth + 1, depInfo.supertypeSimpleNames));
                    }
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
                    queue.add(new TransitiveNode(depInfo.fqn, callingMethods, node.depth + 1, depInfo.supertypeSimpleNames));
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
        TransitiveNode(String fqn, List<String> impactedMethods, int depth, List<String> supertypeSimpleNames) {
            this.fqn = fqn;
            this.impactedMethods = impactedMethods;
            this.depth = depth;
            this.supertypeSimpleNames = supertypeSimpleNames != null ? supertypeSimpleNames : Collections.emptyList();
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
