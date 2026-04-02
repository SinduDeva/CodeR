package com.reviewer.analysis;

import com.reviewer.model.Models.*;
import java.util.regex.*;
import java.util.*;

public class RuleEngine {

    // -----------------------------------------------------------------------
    // Static pattern cache — compiled once per JVM instead of once per file
    // -----------------------------------------------------------------------
    // reviewBugPatterns
    private static final Pattern P_RESOURCE_OPEN   = Pattern.compile("(?:InputStream|OutputStream|Reader|Writer|Connection|Statement|ResultSet|Socket)\\s+(\\w+)\\s*=\\s*(?:new|[^;]+?\\.get\\w+)\\(", Pattern.MULTILINE);
    private static final Pattern P_BIGDECIMAL_EQ   = Pattern.compile("(\\w+)\\.equals\\((\\w+)\\)");
    private static final Pattern P_BIGDECIMAL_CTOR = Pattern.compile("new\\s+BigDecimal\\(\\s*(\\d+(?:\\.\\d+)?)\\s*\\)");
    private static final Pattern P_OPTIONAL_NULL   = Pattern.compile("Optional<[^>]+>\\s+\\w+\\s*\\([^)]{0,200}\\)\\s*\\{[^}]{0,300}return\\s+null;");
    private static final Pattern P_FOREACH_COLL    = Pattern.compile("for\\s*\\(.*?:\\s*(\\w+)\\s*\\)");
    private static final Pattern P_NEW_STREAM      = Pattern.compile("new\\s+(FileInputStream|FileOutputStream|FileReader|FileWriter|BufferedReader|BufferedWriter|InputStreamReader|OutputStreamWriter|Scanner|PrintWriter|ZipInputStream|ZipOutputStream)\\s*\\(");
    // reviewNullSafety
    private static final Pattern P_OPTIONAL_OF     = Pattern.compile("Optional\\.of\\(([^)]+)\\)");
    private static final Pattern P_CHAINED_DEREF   = Pattern.compile("\\w+\\.\\w+\\(\\)\\.\\w+\\(");
    private static final Pattern P_LIST_GET        = Pattern.compile("\\.get\\((\\d+)\\)");
    private static final Pattern P_OPT_GET         = Pattern.compile("(\\w+)\\.get\\(\\)");
    // reviewExceptionHandling
    private static final Pattern P_EMPTY_CATCH     = Pattern.compile("catch\\s*\\(\\s*(\\w+)\\s+(\\w+)\\s*\\)\\s*\\{\\s*\\}");
    private static final Pattern P_CATCH_THROWABLE = Pattern.compile("catch\\s*\\(\\s*Throwable\\s+\\w+\\s*\\)");
    private static final Pattern P_CATCH_EXCEPTION = Pattern.compile("catch\\s*\\(\\s*Exception\\s+\\w+\\s*\\)");
    private static final Pattern P_CATCH_INTERRUPT = Pattern.compile("catch\\s*\\(\\s*InterruptedException\\s+\\w+\\s*\\)");
    // reviewLogging
    private static final Pattern P_LOG_SENSITIVE   = Pattern.compile("log\\.(info|debug|error|warn)\\(\".*?(password|secret|token|apiKey|ssn).*?\"\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_LOG_IN_LOOP     = Pattern.compile("for\\s*\\(.*\\)\\s*\\{[^}]{0,200}?log\\.(info|debug|error|warn)\\(", Pattern.DOTALL);
    private static final Pattern P_SYS_OUT         = Pattern.compile("System\\.(out|err)\\.print");
    private static final Pattern P_LOG_CALLS       = Pattern.compile("log\\.(info|debug|error|warn)\\((.*?)\\);", Pattern.DOTALL);
    // reviewSpringBoot
    private static final Pattern P_TX_PRIVATE      = Pattern.compile("@Transactional[^;{}]*private\\s+\\w+\\s+\\w+", Pattern.DOTALL);
    private static final Pattern P_REQUEST_BODY    = Pattern.compile("@RequestBody");
    private static final Pattern P_FIELD_INJECT    = Pattern.compile("@Autowired\\s+private\\s+\\w+");
    private static final Pattern P_HARDCODED_URL   = Pattern.compile("\"https?://[^\"]+\"");
    private static final Pattern P_REPO_FIND       = Pattern.compile("for\\s*\\(.*\\)\\s*\\{[^}]{0,200}?\\.find(All|By|One)\\(", Pattern.DOTALL);
    private static final Pattern P_SCHED_FIXED     = Pattern.compile("@Scheduled\\([^)]*(fixedRate|fixedDelay)\\s*=\\s*(\\d+)");
    private static final Pattern P_CROSS_WILD      = Pattern.compile("@CrossOrigin\\s*\\([^)]*(?:origins|value)\\s*=\\s*(?:\"\\*\"|\\{\\s*\"\\*\"\\s*\\})");
    private static final Pattern P_CROSS_BARE      = Pattern.compile("@CrossOrigin\\s*(?:(?=\\n|\\r|\\s*$)|(?=\\s+[^(]))");
    private static final Pattern P_ASYNC_PRIVATE   = Pattern.compile("@Async[^;{}\\n]*\\n(?:\\s*@[^\\n]*\\n)*\\s*private\\s+\\w+\\s+\\w+\\s*\\(", Pattern.DOTALL);
    private static final Pattern P_LIFECYCLE_STAT  = Pattern.compile("@(?:PostConstruct|PreDestroy)[^;{}\\n]*\\n(?:\\s*@[^\\n]*\\n)*\\s*(?:public|protected|private)\\s+static\\s+", Pattern.DOTALL);
    private static final Pattern P_RESP_WILDCARD   = Pattern.compile("ResponseEntity<\\?>\\s+\\w+\\s*\\(");
    private static final Pattern P_SENSITIVE_FIELD = Pattern.compile("(?:private|protected)\\s+(?:String|char\\[\\])\\s+(password|secret|token|apiKey|apiSecret|creditCard|cvv|ssn)\\b");
    private static final Pattern P_SELF_INVOKE     = Pattern.compile("\\bthis\\.(\\w+)\\s*\\(");
    private static final Pattern P_CACHE_PRIVATE   = Pattern.compile("@Cacheable[^;{}\\n]*\\n(?:\\s*@[^\\n]*\\n)*\\s*private\\s+\\w+\\s+\\w+\\s*\\(", Pattern.DOTALL);
    private static final Pattern P_CACHEABLE       = Pattern.compile("@Cacheable\\(([^)]*)\\)");
    private static final Pattern P_REST_TPL_NEW    = Pattern.compile("new\\s+RestTemplate\\s*\\(");
    private static final Pattern P_VALUE_NO_DEF    = Pattern.compile("@Value\\(\\\"\\$\\{([^:}]+)\\}\\\"\\)");
    private static final Pattern P_VALUE_SECRET    = Pattern.compile("@Value\\(\\\"\\$\\{[^}]*?(password|secret|token)[^}]*\\}\\\"\\)", Pattern.CASE_INSENSITIVE);
    // reviewPerformance
    private static final Pattern P_OR_ELSE         = Pattern.compile("\\.orElse\\s*\\(\\s*(\\w+\\(.*?\\))\\s*\\)");
    private static final Pattern P_CONCAT_LOOP     = Pattern.compile("for\\s*\\(.*\\)\\s*\\{[^}]{0,200}?\\+=\\s*\"", Pattern.DOTALL);
    private static final Pattern P_THREAD_SLEEP    = Pattern.compile("Thread\\.sleep\\(\\s*\\d+\\s*\\)");
    private static final Pattern P_SLEEP_LITERAL   = Pattern.compile("Thread\\.sleep\\(\\s*(\\d+)\\s*\\)");
    // reviewCodeQuality
    private static final Pattern P_BOXED_CMP       = Pattern.compile("\\b(Integer|Long|Boolean)\\b[^\\n]*?(?:==|!=)[^\\n]*");
    private static final Pattern P_HARDCODED_CRED  = Pattern.compile("\"(?i)(password|passwd|secretKey|apiKey|token)=.+\"");
    private static final Pattern P_TODO_FIXME      = Pattern.compile("//\\s*(TODO|FIXME)");
    private static final Pattern P_WHILE_TRUE      = Pattern.compile("while\\s*\\(\\s*true\\s*\\)\\s*\\{");
    private static final Pattern P_DEEP_NESTING    = Pattern.compile("(?:if|for|while)\\s*\\([^)]*\\)\\s*\\{[^{}]*\\{[^{}]*\\{[^{}]*\\{[^{}]*\\{", Pattern.DOTALL);
    private static final Pattern P_SIMPLE_LITERAL  = Pattern.compile("\"([A-Za-z][A-Za-z0-9_-]{2,})\"");
    private static final Pattern P_NUMBER_LITERAL  = Pattern.compile("(?<!\")(?<!\\.)\\b(\\d{2,})\\b(?!\")(?!\\s*[,)]\\s*(?:TimeUnit|SECONDS|MINUTES|HOURS|MILLISECONDS))");
    private static final Pattern P_DOMAIN_LITERAL  = Pattern.compile("\"([A-Z][A-Z0-9_]{1,})\"");
    private static final Pattern P_LIT_EQUALS      = Pattern.compile("([A-Za-z0-9_\\.\\(\\)]+)\\.(equals(?:IgnoreCase)?)\\(\\s*\"([^\"]+)\"\\s*\\)");
    private static final Pattern P_LITERAL_CONST   = Pattern.compile("\"([A-Z0-9_]{3,})\"");
    private static final Pattern P_STRING_EQ       = Pattern.compile("(\\w+)\\s*(==|!=)\\s*(\"[^\"]*\")|(\"[^\"]*\")\\s*(==|!=)\\s*(\\w+)");
    // reviewJavaModern
    private static final Pattern P_LEGACY_DATE     = Pattern.compile("(?:new\\s+(?:java\\.util\\.)?(?:Date|GregorianCalendar)\\s*\\(|Calendar\\.getInstance\\s*\\(|new\\s+SimpleDateFormat\\s*\\()");
    private static final Pattern P_RAW_COLL        = Pattern.compile("new\\s+(ArrayList|HashMap|HashSet|LinkedList|TreeMap|TreeSet|LinkedHashMap|LinkedHashSet|PriorityQueue|ArrayDeque)\\s*(?!\\s*<)\\s*\\(");
    private static final Pattern P_EMPTY_COLLS     = Pattern.compile("Collections\\.(EMPTY_LIST|EMPTY_SET|EMPTY_MAP)\\b");
    private static final Pattern P_DOUBLE_BRACE    = Pattern.compile("new\\s+\\w+(?:<[^>]*>)?\\s*\\(\\s*\\)\\s*\\{\\s*\\{");
    private static final Pattern P_MATH_RANDOM     = Pattern.compile("Math\\.random\\(\\)");
    private static final Pattern P_INSTANCEOF_CAST = Pattern.compile("instanceof\\s+(\\w+)\\b[^;{]*\\n[^;{]*\\(\\s*\\1\\s*\\)");
    // reviewSecurity (reuse mapping pattern, listed below)
    // reviewOpenApi
    private static final Pattern P_MAPPING_ANNO    = Pattern.compile("@(?:GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)\\b");
    private static final Pattern P_METHOD_PARAM    = Pattern.compile("(?:@RequestParam|@PathVariable|@RequestHeader)\\s+(?:[\\w<>\\[\\]]+)\\s+(\\w+)");
    // reviewScheduled
    private static final Pattern P_SCHED_HEADER    = Pattern.compile("@Scheduled\\b[^\\n]*\\n");
    // reviewSoap
    private static final Pattern P_WEBMETHOD_NO_ACTION = Pattern.compile("@WebMethod\\s*(?:\\((?![^)]*action\\s*=)[^)]*\\)|(?![\\s(]))");
    private static final Pattern P_WEBPARAM_NO_NAME    = Pattern.compile("@WebParam\\s*(?:\\((?![^)]*name\\s*=)[^)]*\\)|(?![\\s(]))");
    private static final Pattern P_WEBSERVICE_NO_NS    = Pattern.compile("@WebService\\s*(?:\\((?![^)]*targetNamespace\\s*=)[^)]*\\)|(?![\\s(]))");
    // reviewGrpc
    private static final Pattern P_GRPC_NO_DEADLINE   = Pattern.compile("(?:newBlockingStub|newFutureStub|newStub)\\s*\\([^)]+\\)(?!\\.withDeadline)");
    private static final Pattern P_GRPC_NO_INTERCEPT   = Pattern.compile("ManagedChannelBuilder\\.forAddress\\s*\\([^)]+\\)(?!.*intercept)");
    // reviewOutboundClients
    private static final Pattern P_WEBCLIENT_NO_TIMEOUT = Pattern.compile("WebClient\\.builder\\s*\\(\\s*\\)(?!.*responseTimeout)");
    private static final Pattern P_FEIGN_NO_FALLBACK    = Pattern.compile("@FeignClient\\s*\\([^)]*\\)");
    private static final Pattern P_JAXB_NO_TRY          = Pattern.compile("JAXBContext\\.newInstance\\s*\\([^)]*\\)");
    private static final Pattern P_RESTTPL_NO_TIMEOUT   = Pattern.compile("new\\s+RestTemplate\\s*\\(\\s*\\)");
    private static final Pattern P_WEBCLIENT_BLOCK       = Pattern.compile("\\.block\\s*\\(\\s*\\)");
    // reviewQuartz
    private static final Pattern P_QUARTZ_NO_MISFIRE    = Pattern.compile("(?:SimpleScheduleBuilder|CronScheduleBuilder)\\.(?:simpleSchedule|cronSchedule)\\s*\\([^)]*\\)(?!.*withMisfireHandling)");
    // reviewSecurity
    private static final Pattern P_SQL_CONCAT            = Pattern.compile("\"\\s*\\+\\s*(?!\")");
    private static final Pattern P_PATH_TRAVERSAL        = Pattern.compile("(?:new\\s+File|Paths\\.get|new\\s+FileInputStream)\\s*\\(\\s*(?!\")");
    private static final Pattern P_PREAUTHORIZE          = Pattern.compile("@(?:GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)\\b");
    // gap rules: security
    private static final Pattern P_XXE_PARSER           = Pattern.compile("(?:newDocumentBuilder|newSAXParser|newTransformer|XMLInputFactory\\.newInstance)\\s*\\(");
    private static final Pattern P_UNSAFE_DESER         = Pattern.compile("\\.readObject\\s*\\(\\s*\\)");
    private static final Pattern P_INSECURE_RANDOM       = Pattern.compile("new\\s+Random\\s*\\(\\s*\\)");
    // gap rules: concurrency
    private static final Pattern P_LOCK_NO_FINALLY      = Pattern.compile("\\.(lock|lockInterruptibly)\\s*\\(\\s*\\)");
    private static final Pattern P_EXECUTOR_NO_SHUTDOWN = Pattern.compile("Executors\\.(?:newFixedThreadPool|newCachedThreadPool|newSingleThreadExecutor|newScheduledThreadPool|newWorkStealingPool)\\s*\\(");
    private static final Pattern P_DOUBLE_CHECK_LOCK    = Pattern.compile("if\\s*\\(\\s*(\\w+)\\s*==\\s*null\\s*\\)[^}]+synchronized[^}]+if\\s*\\(\\s*\\1\\s*==\\s*null", Pattern.DOTALL);
    // gap rules: bug patterns
    private static final Pattern P_FLOAT_EQ             = Pattern.compile("\\b(\\w+)\\s*==\\s*(\\w+)\\b");
    private static final Pattern P_NAN_CMP              = Pattern.compile("(?:Double|Float)\\.NaN\\s*(?:==|!=)");
    private static final Pattern P_EMPTY_CATCH_COMMENT  = Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*//[^\\n]*\\n\\s*\\}");
    // reviewSpringBoot extra
    private static final Pattern P_RESP_BODY_GET         = Pattern.compile("\\.getBody\\s*\\(\\s*\\)\\.\\w+");
    private static final Pattern P_DATA_ENTITY           = Pattern.compile("@Data[^;{}\\n]*(?:\\n(?:\\s*@[^\\n]*\\n)*)\\s*(?:public|final)?\\s*class\\s+\\w+", Pattern.DOTALL);
    private static final Pattern P_BUILDER_ENTITY        = Pattern.compile("@Builder[^;{}\\n]*(?:\\n(?:\\s*@[^\\n]*\\n)*)\\s*(?:public|final)?\\s*class\\s+\\w+", Pattern.DOTALL);
    private static final Pattern P_HASHMAP_FIELD         = Pattern.compile("(?:private|protected)\\s+(?:HashMap|Map<[^>]+>)\\s+\\w+\\s*=\\s*new\\s+HashMap\\s*<");
    private static final Pattern P_TX_HTTP_CALL          = Pattern.compile("@Transactional[^;{}\\n]*(?:\\n(?:\\s*@[^\\n]*\\n)*)\\s*(?:public|protected)\\s+[\\w<>\\[\\]]+\\s+\\w+\\s*\\(", Pattern.DOTALL);
    private static final Pattern P_LAZY_ENTITY_DIRECT   = Pattern.compile("@OneToMany\\([^)]*fetch\\s*=\\s*FetchType\\.LAZY");
    private static final Pattern P_ASYNC_NO_EXECUTOR    = Pattern.compile("@EnableAsync\\b");
    private static final Pattern P_ENABLE_SCHEDULING    = Pattern.compile("@EnableScheduling\\b");
    private static final Pattern P_EVENT_LISTENER_PRIV  = Pattern.compile("@EventListener[^;{}\\n]*(?:\\n(?:\\s*@[^\\n]*\\n)*)\\s*private\\s+\\w+\\s+\\w+\\s*\\(", Pattern.DOTALL);
    private static final Pattern P_FINAL_COMPONENT      = Pattern.compile("@(?:Component|Service|Repository|Controller|RestController)[^;{}\\n]*(?:\\n(?:\\s*@[^\\n]*\\n)*)\\s*(?:public\\s+)?final\\s+class\\s+", Pattern.DOTALL);

    /**
     * Well-known string values that carry semantic meaning as-is and do not need to
     * be extracted into named constants.  HTTP verbs, standard status words,
     * content-type tokens, encoding names, etc.
     */
    private static final Set<String> LITERAL_ALLOWLIST = Set.of(
        // HTTP methods
        "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE", "CONNECT",
        // HTTP status text (Spring HttpStatus names)
        "OK", "CREATED", "ACCEPTED", "NO_CONTENT", "MOVED_PERMANENTLY", "FOUND",
        "NOT_MODIFIED", "BAD_REQUEST", "UNAUTHORIZED", "FORBIDDEN", "NOT_FOUND",
        "CONFLICT", "GONE", "UNPROCESSABLE_ENTITY", "TOO_MANY_REQUESTS",
        "INTERNAL_SERVER_ERROR", "NOT_IMPLEMENTED", "SERVICE_UNAVAILABLE",
        // Common lifecycle / domain status words (often backed by enums already)
        "SUCCESS", "FAILURE", "ERROR", "PENDING", "PROCESSING", "COMPLETED",
        "ACTIVE", "INACTIVE", "ENABLED", "DISABLED", "OPEN", "CLOSED", "ARCHIVED",
        "DRAFT", "PUBLISHED", "APPROVED", "REJECTED", "CANCELLED", "SUSPENDED",
        // Media/content types and charset tokens
        "json", "JSON", "xml", "XML", "html", "HTML", "text", "TEXT", "plain", "PLAIN",
        "UTF-8", "utf-8", "ISO-8859-1", "US-ASCII",
        // Boolean-like config values
        "yes", "YES", "no", "NO", "on", "ON", "off", "OFF",
        // Common short tokens that are not magic strings
        "id", "ID", "UUID", "null", "true", "false",
        // Framework / environment tags
        "default", "DEFAULT", "test", "TEST", "main", "MAIN", "application", "APPLICATION"
    );

    public static void runRules(String content, String[] lines, ChangedFile file, List<Finding> findings, Config config) {
        List<Range> methodRanges = findMethodRanges(lines);
        AnalysisContext context = new AnalysisContext();
        context.className = file.name.replace(".java", "");
        context.analyze(content, methodRanges, lines);
        int[] lineOffsets = buildLineOffsets(content);

        // When PMD ran successfully for this file, skip the rule groups it covers to avoid
        // duplicate findings. Groups with no PMD equivalent always run regardless.
        boolean pmdCovered = config.enablePmdAnalysis
                && config.pmdCoveredFiles.contains(file.name);

        if (config.enableRulesBugPatterns    && !pmdCovered) reviewBugPatterns(content, lines, file, findings, context, lineOffsets);
        if (config.enableRulesNullSafety     && !pmdCovered) reviewNullSafety(content, lines, file, findings, context, lineOffsets);
        if (config.enableRulesExceptions     && !pmdCovered) reviewExceptionHandling(content, lines, file, findings, context, lineOffsets);
        if (config.enableRulesLogging        && !pmdCovered) reviewLogging(content, lines, file, findings, context, methodRanges, lineOffsets);
        if (config.enableRulesPerformance    && !pmdCovered) reviewPerformance(content, lines, file, findings, context, lineOffsets);
        if (config.enableRulesCodeQuality    && !pmdCovered) reviewCodeQuality(content, lines, file, findings, context, config, lineOffsets);
        if (config.enableRulesCodeQuality    && !pmdCovered) reviewJavaModern(content, lines, file, findings, config, lineOffsets);
        // These have no PMD equivalent — always run:
        if (config.enableRulesSpringBoot)     reviewSpringBoot(content, lines, file, findings, context, config, lineOffsets);
        if (config.enableRulesSecurity)       reviewSecurity(content, lines, file, findings, context, lineOffsets);
        if (config.enableRulesOpenApi)        reviewOpenApi(content, lines, file, findings, context, lineOffsets);
        if (config.enableRulesSoap)           reviewSoap(content, lines, file, findings, context, lineOffsets);
        if (config.enableRulesGrpc)           reviewGrpc(content, lines, file, findings, context, lineOffsets);
        if (config.enableRulesOutboundClient) reviewOutboundClients(content, lines, file, findings, context, lineOffsets);
        if (config.enableRulesScheduled)      reviewQuartz(content, lines, file, findings, lineOffsets);

        // AST post-pass: remove false positives from noisy regex rules when JavaParser is available
        List<Finding> filtered = AstRuleFilter.filter(content, findings);
        findings.clear();
        findings.addAll(filtered);
    }

    /**
     * Builds a sorted array of character offsets where each newline character occurs.
     * Used by {@link #getLineNumber(int[], int)} for O(log n) line-number lookups
     * instead of the previous O(n) substring + stream count approach.
     */
    static int[] buildLineOffsets(String content) {
        List<Integer> offsets = new ArrayList<>();
        offsets.add(0);
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') offsets.add(i + 1);
        }
        int[] arr = new int[offsets.size()];
        for (int i = 0; i < offsets.size(); i++) arr[i] = offsets.get(i);
        return arr;
    }

    /** O(log n) line-number lookup using the pre-built offset table. */
    private static int getLineNumber(int[] offsets, int charIndex) {
        int lo = 0, hi = offsets.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (offsets[mid] <= charIndex) lo = mid;
            else hi = mid - 1;
        }
        return lo + 1;
    }

    private static Set<Integer> buildLoggingScope(ChangedFile file, String[] lines, List<Range> methodRanges) {  // lines param kept for API compat
        Set<Integer> scope = new HashSet<>(file.changedLines);
        if (methodRanges == null || methodRanges.isEmpty() || file.changedLines.isEmpty()) {
            return scope;
        }
        for (int cl : file.changedLines) {
            Range r = getMethodRangeForLine(methodRanges, cl);
            if (r != null) {
                for (int i = r.start; i <= r.end; i++) {
                    scope.add(i);
                }
            }
        }
        return scope;
    }

    private static List<Range> findMethodRanges(String[] lines) {
        List<Range> ranges = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            int braceLine = -1;
            String sig = lines[i];
            if (sig.contains("(")) {
                int look = i;
                while (look < lines.length && look < i + 6) {
                    if (lines[look].contains("{")) { 
                        braceLine = look; 
                        break; 
                    }
                    if (look > i) sig += lines[look];
                    look++;
                }
            }
            if (braceLine >= 0) {
                String normalized = sig.replaceAll("/\\*.*?\\*/", " ").replaceAll("//.*", " ").replaceAll("\\s+", " ").trim();
                if (!normalized.matches("(?i)^\\s*(if|for|while|switch|catch|do|try|synchronized)\\b.*") && normalized.contains("(") && normalized.contains(")")) {
                    int end = findBlockEndLine(lines, braceLine, lines[braceLine].indexOf('{'));
                    if (end > 0) {
                        ranges.add(new Range(i + 1, end));
                        i = end;
                        continue;
                    }
                }
            }
            i++;
        }
        return ranges;
    }

    private static Range getMethodRangeForLine(List<Range> ranges, int line) {
        for (Range r : ranges) {
            if (line >= r.start && line <= r.end) return r;
        }
        return null;
    }

    private static int findBlockEndLine(String[] lines, int startLineIdx, int startCharIdx) {
        int depth = 0;
        for (int i = startLineIdx; i < lines.length; i++) {
            String l = lines[i];
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

    private static List<LogCall> findLogCallsCached(String content) {
        List<LogCall> calls = new ArrayList<>();
        Matcher m = P_LOG_CALLS.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
            String level = m.group(1);
            String argsPart = m.group(2);
            List<String> rawArgs = splitLogArgs(argsPart);
            
            String message = "";
            List<String> vars = new ArrayList<>();
            
            int start = 0;
            if (!rawArgs.isEmpty() && rawArgs.get(0).startsWith("\"")) {
                message = rawArgs.get(0);
                start = 1;
            }
            
            for (int i = start; i < rawArgs.size(); i++) {
                String arg = rawArgs.get(i);
                if (!arg.isEmpty()) {
                    vars.add(arg.replaceAll("\\s+", ""));
                }
            }
            
            calls.add(new LogCall(line, level, message, vars));
        }
        return calls;
    }

    private static List<String> splitLogArgs(String argsPart) {
        List<String> args = new ArrayList<>();
        if (argsPart == null || argsPart.trim().isEmpty()) return args;

        List<String> split = new ArrayList<>();
        boolean inQuote = false;
        boolean escaped = false;
        int parenDepth = 0;
        int braceDepth = 0;
        int bracketDepth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : argsPart.toCharArray()) {
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                current.append(c);
                continue;
            }
            if (c == '"') inQuote = !inQuote;
            
            if (!inQuote) {
                if (c == '(') parenDepth++;
                else if (c == ')') parenDepth--;
                else if (c == '{') braceDepth++;
                else if (c == '}') braceDepth--;
                else if (c == '[') bracketDepth++;
                else if (c == ']') bracketDepth--;
            }

            if (c == ',' && !inQuote && parenDepth == 0 && braceDepth == 0 && bracketDepth == 0) {
                split.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) split.add(current.toString().trim());
        return split;
    }

    private static class LogCall {
        int line;
        String level;
        String message;
        List<String> args; // variables
        LogCall(int line, String level, String message, List<String> args) {
            this.line = line;
            this.level = level;
            this.message = message;
            this.args = args;
        }
    }

    private static void reviewBugPatterns(String content, String[] lines, ChangedFile file, List<Finding> findings, AnalysisContext context, int[] lo) {
        Matcher m = P_RESOURCE_OPEN.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            
            String varName = m.group(1);
            String code = lines[line - 1].trim();
            
            boolean inTryWithResources = false;
            int lookBack = Math.max(0, m.start() - 50);
            String contextStr = content.substring(lookBack, m.start());
            if (contextStr.matches("(?s).*try\\s*\\(.*")) {
                inTryWithResources = true;
            }
            
            if (!inTryWithResources) {
                // Check if .close() is called on this variable in the rest of the file
                Pattern closePattern = Pattern.compile("\\b" + Pattern.quote(varName) + "\\.close\\(\\)");
                if (!closePattern.matcher(content).find()) {
                    findings.add(new Finding(Severity.MUST_FIX, Category.CODE_QUALITY, file.name, line, code,
                        "I noticed '" + varName + "' might be leaking.",
                        "If this resource isn't closed, we might run into issues with file handles or connection pools down the line. Java's try-with-resources is a cleaner way to handle this automatically.",
                        "try (" + code.replace(";", "") + ") { \n    // your logic here \n}"));
                }
            }
        }

        // BigDecimal equality using equals() is scale-sensitive (1.0 != 1.00)
        m = P_BIGDECIMAL_EQ.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;

            String code = lines[line - 1].trim();
            String var1 = m.group(1);
            String var2 = m.group(2);

            boolean lineHasBD = code.contains("BigDecimal");
            boolean varIsBD = content.contains("BigDecimal " + var1) || content.contains("BigDecimal " + var2)
                    || content.contains("BigDecimal\t" + var1) || content.contains("BigDecimal\t" + var2);
            boolean nameHint = var1.toLowerCase().contains("amount") || var2.toLowerCase().contains("amount")
                    || var1.toLowerCase().contains("price") || var2.toLowerCase().contains("price")
                    || var1.toLowerCase().contains("total") || var2.toLowerCase().contains("total");
            if (lineHasBD || varIsBD || nameHint) {
                findings.add(new Finding(Severity.SHOULD_FIX, Category.CODE_QUALITY, file.name, line, code,
                    "I noticed a BigDecimal comparison that might behave unexpectedly.",
                    "Using .equals() with BigDecimal checks both the value and the scale (so 1.0 isn't equal to 1.00). In most cases, we want to compare just the numerical values using .compareTo().",
                    var1 + ".compareTo(" + var2 + ") == 0"));
            }
        }

        // BigDecimal constructed from numeric literal is scale-sensitive and can lose intent
        m = P_BIGDECIMAL_CTOR.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String literal = m.group(1);
            String code = lines[line - 1].trim();
            findings.add(new Finding(Severity.SHOULD_FIX, Category.CODE_QUALITY, file.name, line, code,
                "I noticed a BigDecimal being created from a numeric literal.",
                "Using a numeric literal like " + literal + " can produce unexpected scale and binary rounding issues. It's usually better to use a String or valueOf to keep things precise.",
                "new BigDecimal(\"" + literal + "\")"));
        }

        // Optional-returning methods should not return null
        m = P_OPTIONAL_NULL.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.MUST_FIX, Category.CODE_QUALITY, file.name, line, lines[line-1].trim(),
                "It looks like this Optional-returning method might return a null value.",
                "Returning null from a method that's supposed to return an Optional kind of defeats the purpose and can still lead to NullPointerExceptions. It's much safer to return Optional.empty().",
                "return Optional.empty();"));
        }

        // Modifying collection during foreach (ConcurrentModification risk)
        m = P_FOREACH_COLL.matcher(content);
        while (m.find()) {
            String coll = m.group(1);
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            int windowEnd = Math.min(content.length(), m.end() + 200);
            String window = content.substring(m.start(), windowEnd);
            if (window.contains(coll + ".remove") || window.contains(coll + ".add")) {
                findings.add(new Finding(Severity.SHOULD_FIX, Category.CODE_QUALITY, file.name, line, lines[line-1].trim(),
                    "We should be careful about modifying '" + coll + "' while iterating over it.",
                    "Adding or removing items from a collection while you're looping through it with a foreach loop often leads to a ConcurrentModificationException. It's usually safer to use an Iterator or collect the changes to apply them after the loop.",
                    "// Use an iterator to remove safely\nIterator<Type> it = " + coll + ".iterator();\nwhile (it.hasNext()) {\n    var item = it.next();\n    if (condition) it.remove();\n}"));
            }
        }

        // Resource leak detection (try-with-resources)
        m = P_NEW_STREAM.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String code = lines[line - 1].trim();
            
            boolean inTryWithResources = false;
            for (int i = Math.max(1, line - 3); i <= line; i++) {
                if (lines[i-1].contains("try (") || lines[i-1].contains("try(")) { inTryWithResources = true; break; }
            }
            if (inTryWithResources) continue;

            // Extract the declared variable name from the code line (e.g. "FileInputStream fis = ..." → "fis")
            String streamVarName = "resource";
            Matcher svn = Pattern.compile("\\b(\\w+)\\s*=\\s*new\\s+").matcher(code);
            if (svn.find()) streamVarName = svn.group(1);

            findings.add(new Finding(Severity.SHOULD_FIX, Category.CODE_QUALITY, file.name, line, code,
                "I noticed a resource created without try-with-resources.",
                "Failing to close resources can leak file handles and cause production failures.",
                "try (" + code.replace(";", "").trim() + ") {\n    // use " + streamVarName + "\n}"));
        }

        // Lock acquired without unlock() in a finally block
        m = P_LOCK_NO_FINALLY.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String code = lines[line - 1].trim();
            // Check if there's a finally block with unlock() within the next 30 lines
            boolean hasFinally = false;
            for (int i = line; i <= Math.min(lines.length, line + 30); i++) {
                String l = lines[i-1];
                if (l.contains("finally") && l.contains("{")) { hasFinally = true; break; }
                if (l.contains(".unlock()") && content.substring(m.start(), lo[Math.min(i, lo.length-1)]).contains("finally")) {
                    hasFinally = true; break;
                }
            }
            // Simpler: just check method body has both finally and unlock
            int methodEnd = Math.min(content.length(), lo.length > line ? lo[Math.min(line + 50, lo.length - 1)] : content.length());
            String methodSnippet = content.substring(m.start(), Math.min(content.length(), m.start() + 1500));
            if (!methodSnippet.contains("finally") || !methodSnippet.contains(".unlock()")) {
                findings.add(new Finding(Severity.MUST_FIX, Category.CODE_QUALITY, file.name, line, code,
                    "Lock acquired without guaranteed unlock() in a finally block.",
                    "If an exception occurs between lock() and unlock(), the lock will never be released causing a deadlock. Always call unlock() in a finally block.",
                    "lock.lock();\ntry {\n    // critical section\n} finally {\n    lock.unlock();\n}"));
            }
        }

        // ExecutorService created without shutdown
        m = P_EXECUTOR_NO_SHUTDOWN.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String code = lines[line - 1].trim();
            // Check if shutdown() appears anywhere in the same method (within 80 lines)
            boolean hasShutdown = false;
            for (int i = Math.max(1, line - 5); i <= Math.min(lines.length, line + 80); i++) {
                if (lines[i-1].contains(".shutdown()") || lines[i-1].contains(".shutdownNow()")) {
                    hasShutdown = true; break;
                }
            }
            if (!hasShutdown) {
                findings.add(new Finding(Severity.MUST_FIX, Category.CODE_QUALITY, file.name, line, code,
                    "ExecutorService created but no shutdown() or shutdownNow() found nearby.",
                    "An ExecutorService that is never shut down keeps its threads running indefinitely, leaking resources and preventing the JVM from exiting cleanly. Call shutdown() in a finally block or @PreDestroy method.",
                    "ExecutorService executor = Executors.newFixedThreadPool(4);\ntry {\n    // submit tasks\n} finally {\n    executor.shutdown();\n}"));
            }
        }

        // NaN comparison — NaN == NaN is always false
        m = P_NAN_CMP.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.MUST_FIX, Category.CODE_QUALITY, file.name, line, lines[line-1].trim(),
                "Comparing with NaN using == or != is always false/true.",
                "By IEEE 754 spec, NaN is not equal to anything, including itself. (Double.NaN == Double.NaN) is always false. Use Double.isNaN() or Float.isNaN() to check for NaN.",
                "if (Double.isNaN(value)) { ... }"));
        }

        // Float/double equality with ==
        m = P_FLOAT_EQ.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String code = lines[line - 1].trim();
            // Skip if already caught by boxed comparison check
            if (code.contains("Integer") || code.contains("Long") || code.contains("Boolean")) continue;
            String lhsName = m.group(1);
            String rhsName = m.group(2);
            // Only flag if the current line OR a preceding declaration within 30 lines declares float/double
            boolean isFloatContext = code.contains("float ") || code.contains("double ")
                    || code.contains("Float ") || code.contains("Double ");
            if (!isFloatContext) {
                for (int i = Math.max(0, line - 30); i < line - 1 && !isFloatContext; i++) {
                    String prev = lines[i];
                    if ((prev.contains("float ") || prev.contains("double ") || prev.contains("Float ") || prev.contains("Double "))
                            && (prev.contains(lhsName) || prev.contains(rhsName))) {
                        isFloatContext = true;
                    }
                }
            }
            if (!isFloatContext) continue;
            findings.add(new Finding(Severity.MUST_FIX, Category.CODE_QUALITY, file.name, line, code,
                "Floating point values compared with ==.",
                "Floating point arithmetic produces rounding errors (0.1 + 0.2 != 0.3). Using == on float/double almost never works as expected. Use Math.abs(a - b) < epsilon or BigDecimal for exact comparison.",
                "Math.abs(" + lhsName + " - " + rhsName + ") < 1e-9  // or use BigDecimal for financial values"));
        }

        // Empty catch with only a comment — should at least log
        m = P_EMPTY_CATCH_COMMENT.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String code = lines[line - 1].trim();
            findings.add(new Finding(Severity.CONSIDER, Category.EXCEPTION_HANDLING, file.name, line, code,
                "Catch block suppressed with only a comment.",
                "If the exception is truly expected and safe to ignore, the comment should explain why. Consider at least logging at DEBUG level so you have visibility during troubleshooting.",
                "} catch (SomeException e) {\n    log.debug(\"Expected: {}\", e.getMessage()); // explain why this is safe\n}"));
        }
    }

    private static void reviewNullSafety(String content, String[] lines, ChangedFile file, List<Finding> findings, AnalysisContext context, int[] lo) {
        // Optional.of can throw NPE
        Matcher m = P_OPTIONAL_OF.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String arg = m.group(1);
            if (arg != null && !arg.trim().matches("\".*\"|\\d+|true|false")) {
                String code = lines[line - 1].trim();
                findings.add(new Finding(Severity.SHOULD_FIX, Category.NULL_SAFETY, file.name, line, code,
                    "We should probably use Optional.ofNullable() here to be safe.",
                    "If '" + arg + "' happens to be null, Optional.of() will throw a NullPointerException immediately. ofNullable() is a safer bet if there's any chance the value is missing.",
                    "Optional.ofNullable(" + arg + ")"));
            }
        }

        // Chained dereference a.b().c() risk (simple heuristic)
        m = P_CHAINED_DEREF.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String chainedLine = lines[line-1].trim();
            // Skip known-safe chains (logger, stream, Optional, builder fluent APIs)
            if (chainedLine.contains("log.") || chainedLine.startsWith(".") ||
                chainedLine.contains(".stream().") || chainedLine.contains("Optional.") ||
                chainedLine.contains(".builder().") || chainedLine.contains(".collect(")) continue;
            // Extract root object and first method from the matched text (e.g. "response.getBody().getValue" → root="response", m1="getBody()")
            String matched = m.group();  // e.g. "response.getBody().get"
            String[] parts = matched.split("\\.");
            String root = parts.length > 0 ? parts[0] : "obj";
            String firstCall = parts.length > 1 ? parts[1] : "getNext()";
            if (!firstCall.endsWith(")")) firstCall += "()";
            findings.add(new Finding(Severity.SHOULD_FIX, Category.NULL_SAFETY, file.name, line, chainedLine,
                "I noticed a chained method call that could throw a NullPointerException.",
                "If any object in the chain returns null, the whole thing blows up. Consider adding null checks or wrapping the root in Optional.",
                "// Option 1: explicit null check\nif (" + root + " != null && " + root + "." + firstCall + " != null) {\n    " + chainedLine + "\n}\n// Option 2: Optional chain\nOptional.ofNullable(" + root + ")\n    .map(o -> o." + firstCall + ")\n    .ifPresent(val -> { /* use val */ });"));
        }

        // List get without bounds check
        m = P_LIST_GET.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String index = m.group(1);
            String listLine = lines[line-1].trim();
            String listVar = "list";
            int getDot = listLine.indexOf(".get(");
            if (getDot > 0) {
                String before = listLine.substring(0, getDot);
                int lastDelim = Math.max(before.lastIndexOf(' '), Math.max(before.lastIndexOf('='), before.lastIndexOf('(')));
                String candidate = before.substring(lastDelim + 1).trim();
                if (!candidate.isEmpty()) listVar = candidate;
            }
            findings.add(new Finding(Severity.SHOULD_FIX, Category.NULL_SAFETY, file.name, line, listLine,
                "I spotted a list access that doesn't check the size first.",
                "If the list is smaller than we expect, this will throw an IndexOutOfBoundsException. A quick isEmpty() and size check prevents this.",
                "if (!" + listVar + ".isEmpty() && " + index + " < " + listVar + ".size()) {\n    var item = " + listVar + ".get(" + index + ");\n}"));
        }

        // getBody() null-dereference on ResponseEntity
        m = P_RESP_BODY_GET.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String reLine = lines[line-1].trim();
            // Extract the ResponseEntity variable name (e.g. "response.getBody().getId()" → "response")
            String reVar = "response";
            int getBodyIdx = reLine.indexOf(".getBody()");
            if (getBodyIdx > 0) {
                String before = reLine.substring(0, getBodyIdx);
                int delim = Math.max(before.lastIndexOf(' '), Math.max(before.lastIndexOf('='), before.lastIndexOf('(')));
                String candidate = before.substring(delim + 1).trim();
                if (!candidate.isEmpty() && candidate.matches("[a-zA-Z_]\\w*")) reVar = candidate;
            }
            // Extract the chained call after getBody() (e.g. ".getId()")
            String chainedCall = "getValue()";
            Matcher chainM = Pattern.compile("\\.getBody\\s*\\(\\s*\\)\\.([\\w]+\\s*\\([^)]*\\))").matcher(reLine);
            if (chainM.find()) chainedCall = chainM.group(1);
            findings.add(new Finding(Severity.SHOULD_FIX, Category.NULL_SAFETY, file.name, line, reLine,
                "I noticed .getBody() chained directly without a null check.",
                "ResponseEntity.getBody() can return null when the response has no body (e.g. 204 No Content, 404). Chaining a method call on it without checking will throw a NullPointerException.",
                "var body = " + reVar + ".getBody();\nif (body != null) {\n    body." + chainedCall + ";\n}"));
        }

        // Optional.get without presence check
        m = P_OPT_GET.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String optLine = lines[line-1].trim();
            // group(1) is the variable name directly from "(\\w+)\\.get\\(\\)"
            String optVar = m.group(1);
            // Guard: confirm the variable is Optional-typed by scanning the preceding 30 lines
            boolean isOptional = false;
            for (int i = Math.max(0, line - 30); i < line - 1; i++) {
                String prev = lines[i];
                if (prev.contains("Optional") && prev.contains(optVar)) { isOptional = true; break; }
            }
            if (!isOptional) continue;
            // Combined risk: Optional.get() in a loop — scan preceding lines instead of full content regex
            boolean inLoop = false;
            for (int i = Math.max(0, line - 10); i < line - 1; i++) {
                String prev = lines[i].trim();
                if (prev.startsWith("for ") || prev.startsWith("for(") || prev.startsWith("while ") || prev.startsWith("while(")) {
                    inLoop = true; break;
                }
            }
            Severity severity = inLoop ? Severity.MUST_FIX : Severity.SHOULD_FIX;
            String message = inLoop ? "Careful with that .get() inside the loop!" : "I spotted an Optional.get() without a safety check.";
            findings.add(new Finding(severity, Category.NULL_SAFETY, file.name, line, optLine,
                message,
                "If the Optional is empty, this will throw a NoSuchElementException. It's especially risky in a loop where it could cause a batch process to fail halfway through.",
                optVar + ".orElseThrow(() -> new EntityNotFoundException(\"Value not found\"))"));
        }
    }

    private static void reviewExceptionHandling(String content, String[] lines, ChangedFile file, List<Finding> findings, AnalysisContext context, int[] lo) {
        // Empty catch blocks
        Matcher m = P_EMPTY_CATCH.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String eVar = m.group(2) != null ? m.group(2).trim() : "e";
            String catchLine = lines[line-1].trim();
            // Extract the exception type from the catch clause (e.g. "catch (IOException e)" → "IOException")
            String exType = "Exception";
            Matcher exM = Pattern.compile("catch\\s*\\(([\\w.]+(?:\\s*\\|\\s*[\\w.]+)*)\\s+").matcher(catchLine);
            if (exM.find()) exType = exM.group(1);
            findings.add(new Finding(Severity.MUST_FIX, Category.EXCEPTION_HANDLING, file.name, line, catchLine,
                "Looks like this catch block is empty.",
                "Swallowing exceptions without at least a log entry makes it really hard to track down production issues later. It's usually better to log the error so we have some visibility.",
                "} catch (" + exType + " " + eVar + ") {\n    log.error(\"Unexpected error in " + context.className + "\", " + eVar + ");\n}"));
        }

        // Catching Throwable
        m = P_CATCH_THROWABLE.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String catchLine = lines[line-1].trim();
            // Extract var name from "catch (Throwable t)" or "catch (Throwable e)"
            String tVar = "t";
            Matcher tvM = Pattern.compile("catch\\s*\\(Throwable\\s+(\\w+)\\)").matcher(catchLine);
            if (tvM.find()) tVar = tvM.group(1);
            findings.add(new Finding(Severity.MUST_FIX, Category.EXCEPTION_HANDLING, file.name, line, catchLine,
                "I noticed we're catching Throwable here, which is extremely broad.",
                "This catches everything, including OutOfMemoryErrors and other JVM errors we can't really recover from. It's much safer to catch Exception or specific subtypes.",
                "} catch (Exception " + tVar + ") {\n    log.error(\"Error processing\", " + tVar + ");\n}"));
        }

        // Catching generic Exception
        m = P_CATCH_EXCEPTION.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String catchLine = lines[line-1].trim();
            String eVar2 = "e";
            Matcher evM = Pattern.compile("catch\\s*\\(Exception\\s+(\\w+)\\)").matcher(catchLine);
            if (evM.find()) eVar2 = evM.group(1);
            findings.add(new Finding(Severity.SHOULD_FIX, Category.EXCEPTION_HANDLING, file.name, line, catchLine,
                "I noticed we're catching generic Exception, which can hide bugs.",
                "When we catch Exception, we might accidentally swallow RuntimeExceptions we didn't expect. Catching specific exceptions makes the error handling logic much clearer.",
                "} catch (IOException | SQLException " + eVar2 + ") {\n    log.error(\"Operation failed\", " + eVar2 + ");\n}"));
        }

        // Swallowed interrupt
        m = P_CATCH_INTERRUPT.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            boolean restores = false;
            for (int i = line; i <= Math.min(lines.length, line + 5); i++) {
                if (lines[i-1].contains("Thread.currentThread().interrupt()")) { restores = true; break; }
            }
            if (!restores) {
                String catchLine = lines[line-1].trim();
                String ieVar = "e";
                Matcher ieM = Pattern.compile("catch\\s*\\(InterruptedException\\s+(\\w+)\\)").matcher(catchLine);
                if (ieM.find()) ieVar = ieM.group(1);
                findings.add(new Finding(Severity.MUST_FIX, Category.EXCEPTION_HANDLING, file.name, line, catchLine,
                    "InterruptedException caught without restoring the interrupt flag.",
                    "Swallowing interrupts can break cooperative cancellation — callers checking Thread.isInterrupted() will never see the signal.",
                    "} catch (InterruptedException " + ieVar + ") {\n    Thread.currentThread().interrupt(); // restore flag\n    log.warn(\"Thread interrupted\", " + ieVar + ");\n}"));
            }
        }
    }

    private static void reviewLogging(String content, String[] lines, ChangedFile file, List<Finding> findings, AnalysisContext context, List<Range> methodRanges, int[] lo) {
        Set<Integer> loggingScope = buildLoggingScope(file, lines, methodRanges);

        // Sensitive data in logs
        Matcher m = P_LOG_SENSITIVE.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!loggingScope.contains(line)) continue;
            String sensitiveLine = lines[line-1].trim();
            // Extract the log level being used (log.info / log.debug / log.warn / log.error)
            String logLevel = "info";
            Matcher lvlM = Pattern.compile("log\\.(trace|debug|info|warn|error)\\s*\\(").matcher(sensitiveLine);
            if (lvlM.find()) logLevel = lvlM.group(1);
            findings.add(new Finding(Severity.MUST_FIX, Category.LOGGING, file.name, line, sensitiveLine,
                "I noticed sensitive data might be getting logged here.",
                "Logging things like passwords, tokens, or secrets is a major security risk because logs are often stored in plain text. We should redact this.",
                "log." + logLevel + "(\"...: {}\", safeValue); // redact or omit sensitive fields"));
        }

        // Logging inside loops
        m = P_LOG_IN_LOOP.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!loggingScope.contains(line)) continue;
            String loopLogLine = lines[line-1].trim();
            String logLevel2 = "debug";
            Matcher lvlM2 = Pattern.compile("log\\.(trace|debug|info|warn|error)\\s*\\(").matcher(loopLogLine);
            if (lvlM2.find()) logLevel2 = lvlM2.group(1);
            // Extract loop variable from the enclosing for statement (scan backward a few lines)
            String loopCountVar = "count";
            for (int i = Math.max(1, line - 5); i < line; i++) {
                Matcher forM = Pattern.compile("for\\s*\\([^:]+:\\s*(\\w+)\\b").matcher(lines[i-1]);
                if (forM.find()) { loopCountVar = forM.group(1) + ".size()"; break; }
                Matcher forIdxM = Pattern.compile("int\\s+(\\w+)\\s*=\\s*0").matcher(lines[i-1]);
                if (forIdxM.find()) { loopCountVar = forIdxM.group(1); break; }
            }
            findings.add(new Finding(Severity.SHOULD_FIX, Category.LOGGING, file.name, line, loopLogLine,
                "Logging inside a loop can be dangerous.",
                "If this loop runs many times, it could flood the log files, slow down the application, and increase storage costs. It's usually better to log a summary after the loop.",
                "// Log once after the loop:\nlog." + logLevel2 + "(\"Processed {} items\", " + loopCountVar + ");"));
        }

        // System.out/err usage
        m = P_SYS_OUT.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!loggingScope.contains(line)) continue;
            String sysLine = lines[line-1].trim();
            // Extract the actual message argument from System.out.println("msg") if possible
            String sysMsg = "message here";
            Matcher sysArgM = Pattern.compile("System\\.(?:out|err)\\.print(?:ln)?\\s*\\((.+)\\)\\s*;").matcher(sysLine);
            if (sysArgM.find()) sysMsg = sysArgM.group(1).trim();
            findings.add(new Finding(Severity.SHOULD_FIX, Category.LOGGING, file.name, line, sysLine,
                "I noticed System.out/err is being used instead of a logger.",
                "System.out doesn't respect logging levels or configuration, so this might get lost or clutter the console in production. A proper Logger is the way to go.",
                "private static final Logger log = LoggerFactory.getLogger(" + context.className + ".class);\n// ...\nlog.info(" + sysMsg + ");"));
        }

        List<LogCall> logCalls = findLogCallsCached(content);

        // Rebuild line numbers for log calls using fast offset table
        for (LogCall call : logCalls) {
            // line was set via old getLineNumber(content,pos) — recompute with offsets would require
            // storing the match pos; the existing approach is acceptable since log calls are few.
        }

        // Placeholder mismatch
        for (LogCall call : logCalls) {
            if (!loggingScope.contains(call.line)) continue;
            String message = call.message;
            int placeholders = (message.length() - message.replace("{}", "").length()) / 2;
            int argCount = call.args.size();
            if (placeholders > 0 && placeholders != argCount) {
                String code = lines[call.line - 1].trim();
                findings.add(new Finding(Severity.SHOULD_FIX, Category.LOGGING, file.name, call.line, code,
                    "It looks like the log message placeholders don't match the arguments.",
                    "You have " + placeholders + " '{}' placeholders but provided " + argCount + " arguments. This might cause the log to print incorrectly or throw an exception.",
                    "log.info(\"Message with {} placeholders\", arg1, arg2); // Ensure counts match"));
            }
        }

        // Duplicate variable logged within the same method
        Map<Range, List<LogCall>> loggedVarsByMethod = new HashMap<>();
        for (LogCall call : logCalls) {
            Range r = getMethodRangeForLine(methodRanges, call.line);
            if (r == null) continue;
            loggedVarsByMethod.computeIfAbsent(r, k -> new ArrayList<>()).add(call);
        }

        for (Map.Entry<Range, List<LogCall>> entry : loggedVarsByMethod.entrySet()) {
            List<LogCall> callsInMethod = entry.getValue();
            Map<String, List<Integer>> varOccurrences = new HashMap<>();
            
            for (LogCall call : callsInMethod) {
                for (String var : call.args) {
                    // Skip bare exception variables — logging "e" or "exception" in every catch block is expected
                    if (var.equals("e") || var.equalsIgnoreCase("exception")) continue;
                    varOccurrences.computeIfAbsent(var, k -> new ArrayList<>()).add(call.line);
                }
            }

            for (Map.Entry<String, List<Integer>> varEntry : varOccurrences.entrySet()) {
                List<Integer> linesWithVar = varEntry.getValue();
                if (linesWithVar.size() > 1) {
                    // Only flag if at least one occurrence is in the changed lines (loggingScope)
                    boolean touchesChange = linesWithVar.stream().anyMatch(loggingScope::contains);
                    if (!touchesChange) continue;

                    int firstLine = linesWithVar.get(0);
                    String code = lines[firstLine - 1].trim();
                    String otherLines = linesWithVar.subList(1, linesWithVar.size()).toString();

                    String v = varEntry.getKey();
                    findings.add(new Finding(Severity.CONSIDER, Category.LOGGING, file.name, firstLine, code,
                        "Variable '" + v + "' is logged multiple times in this method (lines " + linesWithVar + ").",
                        "Each log statement should add unique context (e.g. a different lifecycle stage or error branch). Repeated logs of the same variable can flood logs without adding value.",
                        "// Before: separate repeated logs\n// log.info(\"Got " + v + ": {}\", " + v + ");\n// log.info(\"Processing " + v + ": {}\", " + v + ");\n// After: one structured log with all context at the right level\nlog.debug(\"Processing item: id={}, status={}\", " + v + ".getId(), " + v + ".getStatus());"));
                }
            }
        }

        // Message-only repetition is intentionally not flagged (same message in different branches is normal)
    }

    private static void detectDuplicateLogs(List<LogCall> logCalls, String[] lines, Set<Integer> loggingScope, ChangedFile file, List<Finding> findings, List<Range> ranges) {
        for (Range r : ranges) {
            Map<String, List<Integer>> occ = new HashMap<>();
            for (LogCall call : logCalls) {
                if (call.line >= r.start && call.line <= r.end) {
                    String key = call.level + "|" + call.message + "|" + call.args.toString();
                    occ.computeIfAbsent(key, k -> new ArrayList<>()).add(call.line);
                }
            }
            for (Map.Entry<String, List<Integer>> e : occ.entrySet()) {
                if (e.getValue().size() < 2) continue;
                boolean touchesChange = e.getValue().stream().anyMatch(loggingScope::contains);
                if (!touchesChange) continue;
                int flagged = e.getValue().get(0);
                String code = lines[flagged - 1].trim();
                findings.add(new Finding(Severity.CONSIDER, Category.LOGGING, file.name, flagged, code,
                    "I noticed this same log message appears multiple times in this method.",
                    "Duplicate logs can make debugging confusing (which one triggered?). Adding some context or a unique ID to the message helps distinguish them.",
                    "log.info(\"Operation status: {} - Step 1\", status);\n// ...\nlog.info(\"Operation status: {} - Step 2\", status);"));
            }
        }
    }

    private static void reviewSecurity(String content, String[] lines, ChangedFile file, List<Finding> findings, AnalysisContext context, int[] lo) {

        // XXE — XML parsers created without disabling external entities (applies to all classes)
        Matcher m = P_XXE_PARSER.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String code = lines[line - 1].trim();
            // Only flag if setFeature / setProperty is not called nearby (within 10 lines after)
            boolean hasFeatureSet = false;
            for (int i = line; i <= Math.min(lines.length, line + 10); i++) {
                if (lines[i-1].contains("setFeature") || lines[i-1].contains("setProperty")) {
                    hasFeatureSet = true; break;
                }
            }
            if (!hasFeatureSet) {
                // Extract the factory/parser variable name from the matched code line
                String xxeVar = "factory";
                Matcher xxeVarM = Pattern.compile("(\\w+)\\s*\\.\\s*(?:newDocumentBuilder|newSAXParser|newTransformer)\\b").matcher(code);
                if (xxeVarM.find()) xxeVar = xxeVarM.group(1);
                else {
                    Matcher assignM = Pattern.compile("(\\w+)\\s*=\\s*XMLInputFactory\\.newInstance\\s*\\(").matcher(code);
                    if (assignM.find()) xxeVar = assignM.group(1);
                }
                findings.add(new Finding(Severity.MUST_FIX, Category.CODE_QUALITY, file.name, line, code,
                    "XML parser created without disabling external entities (XXE risk).",
                    "XML parsers process external entity references by default. This enables XXE attacks that can read local files, trigger SSRF, or cause DoS. Call setFeature() to disable DOCTYPE and external entities before parsing.",
                    xxeVar + ".setFeature(\"http://apache.org/xml/features/disallow-doctype-decl\", true);\n" + xxeVar + ".setFeature(\"http://xml.org/sax/features/external-general-entities\", false);"));
            }
        }

        // Unsafe deserialization — ObjectInputStream.readObject() without a filter
        m = P_UNSAFE_DESER.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String code = lines[line - 1].trim();
            boolean hasFilter = false;
            for (int i = Math.max(1, line - 5); i <= Math.min(lines.length, line + 5); i++) {
                if (lines[i-1].contains("setObjectInputFilter") || lines[i-1].contains("ObjectInputFilter")) {
                    hasFilter = true; break;
                }
            }
            if (!hasFilter) {
                // Extract the ObjectInputStream variable name (e.g. "ois.readObject()" → "ois")
                String oisVar = "ois";
                Matcher oisM = Pattern.compile("(\\w+)\\s*\\.\\s*readObject\\s*\\(").matcher(code);
                if (oisM.find()) oisVar = oisM.group(1);
                findings.add(new Finding(Severity.MUST_FIX, Category.CODE_QUALITY, file.name, line, code,
                    "ObjectInputStream.readObject() is unsafe — deserialization of untrusted data can execute arbitrary code.",
                    "Java deserialization via ObjectInputStream allows gadget chain attacks. Use ObjectInputFilter to restrict allowed classes, or replace with a safe format (JSON, Protobuf).",
                    oisVar + ".setObjectInputFilter(ObjectInputFilter.Config.createFilter(\"com.example.*;!*\"));\n// call setObjectInputFilter before readObject()"));
            }
        }

        // Insecure Random in security-sensitive method names
        m = P_INSECURE_RANDOM.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String code = lines[line - 1].trim();
            // Only flag if enclosing method name hints at security usage
            boolean inSecurityMethod = false;
            for (int i = Math.max(1, line - 15); i < line; i++) {
                String l = lines[i-1];
                if (l.matches(".*\\b(?:token|session|nonce|salt|otp|password|secret|auth|csrf|key|random)\\b.*")
                        && (l.contains("void ") || l.contains("String ") || l.contains("byte[] "))) {
                    inSecurityMethod = true; break;
                }
            }
            if (inSecurityMethod) {
                findings.add(new Finding(Severity.MUST_FIX, Category.CODE_QUALITY, file.name, line, code,
                    "java.util.Random is not cryptographically secure.",
                    "Random uses a predictable algorithm. For tokens, keys, session IDs, or any security-sensitive value, use java.security.SecureRandom instead.",
                    "SecureRandom random = new SecureRandom();\nbyte[] token = new byte[32];\nrandom.nextBytes(token);"));
            }
        }

        if (!context.isController) return;

        // @PreAuthorize / @Secured missing on REST endpoint methods
        m = P_PREAUTHORIZE.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            boolean hasAuth = false;
            for (int i = Math.max(0, line - 5); i < line - 1; i++) {
                String l = lines[i].trim();
                if (l.contains("@PreAuthorize") || l.contains("@Secured") ||
                    l.contains("@RolesAllowed") || l.contains("@PermitAll")) {
                    hasAuth = true;
                    break;
                }
            }
            if (!hasAuth) {
                findings.add(new Finding(Severity.SHOULD_FIX, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                    "I noticed this REST endpoint has no authorization annotation.",
                    "Without @PreAuthorize, @Secured, or a global security filter configured for this path, any caller may reach this endpoint. If intentional (public API), add @PermitAll to make it explicit. Otherwise declare the required role.",
                    "// Role-restricted:\n@PreAuthorize(\"hasRole('ROLE_USER')\")\n// Or explicitly public:\n@PermitAll"));
            }
        }

        // SQL injection via string concatenation
        m = P_SQL_CONCAT.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String sourceLine = lines[line - 1].trim();
            // Only flag when the surrounding line looks like a query string
            if (!sourceLine.toLowerCase().matches(".*\\b(?:select|insert|update|delete|where|from|query)\\b.*")) continue;
            findings.add(new Finding(Severity.MUST_FIX, Category.SPRING_BOOT, file.name, line, sourceLine,
                "I noticed what looks like a SQL query built by string concatenation.",
                "Concatenating user-supplied values into a SQL string opens the door to SQL injection attacks. Use parameterized queries or Spring Data JPA named parameters instead.",
                "// Spring Data JPA:\n@Query(\"SELECT e FROM Entity e WHERE e.name = :name\")\nList<Entity> findByName(@Param(\"name\") String name);\n\n// Plain JDBC (PreparedStatement):\nPreparedStatement ps = conn.prepareStatement(\"SELECT * FROM t WHERE name = ?\");\nps.setString(1, userInput);"));
        }

        // Path traversal
        m = P_PATH_TRAVERSAL.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String sourceLine = lines[line - 1].trim();
            // Only flag when the argument looks like it could be user-controlled (not a literal)
            if (sourceLine.contains("\"")) continue;
            findings.add(new Finding(Severity.MUST_FIX, Category.SPRING_BOOT, file.name, line, sourceLine,
                "I noticed a file path constructed from a potentially user-controlled value.",
                "If the path comes from user input without sanitization, an attacker can use '../' sequences to escape the intended directory and read/write arbitrary files (path traversal).",
                "// Validate and canonicalize:\nPath resolved = Paths.get(baseDir).resolve(userInput).normalize();\nif (!resolved.startsWith(Paths.get(baseDir))) throw new SecurityException(\"Invalid path\");"));
        }
    }

    private static void reviewSpringBoot(String content, String[] lines, ChangedFile file, List<Finding> findings, AnalysisContext context, Config config, int[] lo) {
        // Transactional on private method
        Matcher m = P_TX_PRIVATE.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String txLine = lines[line-1].trim();
            // Extract the method name from the matched declaration (e.g. "private void doSave(...)")
            String txMethod = "process";
            Matcher txMethM = Pattern.compile("private\\s+[\\w<>\\[\\]]+\\s+(\\w+)\\s*\\(").matcher(m.group());
            if (txMethM.find()) txMethod = txMethM.group(1);
            findings.add(new Finding(Severity.MUST_FIX, Category.SPRING_BOOT, file.name, line, txLine,
                "I noticed @Transactional on a private method.",
                "Spring's AOP proxies can't see private methods, so this annotation is basically being ignored. The transaction won't actually start.",
                "// Make it public:\n@Transactional\npublic void " + txMethod + "() { ... }"));
        }

        // Escalate severity for Spring-managed classes
        Severity springSeverity = context.isController || context.isService ? Severity.MUST_FIX : Severity.SHOULD_FIX;

        // @RequestBody without @Valid
        m = P_REQUEST_BODY.matcher(content);
        while (m.find()) {
            int start = m.start();
            // Scan the entire method parameter list rather than a fixed ±50 char window so that
            // multi-line parameter lists (each param on its own line) are handled correctly.
            // Walk backward to the opening ( of the method signature, then include the 300 chars
            // before it so annotations on their own line (e.g. @Valid on the line above) are covered.
            int paramOpen = content.lastIndexOf('(', start);
            int paramClose = content.indexOf(')', start);
            int annotationsStart = Math.max(0, (paramOpen >= 0 ? paramOpen : start) - 300);
            int windowEnd = paramClose >= 0 ? Math.min(content.length(), paramClose + 1) : Math.min(content.length(), m.end() + 300);
            String paramWindow = content.substring(annotationsStart, windowEnd);

            boolean hasValid = paramWindow.contains("@Valid") || paramWindow.contains("@Validated");
            
            if (hasValid) continue;

            int line = getLineNumber(lo, start);
            if (!isInChangedLines(file, line)) continue;
            String rbLine = lines[line-1].trim();
            // Extract the type and param name from "@RequestBody SomeDto dto"
            String rbType = "MyDto", rbParam = "dto";
            Matcher rbM = Pattern.compile("@RequestBody\\s+([\\w<>\\[\\]]+)\\s+(\\w+)").matcher(rbLine);
            if (rbM.find()) { rbType = rbM.group(1); rbParam = rbM.group(2); }
            findings.add(new Finding(springSeverity, Category.SPRING_BOOT, file.name, line, rbLine,
                "I noticed this @RequestBody is missing @Valid.",
                "Since this is a " + (context.isController ? "Controller" : "Service") + ", we should ensure the incoming payload is validated before we process it. This prevents malformed data from causing issues deeper in the logic.",
                "@Valid @RequestBody " + rbType + " " + rbParam));
        }

        // Field injection
        m = P_FIELD_INJECT.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String fiLine = lines[line-1].trim();
            // Extract type and field name from e.g. "private MyService myService;"
            String fiType = "MyService", fiField = "myService";
            Matcher fiM = Pattern.compile("private\\s+([\\w<>]+)\\s+(\\w+)\\s*;").matcher(fiLine);
            if (fiM.find()) { fiType = fiM.group(1); fiField = fiM.group(2); }
            findings.add(new Finding(Severity.SHOULD_FIX, Category.SPRING_BOOT, file.name, line, fiLine,
                "We might want to avoid field injection here.",
                "Using @Autowired on fields can make unit testing a bit of a headache because it requires reflection to mock. Constructor injection is usually the preferred way in modern Spring.",
                "private final " + fiType + " " + fiField + ";\n\npublic " + context.className + "(" + fiType + " " + fiField + ") {\n    this." + fiField + " = " + fiField + ";\n}"));
        }

        // Hardcoded URLs
        m = P_HARDCODED_URL.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.CONSIDER, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                "I spotted a hardcoded URL here.",
                "Hardcoding URLs makes it tough to switch environments (like dev to prod). Putting this in a property makes configuration much easier.",
                "@Value(\"${service.endpoint.url}\")\nprivate String endpointUrl;"));
        }

        // N+1 query heuristic: repository.find* inside loop
        m = P_REPO_FIND.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.SHOULD_FIX, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                "Potential N+1 query detected.",
                "Calling a repository find method inside a loop often leads to N+1 database queries, which kills performance. It's usually better to fetch everything in one go.",
                "// Fetch all IDs at once\nList<Entity> entities = repository.findAllById(ids);"));
        }

        // @ConfigurationProperties missing @Validated
        if (content.contains("@ConfigurationProperties") && !content.contains("@Validated")) {
            int line = getLineNumber(lo, content.indexOf("@ConfigurationProperties"));
            if (isInChangedLines(file, line)) {
                findings.add(new Finding(Severity.SHOULD_FIX, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                    "I noticed @ConfigurationProperties is missing @Validated.",
                    "Without @Validated, any validation annotations (like @NotNull) on the fields won't be checked at startup. We might miss missing config.",
                    "@Validated\n@ConfigurationProperties(prefix = \"app\")\npublic class AppConfig { ... }"));
            }
        }

        // @Value without default
        m = P_VALUE_NO_DEF.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String propKey = m.group(1);
            findings.add(new Finding(Severity.SHOULD_FIX, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                "I noticed an @Value annotation without a default value.",
                "If '" + propKey + "' is absent from the config, Spring will throw a BeanCreationException at startup. Provide a sensible fallback, or use @ConfigurationProperties + @NotNull to fail fast with a clear error message.",
                "// Provide an empty or safe fallback:\n@Value(\"${" + propKey + ":}\")\n// Or for required secrets, prefer @ConfigurationProperties with @NotNull"));
        }

        // @Value potential secrets
        m = P_VALUE_SECRET.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.MUST_FIX, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                "I noticed what looks like a secret being injected via @Value.",
                "Secrets shouldn't be in the code or properties files if possible. It's safer to load them from an environment variable or a secret manager.",
                "@Value(\"${api.key}\") // Load from environment/vault\nprivate String apiKey;"));
        }

        // @Cacheable without explicit key
        m = P_CACHEABLE.matcher(content);
        while (m.find()) {
            String body = m.group(1);
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            if (body != null && !body.contains("key=")) {
                findings.add(new Finding(Severity.SHOULD_FIX, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                    "This @Cacheable annotation is missing a key.",
                    "Using the default key can cause cache collisions if the method arguments change or are complex objects. It's safer to define the key explicitly.",
                    "@Cacheable(value = \"items\", key = \"#id\")"));
            }
        }

        // RestTemplate constructed inline
        m = P_REST_TPL_NEW.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.CONSIDER, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                "It looks like RestTemplate is being created inline.",
                "Creating a new RestTemplate every time misses out on shared configuration like timeouts and tracing. Injecting it as a bean is better practice.",
                "private final RestTemplate restTemplate;\n\npublic MyService(RestTemplateBuilder builder) {\n    this.restTemplate = builder.build();\n}"));
        }

        // @Scheduled with numeric fixedRate/fixedDelay
        m = P_SCHED_FIXED.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.CONSIDER, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                "I noticed @Scheduled using raw milliseconds.",
                "Raw numbers can be hard to read (is that 60000 one minute or ten?). Using ISO-8601 strings is much clearer and less error-prone.",
                "@Scheduled(fixedRateString = \"PT1M\") // Runs every 1 minute"));
        }

        // @CrossOrigin without restricted origins (security risk)
        // Flag: @CrossOrigin with wildcard, OR @CrossOrigin with no origins arg (defaults differ by Spring version)
        m = P_CROSS_WILD.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.MUST_FIX, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                "I noticed @CrossOrigin is allowing all origins with '*'.",
                "Allowing all origins exposes this endpoint to any website, which is a CORS security risk. Restrict it to only the domains you trust.",
                "@CrossOrigin(origins = \"https://your-app.example.com\")"));
        }
        // Also warn on bare @CrossOrigin (no args) which allows all in many Spring versions
        m = P_CROSS_BARE.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            // Skip if the annotation has explicit args on this or next line
            String peek = content.substring(m.start(), Math.min(content.length(), m.end() + 5)).trim();
            if (peek.contains("(")) continue;
            findings.add(new Finding(config.strictSpring ? Severity.MUST_FIX : Severity.SHOULD_FIX,
                Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                "I noticed a @CrossOrigin annotation with no origin restriction.",
                "Without an explicit 'origins' parameter, some Spring versions allow all origins by default. It's safer to explicitly restrict this to trusted domains.",
                "@CrossOrigin(origins = \"https://your-app.example.com\")"));
        }

        // @Async on private method (AOP proxy can't intercept it, so @Async is silently ignored)
        m = P_ASYNC_PRIVATE.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String asyncLine = lines[line-1].trim();
            String asyncMethod = "asyncMethod";
            Matcher asyncMethM = Pattern.compile("private\\s+[\\w<>\\[\\]]+\\s+(\\w+)\\s*\\(").matcher(m.group());
            if (asyncMethM.find()) asyncMethod = asyncMethM.group(1);
            findings.add(new Finding(Severity.MUST_FIX, Category.SPRING_BOOT, file.name, line, asyncLine,
                "I noticed @Async on a private method.",
                "Spring's AOP proxy cannot intercept private methods, so @Async is silently ignored here — the method will run synchronously. Make it public or move it to a separate @Service bean.",
                "@Async\npublic CompletableFuture<Void> " + asyncMethod + "() { ... }"));
        }

        // @PostConstruct / @PreDestroy on static method (Spring ignores them on static methods)
        m = P_LIFECYCLE_STAT.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.MUST_FIX, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                "I noticed @PostConstruct or @PreDestroy on a static method.",
                "Spring ignores lifecycle callbacks on static methods. Remove 'static' or use an instance method instead.",
                "// Remove static:\n@PostConstruct\npublic void init() { ... }"));
        }

        // @Transactional(readOnly=true) suggestion for query methods in @Service
        if (context.isService) {
            Pattern txReadOnly = Pattern.compile("@Transactional(?!\\([^)]*readOnly\\s*=\\s*true)(?:\\([^)]*\\))?\\s+(?:public|protected)\\s+[\\w<>\\[\\],\\s]+\\s+((?:get|find|list|fetch|load|search|count|query|select)\\w+)\\s*\\(");
            m = txReadOnly.matcher(content);
            while (m.find()) {
                int line = getLineNumber(lo, m.start());
                if (!isInChangedLines(file, line)) continue;
                String methodName = m.group(1);
                findings.add(new Finding(Severity.CONSIDER, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                    "Consider adding readOnly=true to @Transactional on '" + methodName + "'.",
                    "Read-only transactions can improve performance by allowing the database to use optimizations like skipping dirty checking. Since this looks like a query method, it's usually safe to set readOnly=true.",
                    "@Transactional(readOnly = true)\npublic " + methodName + "(...) { ... }"));
            }
        }

        // ResponseEntity<?> wildcard (loses type safety)
        m = P_RESP_WILDCARD.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(config.strictSpring ? Severity.SHOULD_FIX : Severity.CONSIDER,
                Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                "I noticed a ResponseEntity<?> with a wildcard type.",
                "Using ResponseEntity<?> loses type safety and makes it harder for API clients to understand the response contract. Prefer a specific type like ResponseEntity<MyDto>.",
                "public ResponseEntity<MyResponseDto> endpoint(...) { ... }"));
        }

        // Sensitive fields in @Entity without @JsonIgnore (data exposure risk)
        if (context.isEntity) {
            m = P_SENSITIVE_FIELD.matcher(content);
            while (m.find()) {
                int line = getLineNumber(lo, m.start());
                if (!isInChangedLines(file, line)) continue;
                // Check if @JsonIgnore or @JsonProperty(access = ...) appears in the 3 lines above
                boolean hasJsonIgnore = false;
                for (int i = Math.max(0, line - 4); i < line - 1; i++) {
                    if (lines[i].contains("@JsonIgnore") || lines[i].contains("JsonProperty")) {
                        hasJsonIgnore = true;
                        break;
                    }
                }
                if (!hasJsonIgnore) {
                    findings.add(new Finding(Severity.MUST_FIX, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                        "I noticed a sensitive field '" + m.group(1) + "' in an @Entity without @JsonIgnore.",
                        "Without @JsonIgnore, this sensitive field will be serialized into JSON responses, leaking it to API clients. Always exclude sensitive fields from serialization.",
                        "@JsonIgnore\nprivate String " + m.group(1) + ";"));
                }
            }
        }

        // @Transactional self-invocation: this.transactionalMethod() bypasses the Spring AOP proxy
        m = P_SELF_INVOKE.matcher(content);
        while (m.find()) {
            String calledMethod = m.group(1);
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            // Check if the called method is @Transactional in this class
            Pattern txOnMethod = Pattern.compile("@Transactional[^;{}]*(?:public|protected)\\s+\\S+\\s+" + Pattern.quote(calledMethod) + "\\s*\\(");
            if (txOnMethod.matcher(content).find()) {
                findings.add(new Finding(Severity.MUST_FIX, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                    "I noticed a self-invocation call this." + calledMethod + "() to a @Transactional method.",
                    "Spring's proxy-based AOP intercepts calls from outside the bean, not internal 'this.' calls. The @Transactional on '" + calledMethod + "' will be silently ignored — no transaction will be started for that inner call.",
                    "// Option 1: inject self via ApplicationContext (avoids field injection):\n@Autowired private ApplicationContext ctx;\nctx.getBean(getClass())." + calledMethod + "();\n\n// Option 2 (preferred): extract " + calledMethod + " into a separate @Service\n// and inject that bean via constructor injection."));
            }
        }

        // @Cacheable on private method (AOP proxy cannot intercept it)
        m = P_CACHE_PRIVATE.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String cacheLine = lines[line-1].trim();
            // Extract method signature from the match (e.g. "private Item findById(Long id)")
            String cacheMethod = "findById";
            String cacheReturnType = "Object";
            String cacheParams = "";
            Matcher cacheMethM = Pattern.compile("private\\s+([\\w<>\\[\\]]+)\\s+(\\w+)\\s*\\(([^)]*)\\)").matcher(m.group());
            if (cacheMethM.find()) {
                cacheReturnType = cacheMethM.group(1);
                cacheMethod = cacheMethM.group(2);
                cacheParams = cacheMethM.group(3).trim();
            }
            // Extract @Cacheable annotation body from the match to preserve value/key attributes
            String cacheAnno = "@Cacheable(value = \"items\", key = \"#id\")";
            Matcher cacheAnnoM = Pattern.compile("(@Cacheable\\s*\\([^)]*\\))").matcher(m.group());
            if (cacheAnnoM.find()) cacheAnno = cacheAnnoM.group(1);
            findings.add(new Finding(Severity.MUST_FIX, Category.SPRING_BOOT, file.name, line, cacheLine,
                "I noticed @Cacheable on a private method.",
                "Spring's AOP proxy cannot intercept private methods, so @Cacheable is silently ignored here — the method will be called every time without any caching. Make it public or move it to a separate @Service bean.",
                cacheAnno + "\npublic " + cacheReturnType + " " + cacheMethod + "(" + cacheParams + ") { ... }"));
        }

        // @Scheduled method that blocks the scheduler thread by calling Future.get() or CompletableFuture.join()
        // without a timeout.  This starves the shared scheduler thread pool.
        m = P_SCHED_HEADER.matcher(content);
        while (m.find()) {
            int annoLine = getLineNumber(lo, m.start());
            // Find the opening brace of the method body
            int braceStart = content.indexOf('{', m.end());
            if (braceStart == -1) continue;
            // Scan up to ~3000 chars of body (heuristic) for blocking calls
            String body = content.substring(braceStart, Math.min(content.length(), braceStart + 3000));
            boolean blocks = body.contains(".get()") || body.contains(".join()");
            if (!blocks) continue;
            if (!isInChangedLines(file, annoLine)) continue;
            findings.add(new Finding(Severity.MUST_FIX, Category.SPRING_BOOT, file.name, annoLine, lines[annoLine - 1].trim(),
                "I noticed this @Scheduled method blocks on a Future.",
                "Calling Future.get() or CompletableFuture.join() inside a @Scheduled method blocks the scheduler thread. " +
                "Spring's default scheduler uses a single thread, so one blocked task can freeze ALL scheduled jobs. " +
                "Either use a timeout (.get(5, TimeUnit.SECONDS)), orTimeout(), or fire-and-forget without waiting for the result.",
                "import java.util.concurrent.TimeUnit;\n\n" +
                "// Option 1: bounded Future.get() with timeout\nfuture.get(10, TimeUnit.SECONDS);\n\n" +
                "// Option 2: CompletableFuture with orTimeout (Java 9+)\n@Scheduled(fixedRateString = \"PT1M\")\npublic void task() {\n" +
                "    asyncOp().orTimeout(10, TimeUnit.SECONDS)\n" +
                "             .exceptionally(ex -> { log.error(\"Scheduled task failed\", ex); return null; });\n}"));
        }

        // @Data on @Entity — Lombok generates equals/hashCode on all fields incl. null id (breaks JPA dirty-check)
        if (context.isEntity) {
            Matcher dm = P_DATA_ENTITY.matcher(content);
            if (dm.find()) {
                int line = getLineNumber(lo, dm.start());
                if (isInChangedLines(file, line)) {
                    findings.add(new Finding(Severity.MUST_FIX, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                        "I noticed @Data on a JPA @Entity class.",
                        "Lombok @Data generates equals() and hashCode() based on all fields, including the id which is null before the entity is persisted. This breaks JPA dirty checking, causes incorrect Set/HashMap behavior, and can trigger LazyInitializationExceptions. Use @Getter @Setter @ToString instead and implement equals/hashCode based on the natural key.",
                        "@Entity\n@Getter @Setter @ToString\npublic class MyEntity {\n    @Id @GeneratedValue\n    private Long id;\n    // override equals/hashCode using business key\n}"));
                }
            }
            // @Builder on @Entity without @NoArgsConstructor — JPA proxies need a no-arg constructor
            Matcher bm = P_BUILDER_ENTITY.matcher(content);
            if (bm.find() && !content.contains("@NoArgsConstructor") && !content.contains("@AllArgsConstructor")) {
                int line = getLineNumber(lo, bm.start());
                if (isInChangedLines(file, line)) {
                    findings.add(new Finding(Severity.MUST_FIX, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                        "I noticed @Builder on a JPA @Entity without @NoArgsConstructor.",
                        "Lombok @Builder generates an all-args constructor and suppresses the default no-arg constructor. JPA requires a no-arg constructor to instantiate proxy objects — this will cause runtime failures. Add @NoArgsConstructor and @AllArgsConstructor alongside @Builder.",
                        "@Entity\n@Builder @NoArgsConstructor @AllArgsConstructor\npublic class MyEntity { ... }"));
                }
            }
        }

        // HashMap as class-level field in @Service/@Component — not thread-safe
        if (context.isService || context.classAnnotations.contains("Component")) {
            Matcher hm = P_HASHMAP_FIELD.matcher(content);
            while (hm.find()) {
                int line = getLineNumber(lo, hm.start());
                if (!isInChangedLines(file, line)) continue;
                String hmLine = lines[line-1].trim();
                Matcher typeM = Pattern.compile("Map<([^>]+)>").matcher(hmLine);
                String typeParams = typeM.find() ? typeM.group(1) : "K, V";
                // Extract field name from the original declaration
                Matcher nameM = Pattern.compile("Map<[^>]+>\\s+(\\w+)\\s*=").matcher(hmLine);
                String fieldName = nameM.find() ? nameM.group(1) : "cache";
                findings.add(new Finding(Severity.SHOULD_FIX, Category.PERFORMANCE, file.name, line, hmLine,
                    "I noticed a HashMap field in a Spring-managed bean.",
                    "Spring beans are singletons shared across threads. A plain HashMap is not thread-safe — concurrent reads and writes can corrupt the map or cause an infinite loop. Use ConcurrentHashMap for a simple fix, or a proper cache (Caffeine, Guava) for expiry/eviction.",
                    "private final Map<" + typeParams + "> " + fieldName + " = new ConcurrentHashMap<>();"));
            }
        }

        // @Transactional method containing HTTP/network calls — holds DB connection during I/O
        if ((context.isService || context.isController) && content.contains("@Transactional")) {
            Matcher tm = P_TX_HTTP_CALL.matcher(content);
            while (tm.find()) {
                int line = getLineNumber(lo, tm.start());
                if (!isInChangedLines(file, line)) continue;
                // Only flag if the method body (approx next 2000 chars) contains an HTTP call
                int bodyStart = content.indexOf('{', tm.end());
                if (bodyStart == -1) continue;
                String body = content.substring(bodyStart, Math.min(content.length(), bodyStart + 2000));
                boolean hasHttpCall = body.contains(".exchange(") || body.contains(".getForObject(") ||
                    body.contains(".postForObject(") || body.contains("restTemplate.") ||
                    body.contains(".retrieve().") || body.contains(".webclient") ||
                    body.contains("restClient.") || body.contains(".block(");
                if (!hasHttpCall) continue;
                findings.add(new Finding(Severity.SHOULD_FIX, Category.PERFORMANCE, file.name, line, lines[line-1].trim(),
                    "I noticed a @Transactional method that appears to make an HTTP call.",
                    "Holding a database transaction open while waiting for an HTTP response ties up a connection pool thread for the full network round-trip. If the remote service is slow, this can exhaust the connection pool and cascade into a full service outage. Consider moving the HTTP call outside the transaction boundary.",
                    "// Move HTTP call before the @Transactional method:\nMyDto response = httpClient.fetchData(); // outside transaction\ntransactionalService.saveResult(response); // inside transaction"));
            }
        }

        // @Async without a configured executor — Spring uses SimpleAsyncTaskExecutor (unbounded new threads)
        if (content.contains("@Async") && content.contains("@EnableAsync")) {
            Matcher am = P_ASYNC_NO_EXECUTOR.matcher(content);
            if (am.find()) {
                int line = getLineNumber(lo, am.start());
                if (isInChangedLines(file, line) && !content.contains("AsyncConfigurer") && !content.contains("ThreadPoolTaskExecutor")) {
                    findings.add(new Finding(Severity.SHOULD_FIX, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                        "I noticed @EnableAsync without a custom executor configured.",
                        "Without a configured executor, Spring uses SimpleAsyncTaskExecutor which creates a new thread for every @Async invocation — it is not a thread pool. Under load this can exhaust OS thread limits. Implement AsyncConfigurer or define a ThreadPoolTaskExecutor bean.",
                        "@Bean\npublic Executor asyncExecutor() {\n    ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();\n    exec.setCorePoolSize(4);\n    exec.setMaxPoolSize(10);\n    exec.setQueueCapacity(100);\n    exec.initialize();\n    return exec;\n}"));
                }
            }
        }

        // @Scheduled without @EnableScheduling — silently never runs
        if (content.contains("@Scheduled") && !content.contains("@EnableScheduling")) {
            // Only warn on the @Scheduled annotation line itself
            Matcher sm = P_SCHED_HEADER.matcher(content);
            if (sm.find()) {
                int line = getLineNumber(lo, sm.start());
                if (isInChangedLines(file, line)) {
                    // Check whether @EnableScheduling exists anywhere in the project would require a cross-file scan;
                    // flag as CONSIDER so teams without it notice, teams with it in a config class are not blocked.
                    findings.add(new Finding(Severity.CONSIDER, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                        "I noticed @Scheduled — make sure @EnableScheduling is on a @Configuration class.",
                        "@Scheduled is silently ignored if @EnableScheduling is not present on any @Configuration class in the application context. The method will never run.",
                        "@Configuration\n@EnableScheduling\npublic class SchedulingConfig { }"));
                }
            }
        }

        // @EventListener on private method — Spring AOP cannot proxy private methods
        Matcher elm = P_EVENT_LISTENER_PRIV.matcher(content);
        while (elm.find()) {
            int line = getLineNumber(lo, elm.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.MUST_FIX, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                "I noticed @EventListener on a private method.",
                "Spring's event publishing uses AOP proxies which cannot intercept private methods. The event handler will never be invoked. Make the method public.",
                "@EventListener\npublic void handleEvent(MyEvent event) { ... }"));
        }

        // final class annotated with @Component / @Service — CGLIB subclass proxy fails
        Matcher fcm = P_FINAL_COMPONENT.matcher(content);
        if (fcm.find()) {
            int line = getLineNumber(lo, fcm.start());
            if (isInChangedLines(file, line)) {
                findings.add(new Finding(Severity.MUST_FIX, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                    "I noticed a final class annotated with a Spring stereotype.",
                    "Spring uses CGLIB to create subclass-based proxies for beans. A final class cannot be subclassed, which will cause an application startup failure when Spring tries to proxy it (e.g. for @Transactional, @Cacheable, @Async).",
                    "// Remove 'final', or implement an interface and use JDK dynamic proxy:\npublic class MyService implements IMyService { ... }"));
            }
        }

        // Spring Boot 3+ requires Jakarta EE (jakarta.*) — flag any remaining javax.* imports
        if (config.springBootVersion >= 3) {
            Pattern P_JAVAX = Pattern.compile("^import\\s+javax\\.(servlet|persistence|validation|transaction|annotation)\\.", Pattern.MULTILINE);
            m = P_JAVAX.matcher(content);
            while (m.find()) {
                int line = getLineNumber(lo, m.start());
                if (!isInChangedLines(file, line)) continue;
                String pkg = m.group(1);
                findings.add(new Finding(Severity.MUST_FIX, Category.SPRING_BOOT, file.name, line, lines[line - 1].trim(),
                    "javax." + pkg + ".* import detected in a Spring Boot 3 project.",
                    "Spring Boot 3 migrated from Java EE (javax.*) to Jakarta EE (jakarta.*). "
                    + "The javax." + pkg + " package no longer exists on the classpath — this will cause a compilation error.",
                    "Replace: import javax." + pkg + ".*\nWith:    import jakarta." + pkg + ".*"));
            }
        }
    }

    private static void reviewOpenApi(String content, String[] lines, ChangedFile file, List<Finding> findings, AnalysisContext context, int[] lo) {
        // Only relevant for REST controllers
        if (!context.isController) return;

        // Rule 1: @RestController / @Controller missing @Tag annotation (OpenAPI grouping)
        if (!content.contains("@Tag(")) {
            // Find the class declaration line to report on
            int classLine = -1;
            for (int i = 0; i < Math.min(lines.length, 60); i++) {
                if (lines[i].contains("class ") && (lines[i].contains("@RestController") || lines[i].contains("@Controller")
                        || (i > 0 && (lines[i-1].contains("@RestController") || lines[i-1].contains("@Controller"))))) {
                    classLine = i + 1;
                    break;
                }
                if (lines[i].contains("class ") && context.classAnnotations.contains("RestController")) {
                    classLine = i + 1;
                    break;
                }
            }
            if (classLine > 0 && isInChangedLines(file, classLine)) {
                findings.add(new Finding(Severity.CONSIDER, Category.OPEN_API, file.name, classLine, lines[classLine-1].trim(),
                    "I noticed this REST controller is missing an @Tag annotation.",
                    "@Tag groups related endpoints together in the OpenAPI/Swagger UI, making the API easier to navigate for consumers. It maps to the 'tag' section in the generated OpenAPI spec.",
                    "@Tag(name = \"" + file.name.replace(".java", "") + "\", description = \"APIs for ...\")"));
            }
        }

        // Rule 2: Mapping annotation without @Operation (undocumented endpoint)
        Matcher m = P_MAPPING_ANNO.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            // Check the 6 lines above this mapping annotation for @Operation
            boolean hasOperation = false;
            for (int i = Math.max(0, line - 7); i < line - 1; i++) {
                if (lines[i].contains("@Operation")) {
                    hasOperation = true;
                    break;
                }
            }
            if (!hasOperation) {
                findings.add(new Finding(Severity.CONSIDER, Category.OPEN_API, file.name, line, lines[line-1].trim(),
                    "I noticed this endpoint is missing an @Operation annotation.",
                    "@Operation provides a human-readable summary and description for the endpoint in the generated OpenAPI spec / Swagger UI. Without it, API consumers see no documentation for what this endpoint does.",
                    "@Operation(summary = \"Short description\", description = \"Detailed description of what this endpoint does\")"));
            }
        }

        // Rule 3: @Operation present but no @ApiResponse declared (no documented response codes)
        if (content.contains("@Operation") && !content.contains("@ApiResponse")) {
            Matcher om = Pattern.compile("@Operation\\b").matcher(content);
            while (om.find()) {
                int line = getLineNumber(lo, om.start());
                if (!isInChangedLines(file, line)) continue;
                findings.add(new Finding(Severity.CONSIDER, Category.OPEN_API, file.name, line, lines[line-1].trim(),
                    "I noticed @Operation is used but no @ApiResponse annotations are declared.",
                    "@ApiResponse documents the possible HTTP response codes and their meaning for this endpoint. Without it, the OpenAPI spec won't describe what 200, 400, 404, or 500 responses look like.",
                    "@ApiResponses({\n    @ApiResponse(responseCode = \"200\", description = \"Successful operation\"),\n    @ApiResponse(responseCode = \"400\", description = \"Invalid input\"),\n    @ApiResponse(responseCode = \"404\", description = \"Not found\")\n})"));
            }
        }

        // Rule 4: Method parameter with complex object type in controller but no @Parameter or @Schema
        m = P_METHOD_PARAM.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            // Check if @Parameter annotation is present on preceding line
            String prevLine = line > 1 ? lines[line - 2].trim() : "";
            if (!prevLine.contains("@Parameter")) {
                findings.add(new Finding(Severity.CONSIDER, Category.OPEN_API, file.name, line, lines[line-1].trim(),
                    "I noticed an endpoint parameter without an @Parameter annotation.",
                    "@Parameter documents what this parameter does, whether it's required, and its example values in the OpenAPI spec. This is especially useful for path variables and query parameters.",
                    "@Parameter(description = \"Description of " + m.group(1) + "\", required = true, example = \"example-value\")"));
            }
        }
    }

    private static void reviewPerformance(String content, String[] lines, ChangedFile file, List<Finding> findings, AnalysisContext context, int[] lo) {
        // orElse(expensiveCall())
        Matcher m = P_OR_ELSE.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String call = m.group(1);
            if (call.contains("()")) {
                // Only flag when the argument looks like a potentially expensive operation.
                // Cheap factory methods (Optional.of, List.of, Collections.emptyList, etc.) are fine.
                boolean looksExpensive = call.startsWith("new ")
                    || call.matches("(?i).*(find|fetch|load|query|search|request|invoke|execute|send|select|retrieve|compute|calculate|call)\\w*\\(.*")
                    || call.contains("Repository.")
                    || call.contains("Service.")
                    || call.contains("Client.");
                if (!looksExpensive) continue;
                findings.add(new Finding(Severity.SHOULD_FIX, Category.PERFORMANCE, file.name, line, lines[line-1].trim(),
                    "We might want to use .orElseGet() here.",
                    ".orElse() evaluates its argument even if the Optional is present, which can be unnecessary work if the method call is expensive. .orElseGet() is lazy — it only calls the supplier when the value is absent.",
                    ".orElseGet(() -> " + call + ")"));
            }
        }

        // String concatenation in loops
        m = P_CONCAT_LOOP.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String concatLine = lines[line-1].trim();
            // Extract the string variable being concatenated (e.g. "result += item" → "result")
            String strVar = "result";
            Matcher concatM = Pattern.compile("(\\w+)\\s*\\+?=").matcher(concatLine);
            if (concatM.find()) strVar = concatM.group(1);
            findings.add(new Finding(Severity.SHOULD_FIX, Category.PERFORMANCE, file.name, line, concatLine,
                "String concatenation in a loop is inefficient.",
                "This creates a new String object in every iteration, which can be slow. StringBuilder is designed exactly for this and is much faster.",
                "StringBuilder " + strVar + "Sb = new StringBuilder();\nfor (...) {\n    " + strVar + "Sb.append(...);\n}\nString " + strVar + " = " + strVar + "Sb.toString();"));
        }

        // Thread.sleep in production code
        m = P_THREAD_SLEEP.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.CONSIDER, Category.PERFORMANCE, file.name, line, lines[line-1].trim(),
                "I noticed a Thread.sleep() call here.",
                "Thread.sleep hurts responsiveness and is often flaky in tests or production. If you're waiting for something, 'awaitility' or a scheduled executor is usually more robust.",
                "// Use awaitility for conditions\nawait().atMost(5, SECONDS).until(() -> condition);\n\n// Or Schedule for delays\nscheduler.schedule(task, 5, SECONDS);"));
        }
    }

    private static void reviewCodeQuality(String content, String[] lines, ChangedFile file, List<Finding> findings, AnalysisContext context, Config config, int[] lo) {
        // Boxed types compared with ==
        Matcher m = P_BOXED_CMP.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            // Guard: ensure == actually appears on the same line as the boxed type token
            String cmpLine = lines[line - 1];
            if (!cmpLine.contains("==") && !cmpLine.contains("!=")) continue;
            if (!isInChangedLines(file, line)) continue;
            String cmpLineTrimmed = cmpLine.trim();
            // Extract the two operands (e.g. "count == other" -> lhs="count", rhs="other")
            String lhsVar = "a", rhsVar = "b";
            Matcher cmpVarM = Pattern.compile("(\\w+)\\s*(?:==|!=)\\s*(\\w+)").matcher(cmpLineTrimmed);
            if (cmpVarM.find()) { lhsVar = cmpVarM.group(1); rhsVar = cmpVarM.group(2); }
            findings.add(new Finding(Severity.MUST_FIX, Category.CODE_QUALITY, file.name, line, cmpLineTrimmed,
                "I noticed we're comparing boxed types using '=='.",
                "Using '==' on Integer, Long, or Boolean compares object identity (memory address), not the actual value. This can work for cached values (-128 to 127) but fails for others, causing subtle bugs.",
                "Objects.equals(" + lhsVar + ", " + rhsVar + ")"));
        }

        // Hardcoded credentials
        m = P_HARDCODED_CRED.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String credLine = lines[line-1].trim();
            // Extract field name (e.g. "private String apiKey = \"...\"" -> "apiKey")
            String credField = "secret";
            Matcher credFieldM = Pattern.compile("(?:private|protected|public)?\\s+\\w+\\s+(\\w+)\\s*=").matcher(credLine);
            if (credFieldM.find()) credField = credFieldM.group(1);
            // Derive property key from field name (camelCase -> dot.case)
            String propKey = credField.replaceAll("([A-Z])", ".$1").toLowerCase().replaceAll("^\\.+", "");
            findings.add(new Finding(Severity.MUST_FIX, Category.CODE_QUALITY, file.name, line, credLine,
                "I spotted a hardcoded credential here.",
                "Hardcoding secrets is a security risk. If this code gets shared, the secret goes with it. It's better to load this from a secure config.",
                "@Value(\"${" + propKey + "}\")\nprivate String " + credField + ";"));
        }

        // TODO/FIXME markers
        m = P_TODO_FIXME.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.CONSIDER, Category.LOGGING, file.name, line, lines[line-1].trim(),
                "I noticed some TODO or FIXME markers in the code.",
                "It's always good to keep track of pending work, but we should try to resolve these or move them to a formal tracking system before the final release to keep the codebase clean.",
                "// Jira: PROJ-123"));
        }

        // equals/hashCode mismatch: class overrides one without the other
        if (content.contains("equals(") && !content.contains("hashCode(")) {
            int line = getLineNumber(lo, content.indexOf("equals("));
            if (isInChangedLines(file, line)) {
                findings.add(new Finding(Severity.SHOULD_FIX, Category.CODE_QUALITY, file.name, line, lines[line-1].trim(),
                    "It looks like equals() is overridden without hashCode().",
                    "In Java, if you override equals(), you should also override hashCode() to maintain the contract. This is crucial for things to work correctly when this class is used in sets or maps.",
                    "@Override\npublic int hashCode() {\n    return Objects.hash(field1, field2); // Include key fields\n}"));
            }
        } else if (content.contains("hashCode(") && !content.contains("equals(")) {
            int line = getLineNumber(lo, content.indexOf("hashCode("));
            if (isInChangedLines(file, line)) {
                findings.add(new Finding(Severity.SHOULD_FIX, Category.CODE_QUALITY, file.name, line, lines[line-1].trim(),
                    "I noticed hashCode() is overridden without equals().",
                    "Overriding hashCode() without equals() can lead to inconsistent behavior in collections. It's best to implement both to ensure equality works as expected.",
                    "@Override\npublic boolean equals(Object o) {\n    if (this == o) return true;\n    if (!(o instanceof " + context.className + ")) return false;\n    // ... compare fields\n}"));
            }
        }

        // Thread.sleep sanity: suspicious very low or very high literals
        m = P_SLEEP_LITERAL.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            long val = Long.parseLong(m.group(1));
            if (val < 10 || val > 60000) {
                findings.add(new Finding(Severity.CONSIDER, Category.PERFORMANCE, file.name, line, lines[line-1].trim(),
                    "The Thread.sleep() duration looks a bit odd.",
                    "Extremely small or large values often happen when we mix up milliseconds and seconds. It's usually clearer to use Duration or TimeUnit.",
                    "TimeUnit.SECONDS.sleep(5);"));
            }
        }

        // Builder + public setters conflict
        if (content.contains("@Builder") && content.matches("(?s).*public\\s+void\\s+set\\w+\\(.*")) {
            int line = getLineNumber(lo, content.indexOf("@Builder"));
            if (isInChangedLines(file, line)) {
                findings.add(new Finding(Severity.CONSIDER, Category.CODE_QUALITY, file.name, line, lines[line-1].trim(),
                    "I noticed @Builder alongside public setters.",
                    "The Builder pattern is usually used for immutable objects. Having public setters kind of defeats that purpose and can lead to objects being modified unexpectedly.",
                    "@Builder\n@Getter // Prefer Getter only to keep it immutable\npublic class MyClass { ... }"));
            }
        }

        // Hardcoded string literals (heuristic)
        m = P_SIMPLE_LITERAL.matcher(content);
        Set<String> seenLiteralsOnLine = new HashSet<>();
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String literal = m.group(1);
            if (LITERAL_ALLOWLIST.contains(literal) || LITERAL_ALLOWLIST.contains(literal.toUpperCase())) continue;
            String sourceLine = lines[line-1];
            if (sourceLine.contains("log.") || sourceLine.matches(".*\\blog\\.(info|debug|warn|error|trace)\\(.*")) continue; // allow log messages
            // Skip annotation values — they are intentionally literal
            if (sourceLine.trim().startsWith("@")) continue;
            // Deduplication: Avoid double-flagging if it's already caught by literalEquals (literal.equals("...") or IgnoreCase)
            if (sourceLine.contains(".equals(\"" + literal + "\")") || sourceLine.contains("IgnoreCase(\"" + literal + "\")")) continue;
            
            String key = line + ":" + literal;
            if (seenLiteralsOnLine.contains(key)) continue;
            seenLiteralsOnLine.add(key);

            findings.add(new Finding(Severity.SHOULD_FIX, Category.CODE_QUALITY, file.name, line, sourceLine.trim(),
                "I noticed a hardcoded literal value here.",
                "Hardcoded identifiers make future changes brittle and hide intent. It's usually better to pull these into a named constant so the code is easier to read and maintain.",
                "private static final String CONSTANT_NAME = \"" + literal + "\";"));
        }

        // Hardcoded numeric literals (non-string) with length >= 2
        m = P_NUMBER_LITERAL.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String sourceLine = lines[line-1];
            if (sourceLine.contains("for (int") || sourceLine.contains("case ")) continue;
            // Skip HTTP status codes and common constants context
            String val = m.group(1);
            if (val.equals("200") || val.equals("201") || val.equals("204") ||
                val.equals("400") || val.equals("401") || val.equals("403") ||
                val.equals("404") || val.equals("409") || val.equals("500") ||
                val.equals("503")) continue;
            // Skip annotation values (e.g. @Scheduled(fixedRate = 60000))
            if (sourceLine.trim().startsWith("@")) continue;
            findings.add(new Finding(Severity.SHOULD_FIX, Category.CODE_QUALITY, file.name, line, sourceLine.trim(),
                "I spotted a magic number here.",
                "Using raw numbers like " + m.group(1) + " can make the code's purpose unclear. It's much easier for the team to understand what this represents if it's stored in a well-named constant.",
                "private static final int CONSTANT_NAME = " + m.group(1) + ";"));
        }

        // Uppercase literals repeated -> promote to constants/enums
        m = P_DOMAIN_LITERAL.matcher(content);
        Map<String, List<Integer>> literalOccurrences = new HashMap<>();
        while (m.find()) {
            literalOccurrences.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(getLineNumber(lo, m.start()));
        }
        for (Map.Entry<String, List<Integer>> entry : literalOccurrences.entrySet()) {
            if (entry.getValue().size() < 2 || LITERAL_ALLOWLIST.contains(entry.getKey())) continue;
            int flagged = entry.getValue().stream().filter(l -> isInChangedLines(file, l)).findFirst().orElse(-1);
            if (flagged == -1) continue;
            String code = lines[flagged - 1].trim();
            findings.add(new Finding(Severity.SHOULD_FIX, Category.CODE_QUALITY, file.name, flagged, code,
                "I noticed the literal \"" + entry.getKey() + "\" appears multiple times.",
                "When we repeat the same string token, we risk typos and it makes updating the value harder. Centralizing this in a constant or enum keeps everyone on the same page.",
                "private static final String " + entry.getKey() + " = \"" + entry.getKey() + "\";"));
        }

        // Literal equals on possibly null receiver with constant suggestion
        m = P_LIT_EQUALS.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String code = lines[line - 1].trim();
            String receiver = m.group(1);
            String literal = m.group(3);
            
            // Sanitize receiver: remove leading if(, while(, etc. and matching parens
            if (receiver.startsWith("if(") || receiver.startsWith("while(") || receiver.startsWith("for(")) {
                receiver = receiver.substring(receiver.indexOf('(') + 1);
            }
            if (receiver.startsWith("(") && !receiver.endsWith(")")) {
                receiver = receiver.substring(1);
            }

            if (receiver.startsWith("\"")) continue;

            String constName = "EXPECTED_" + literal.replaceAll("[^A-Za-z0-9]", "_").toUpperCase();
            if (constName.length() > 30) constName = constName.substring(0, 30);
            while (constName.endsWith("_")) constName = constName.substring(0, constName.length() - 1);
            if (constName.isEmpty() || Character.isDigit(constName.replace("EXPECTED_", "").charAt(0))) constName = "VAL_" + constName;

            String fixExpression = constName + "." + m.group(2) + "(" + receiver + ")";

            findings.add(new Finding(Severity.SHOULD_FIX, Category.CODE_QUALITY, file.name, line, code,
                "I noticed the literal \"" + literal + "\" is used with a potentially null object.",
                "Calling .equals() on a variable that might be null can cause a NullPointerException. Using a constant and flipping the comparison handles null checks safely.",
                "private static final String " + constName + " = \"" + literal + "\";\n// ...\n" + fixExpression));
        }

        // Potential infinite while(true) without exit markers
        m = P_WHILE_TRUE.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            int bodyEnd = content.indexOf("}", m.end());
            if (bodyEnd == -1) continue;
            String body = content.substring(m.end(), Math.min(bodyEnd, content.length()));
            if (body.contains("break;") || body.contains("return") || body.contains("throw")) continue;
            String code = lines[line - 1].trim();
            findings.add(new Finding(Severity.MUST_FIX, Category.CODE_QUALITY, file.name, line, code,
                "I noticed a while(true) loop that doesn't seem to have a clear exit.",
                "Without a break, return, or throw inside, this loop could potentially run forever and hang the thread. It's usually safer to have an explicit exit condition.",
                "// Inside the loop:\nif (shouldStop) {\n    break;\n}"));
        }

        // Resource leak detection (already handled by reviewBugPatterns)

        // Multi-line: Deep nesting detection (heuristic)
        m = P_DEEP_NESTING.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.CONSIDER, Category.CODE_QUALITY, file.name, line, lines[line-1].trim(),
                "This logic is starting to look a bit complex with all these nested blocks.",
                "Deeply nested code can be tough for the team to follow and test. We might want to look at simplifying this—maybe by using early returns or breaking some of this out into helper methods.",
                "// Example of early return:\nif (!condition) return;\n// Continue execution..."));
        }

        // Actionable recommendation: Suggested constant name (combined for same line)
        m = P_LITERAL_CONST.matcher(content);
        Map<Integer, List<String>> tokensByLine = new HashMap<>();
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String val = m.group(1);
            String sourceLine = lines[line-1];
            if (sourceLine.contains("static final")) continue;
            if (LITERAL_ALLOWLIST.contains(val)) continue;
            if (sourceLine.contains("log.") || sourceLine.matches(".*\\blog\\.(info|debug|warn|error|trace)\\(.*")) continue; // Ignore log messages
            tokensByLine.computeIfAbsent(line, k -> new ArrayList<>()).add(val);
        }

        for (Map.Entry<Integer, List<String>> entry : tokensByLine.entrySet()) {
            int line = entry.getKey();
            List<String> tokens = entry.getValue();
            String code = lines[line - 1].trim();
            
            if (tokens.size() == 1) {
                String val = tokens.get(0);
                String constName = val.replaceAll("[^A-Za-z0-9]", "_").toUpperCase();
                if (constName.isEmpty() || Character.isDigit(constName.charAt(0))) constName = "TOKEN_" + constName;

                findings.add(new Finding(Severity.SHOULD_FIX, Category.CODE_QUALITY, file.name, line, code,
                    "I noticed a hardcoded business token '" + val + "' here.",
                    "It's usually safer to pull these into a named constant. This makes the code easier to maintain and prevents the same value from drifting apart in different places.",
                    "private static final String " + constName + " = \"" + val + "\";"));
            } else {
                StringBuilder fix = new StringBuilder();
                for (String t : tokens) {
                    String constName = t.replaceAll("[^A-Za-z0-9]", "_").toUpperCase();
                    if (constName.isEmpty() || Character.isDigit(constName.charAt(0))) constName = "TOKEN_" + constName;
                    fix.append("private static final String ").append(constName).append(" = \"").append(t).append("\";\n");
                }
                findings.add(new Finding(Severity.SHOULD_FIX, Category.CODE_QUALITY, file.name, line, code,
                    "It looks like there are a few hardcoded tokens here: " + String.join(", ", tokens),
                    "Consolidating these into constants would definitely make the code cleaner and more robust against future changes.",
                    fix.toString().trim()));
            }
        }

        if (config.strictJava) {
            // String comparison using == or !=
            // Capture groups: 1=var, 2=op, 3=lit OR 4=lit, 5=op, 6=var
            m = P_STRING_EQ.matcher(content);
            while (m.find()) {
                int line = getLineNumber(lo, m.start());
                if (!isInChangedLines(file, line)) continue;
                
                String var = m.group(1);
                String op = m.group(2);
                String literal = m.group(3);
                
                if (var == null) {
                    literal = m.group(4);
                    op = m.group(5);
                    var = m.group(6);
                }
                
                String fix = "!=".equals(op) ? "!" + literal + ".equals(" + var + ")" : literal + ".equals(" + var + ")";
                
                findings.add(new Finding(Severity.MUST_FIX, Category.CODE_QUALITY, file.name, line, lines[line-1].trim(),
                    "I noticed string comparison using '" + op + "'.",
                    "In Java, '" + op + "' compares object references (memory addresses), not the actual string content. This might work for interned strings but is risky in production.",
                    fix));
            }
        }
    }

    private static void reviewJavaModern(String content, String[] lines, ChangedFile file, List<Finding> findings, Config config, int[] lo) {
        // 1. Legacy java.util.Date / Calendar / SimpleDateFormat usage
        Matcher m = P_LEGACY_DATE.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String code = lines[line - 1].trim();
            if (code.contains("//") || code.startsWith("*")) continue; // skip comments
            findings.add(new Finding(Severity.SHOULD_FIX, Category.CODE_QUALITY, file.name, line, code,
                "I noticed use of legacy java.util.Date / Calendar / SimpleDateFormat.",
                "These classes are mutable, not thread-safe, and have confusing API design. Java 8+ introduced java.time (LocalDate, ZonedDateTime, DateTimeFormatter) as a clean replacement.",
                "// Use java.time instead:\nLocalDateTime now = LocalDateTime.now();\nDateTimeFormatter fmt = DateTimeFormatter.ofPattern(\"yyyy-MM-dd\");"));
        }

        // 2. Raw collection types (missing generic type parameter)
        m = P_RAW_COLL.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String typeName = m.group(1);
            String code = lines[line - 1].trim();
            findings.add(new Finding(Severity.SHOULD_FIX, Category.CODE_QUALITY, file.name, line, code,
                "I noticed a raw '" + typeName + "' without a type parameter.",
                "Raw types skip generics type checking and can cause ClassCastException at runtime. Use the diamond operator or explicit type to keep it type-safe.",
                "new " + typeName + "<>()  // or: new " + typeName + "<ExpectedType>()"));
        }

        // 3. Collections.EMPTY_LIST / EMPTY_SET / EMPTY_MAP (type-unsafe, use emptyList() etc.)
        m = P_EMPTY_COLLS.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            String field = m.group(1);
            String replacement = field.equals("EMPTY_LIST") ? "emptyList()" : field.equals("EMPTY_SET") ? "emptySet()" : "emptyMap()";
            findings.add(new Finding(Severity.SHOULD_FIX, Category.CODE_QUALITY, file.name, line, lines[line-1].trim(),
                "I noticed " + field + " is being used instead of the type-safe alternative.",
                "Collections.EMPTY_LIST/SET/MAP are raw types and will generate unchecked warnings. The type-safe Collections.emptyList/emptySet/emptyMap() methods were introduced in Java 5 for exactly this reason.",
                "Collections." + replacement));
        }

        // 4. Double-brace initialization (creates anonymous inner class — memory leak risk)
        m = P_DOUBLE_BRACE.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.SHOULD_FIX, Category.CODE_QUALITY, file.name, line, lines[line-1].trim(),
                "I noticed double-brace initialization here.",
                "Double-brace initialization creates an anonymous inner class that holds an implicit reference to the outer class. This can cause memory leaks, prevents equals() from working correctly, and breaks serialization. Use Map.of(), List.of(), or explicit add() calls instead.",
                "// Use factory methods:\nMap<String, String> map = new HashMap<>(Map.of(\"key\", \"value\"));\n// Or explicit:\nList<String> list = new ArrayList<>();\nlist.add(\"item\");"));
        }

        // 5. Math.random() for anything non-trivial (use ThreadLocalRandom or SecureRandom)
        m = P_MATH_RANDOM.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.SHOULD_FIX, Category.CODE_QUALITY, file.name, line, lines[line-1].trim(),
                "I noticed Math.random() being used.",
                "Math.random() uses a shared, unsynchronized Random instance which can be a bottleneck under concurrency. Use ThreadLocalRandom.current().nextDouble() for performance, or SecureRandom for security-sensitive cases.",
                "// For general use:\ndouble val = ThreadLocalRandom.current().nextDouble();\n// For security-sensitive:\ndouble val = new SecureRandom().nextDouble();"));
        }

        // 6. instanceof + explicit cast on next token (suggest Java 16+ pattern matching)
        if (config.javaSourceVersion >= 16) {
            m = P_INSTANCEOF_CAST.matcher(content);
            while (m.find()) {
                int line = getLineNumber(lo, m.start());
                if (!isInChangedLines(file, line)) continue;
                String castType = m.group(1);
                findings.add(new Finding(Severity.CONSIDER, Category.CODE_QUALITY, file.name, line, lines[line-1].trim(),
                    "I noticed an instanceof check followed by a cast to " + castType + ".",
                    "Pattern matching for instanceof (Java 16+) lets you combine the check and cast in one step, eliminating the redundant cast and making the code cleaner.",
                    "// Java 16+ pattern matching:\nif (obj instanceof " + castType + " typed) {\n    typed.doSomething();\n}"));
            }
        }
    }

    // -----------------------------------------------------------------------
    // SOAP / JAX-WS
    // -----------------------------------------------------------------------
    private static void reviewSoap(String content, String[] lines, ChangedFile file,
                                    List<Finding> findings, AnalysisContext context, int[] lo) {
        if (!context.isSoapEndpoint) return;

        // @WebService missing targetNamespace — WSDL portType will use package name, breaks clients
        Matcher m = P_WEBSERVICE_NO_NS.matcher(content);
        if (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (isInChangedLines(file, line)) {
                findings.add(new Finding(Severity.SHOULD_FIX, Category.SOAP, file.name, line, lines[line-1].trim(),
                    "@WebService is missing a targetNamespace.",
                    "Without an explicit targetNamespace the WSDL uses the Java package name as the namespace. "
                    + "This makes the contract fragile — any package rename silently breaks all WSDL-bound clients.",
                    "@WebService(name = \"MyService\", targetNamespace = \"http://example.com/myservice\")"));
            }
        }

        // @WebMethod missing action — SOAPAction header will be empty, confuses some WS stacks
        m = P_WEBMETHOD_NO_ACTION.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.CONSIDER, Category.SOAP, file.name, line, lines[line-1].trim(),
                "@WebMethod is missing an explicit action.",
                "Without an 'action' attribute the SOAPAction header is empty. Some WS stacks and API gateways "
                + "rely on SOAPAction for routing — making it explicit improves compatibility.",
                "@WebMethod(action = \"http://example.com/myservice/operationName\")"));
        }

        // @WebParam missing name — WSDL parameter names become 'arg0', 'arg1' etc.
        m = P_WEBPARAM_NO_NAME.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.SHOULD_FIX, Category.SOAP, file.name, line, lines[line-1].trim(),
                "@WebParam is missing a name attribute.",
                "Without an explicit name the generated WSDL uses positional names (arg0, arg1) which makes "
                + "the contract unreadable and brittle for callers.",
                "@WebParam(name = \"customerId\") String customerId"));
        }

        // Exception thrown from @WebMethod without @WebFault — becomes an unmapped fault
        if (content.contains("@WebMethod") && content.contains("throws ") && !content.contains("@WebFault")) {
            int idx = content.indexOf("@WebMethod");
            int line = getLineNumber(lo, idx);
            if (isInChangedLines(file, line)) {
                findings.add(new Finding(Severity.SHOULD_FIX, Category.SOAP, file.name, line, lines[line-1].trim(),
                    "@WebMethod throws a checked exception without a corresponding @WebFault.",
                    "Checked exceptions thrown by a JAX-WS @WebMethod must be annotated with @WebFault to be "
                    + "properly mapped to a WSDL fault element. Without it the exception becomes a generic SOAP fault "
                    + "and callers lose the ability to handle it programmatically.",
                    "@WebFault(name = \"ServiceFault\", targetNamespace = \"http://example.com/\")"
                    + "\npublic class ServiceFaultException extends Exception { ... }"));
            }
        }
    }

    // -----------------------------------------------------------------------
    // gRPC
    // -----------------------------------------------------------------------
    private static void reviewGrpc(String content, String[] lines, ChangedFile file,
                                    List<Finding> findings, AnalysisContext context, int[] lo) {
        if (!context.isGrpcClient) return;

        // Stub created without a deadline — hangs forever on unresponsive server
        Matcher m = P_GRPC_NO_DEADLINE.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.MUST_FIX, Category.GRPC, file.name, line, lines[line-1].trim(),
                "gRPC stub created without a deadline.",
                "A gRPC stub without a deadline will wait indefinitely if the remote server is slow or unresponsive. "
                + "This blocks threads and can cascade into a full service hang.",
                "stub.withDeadlineAfter(5, TimeUnit.SECONDS).myRpcCall(request)"));
        }

        // ManagedChannel built without an interceptor — no tracing, auth, or retry
        m = P_GRPC_NO_INTERCEPT.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.CONSIDER, Category.GRPC, file.name, line, lines[line-1].trim(),
                "ManagedChannel built without any interceptor.",
                "Without interceptors the channel has no observability (tracing), no retry policy, and no auth header "
                + "injection. Consider adding a ClientInterceptor for cross-cutting concerns.",
                "ManagedChannelBuilder.forAddress(host, port)\n"
                + "    .intercept(new TracingClientInterceptor())\n"
                + "    .build()"));
        }

        // ManagedChannel not shut down — resource/port leak
        if (content.contains("ManagedChannel") && !content.contains(".shutdown()") && !content.contains(".shutdownNow()")) {
            int idx = content.indexOf("ManagedChannel");
            int line = getLineNumber(lo, idx);
            if (isInChangedLines(file, line)) {
                findings.add(new Finding(Severity.MUST_FIX, Category.GRPC, file.name, line, lines[line-1].trim(),
                    "ManagedChannel is created but never shut down.",
                    "A ManagedChannel that is never shut down keeps the underlying connection and thread pool alive "
                    + "indefinitely, leaking resources. Call shutdown() (or shutdownNow()) in a @PreDestroy or try-finally.",
                    "@PreDestroy\npublic void destroy() {\n    channel.shutdown();\n}"));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Outbound clients — Feign, WebClient, RestTemplate, JAXB
    // -----------------------------------------------------------------------
    private static void reviewOutboundClients(String content, String[] lines, ChangedFile file,
                                               List<Finding> findings, AnalysisContext context, int[] lo) {
        if (!context.isOutboundClient) return;

        // @FeignClient without fallback — any downstream failure propagates unchecked
        if (context.isFeignClient) {
            Matcher m = P_FEIGN_NO_FALLBACK.matcher(content);
            while (m.find()) {
                String body = m.group(0);
                if (body.contains("fallback") || body.contains("fallbackFactory")) continue;
                int line = getLineNumber(lo, m.start());
                if (!isInChangedLines(file, line)) continue;
                findings.add(new Finding(Severity.SHOULD_FIX, Category.OUTBOUND_CLIENT, file.name, line, lines[line-1].trim(),
                    "@FeignClient has no fallback defined.",
                    "Without a fallback, any failure in the remote service (timeout, 5xx, network error) throws an "
                    + "exception directly to the caller. A fallback class lets you return a safe default and "
                    + "keeps the circuit open cleanly.",
                    "@FeignClient(name = \"service\", fallback = MyClientFallback.class)"));
            }
        }

        // WebClient.builder() chain — flag if no responseTimeout is set
        if (content.contains("WebClient")) {
            Matcher m = P_WEBCLIENT_NO_TIMEOUT.matcher(content);
            while (m.find()) {
                int line = getLineNumber(lo, m.start());
                if (!isInChangedLines(file, line)) continue;
                findings.add(new Finding(Severity.SHOULD_FIX, Category.OUTBOUND_CLIENT, file.name, line, lines[line-1].trim(),
                    "WebClient.builder() configured without a response timeout.",
                    "A WebClient without a timeout will wait indefinitely for a slow or unresponsive downstream "
                    + "service. This ties up reactive threads and can cascade into a full service outage.",
                    "WebClient.builder()\n"
                    + "    .responseTimeout(Duration.ofSeconds(5))\n"
                    + "    .build()"));
            }

            // .block() on a reactive chain in a non-scheduled context — defeats non-blocking purpose
            m = P_WEBCLIENT_BLOCK.matcher(content);
            while (m.find()) {
                int line = getLineNumber(lo, m.start());
                if (!isInChangedLines(file, line)) continue;
                findings.add(new Finding(Severity.SHOULD_FIX, Category.OUTBOUND_CLIENT, file.name, line, lines[line-1].trim(),
                    "WebClient reactive chain blocked with .block().",
                    ".block() converts the reactive call to a blocking one, tying up a thread and "
                    + "defeating the non-blocking benefit of WebClient. Prefer returning Mono/Flux and "
                    + "letting the framework subscribe.",
                    "// Instead of: String result = client.get().retrieve().bodyToMono(String.class).block()\n"
                    + "// Return:     Mono<String> result = client.get().retrieve().bodyToMono(String.class)"));
            }
        }

        // new RestTemplate() without timeout configuration
        if (content.contains("RestTemplate")) {
            Matcher m = P_RESTTPL_NO_TIMEOUT.matcher(content);
            while (m.find()) {
                int line = getLineNumber(lo, m.start());
                if (!isInChangedLines(file, line)) continue;
                findings.add(new Finding(Severity.SHOULD_FIX, Category.OUTBOUND_CLIENT, file.name, line, lines[line-1].trim(),
                    "new RestTemplate() created without timeout configuration.",
                    "A default RestTemplate has no connect or read timeout — it will block indefinitely on a "
                    + "slow downstream service. Use RestTemplateBuilder with explicit timeouts.",
                    "RestTemplate restTemplate = new RestTemplateBuilder()\n"
                    + "    .connectTimeout(Duration.ofSeconds(3))\n"
                    + "    .readTimeout(Duration.ofSeconds(5))\n"
                    + "    .build()"));
            }
        }

        // JAXBContext.newInstance outside a try-catch — JAXBException is checked
        if (content.contains("JAXBContext")) {
            Matcher m = P_JAXB_NO_TRY.matcher(content);
            while (m.find()) {
                int line = getLineNumber(lo, m.start());
                if (!isInChangedLines(file, line)) continue;
                // Rough check: if there's no enclosing try block in the preceding 5 lines
                boolean inTry = false;
                for (int i = Math.max(0, line - 6); i < line - 1; i++) {
                    if (lines[i].contains("try")) { inTry = true; break; }
                }
                if (inTry) continue;
                findings.add(new Finding(Severity.SHOULD_FIX, Category.OUTBOUND_CLIENT, file.name, line, lines[line-1].trim(),
                    "JAXBContext.newInstance() is not wrapped in a try-catch.",
                    "JAXBContext.newInstance() throws a checked JAXBException if the class is not a valid JAXB "
                    + "annotated type. Without a try-catch this becomes an unchecked runtime failure that is "
                    + "hard to diagnose in production.",
                    "try {\n"
                    + "    JAXBContext ctx = JAXBContext.newInstance(MyDto.class);\n"
                    + "} catch (JAXBException e) {\n"
                    + "    throw new RuntimeException(\"JAXB init failed\", e);\n"
                    + "}"));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Quartz scheduler
    // -----------------------------------------------------------------------
    private static void reviewQuartz(String content, String[] lines, ChangedFile file,
                                      List<Finding> findings, int[] lo) {
        if (!content.contains("JobDetail") && !content.contains("implements Job")) return;

        // Schedule built without misfire handling — silent job skips under load
        Matcher m = P_QUARTZ_NO_MISFIRE.matcher(content);
        while (m.find()) {
            int line = getLineNumber(lo, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.SHOULD_FIX, Category.SCHEDULED, file.name, line, lines[line-1].trim(),
                "Quartz schedule built without a misfire instruction.",
                "Without a misfire instruction Quartz uses the default policy which silently skips the job "
                + "if it could not fire at its scheduled time (e.g. app was down). Define an explicit misfire "
                + "policy so the behaviour on recovery is predictable.",
                "SimpleScheduleBuilder.simpleSchedule()\n"
                + "    .withIntervalInSeconds(60)\n"
                + "    .withMisfireHandlingInstructionFireNow()\n"
                + "    .repeatForever()"));
        }

        // JobDetail without durability — orphaned job deleted when trigger unschedules
        if (content.contains("JobBuilder") && !content.contains(".storeDurably()")) {
            int idx = content.indexOf("JobBuilder");
            int line = getLineNumber(lo, idx);
            if (isInChangedLines(file, line)) {
                findings.add(new Finding(Severity.CONSIDER, Category.SCHEDULED, file.name, line, lines[line-1].trim(),
                    "JobDetail built without .storeDurably().",
                    "A non-durable job is automatically deleted from the scheduler when it has no associated "
                    + "triggers. If you ever need to re-trigger it manually (e.g. via JMX or an admin endpoint) "
                    + "the job definition will already be gone. Use .storeDurably() to keep the definition alive.",
                    "JobBuilder.newJob(MyJob.class)\n"
                    + "    .withIdentity(\"myJob\", \"group1\")\n"
                    + "    .storeDurably()\n"
                    + "    .build()"));
            }
        }

        // execute() method with no exception handling — Quartz swallows JobExecutionException silently
        if (content.contains("implements Job")) {
            Pattern executeMethod = Pattern.compile("public\\s+void\\s+execute\\s*\\(\\s*JobExecutionContext");
            Matcher em = executeMethod.matcher(content);
            if (em.find()) {
                int line = getLineNumber(lo, em.start());
                if (isInChangedLines(file, line) && !content.contains("JobExecutionException")) {
                    findings.add(new Finding(Severity.SHOULD_FIX, Category.SCHEDULED, file.name, line, lines[line-1].trim(),
                        "Quartz Job.execute() does not declare or handle JobExecutionException.",
                        "Quartz wraps unhandled runtime exceptions in a JobExecutionException internally, but "
                        + "the details are often lost or logged at the wrong level. Catch exceptions explicitly "
                        + "and wrap in JobExecutionException so Quartz can apply the configured retry/refireImmediately policy.",
                        "public void execute(JobExecutionContext ctx) throws JobExecutionException {\n"
                        + "    try {\n"
                        + "        doWork(ctx);\n"
                        + "    } catch (Exception e) {\n"
                        + "        throw new JobExecutionException(e, false);\n"
                        + "    }\n"
                        + "}"));
                }
            }
        }
    }

    private static boolean isInChangedLines(ChangedFile file, int line) {
        return file.changedLines.contains(line);
    }

    /** Legacy O(n) fallback — kept only for internal log-call line resolution. */
    private static int getLineNumber(String content, int index) {
        return (int) content.substring(0, Math.min(index, content.length())).chars().filter(c -> c == '\n').count() + 1;
    }
}
