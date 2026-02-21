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
    private static final Pattern P_OPTIONAL_NULL   = Pattern.compile("Optional<[^>]+>\\s+\\w+\\s*\\(.*?\\)\\s*\\{[^}]*return\\s+null;", Pattern.DOTALL);
    private static final Pattern P_FOREACH_COLL    = Pattern.compile("for\\s*\\(.*?:\\s*(\\w+)\\s*\\)");
    private static final Pattern P_NEW_STREAM      = Pattern.compile("new\\s+(FileInputStream|FileOutputStream|FileReader|FileWriter|BufferedReader|BufferedWriter|InputStreamReader|OutputStreamWriter|Scanner|PrintWriter|ZipInputStream|ZipOutputStream)\\s*\\(");
    // reviewNullSafety
    private static final Pattern P_OPTIONAL_OF     = Pattern.compile("Optional\\.of\\(([^)]+)\\)");
    private static final Pattern P_CHAINED_DEREF   = Pattern.compile("\\w+\\.\\w+\\(\\)\\.\\w+\\(");
    private static final Pattern P_LIST_GET        = Pattern.compile("\\.get\\((\\d+)\\)");
    private static final Pattern P_OPT_GET         = Pattern.compile("Optional\\s*<[^>]*>.*?\\.get\\(\\)", Pattern.DOTALL);
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
    private static final Pattern P_BOXED_CMP       = Pattern.compile("\\b(Integer|Long|Boolean)\\b.*?==.*?");
    private static final Pattern P_HARDCODED_CRED  = Pattern.compile("\"(?i)(password|passwd|secretKey|apiKey|token)=.+\"");
    private static final Pattern P_TODO_FIXME      = Pattern.compile("//\\s*(TODO|FIXME)");
    private static final Pattern P_WHILE_TRUE      = Pattern.compile("while\\s*\\(\\s*true\\s*\\)\\s*\\{");
    private static final Pattern P_DEEP_NESTING    = Pattern.compile("(?:if|for|while)\\s*\\(.*?\\)\\s*\\{\\s*(?:[^{}]*\\{[^{}]*\\}){3,}", Pattern.DOTALL);
    private static final Pattern P_SIMPLE_LITERAL  = Pattern.compile("\"([A-Za-z0-9_-]{2,})\"");
    private static final Pattern P_NUMBER_LITERAL  = Pattern.compile("(?<!\")\\b(\\d{2,})\\b(?!\")");
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
    // reviewOpenApi
    private static final Pattern P_MAPPING_ANNO    = Pattern.compile("@(?:GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)\\b");
    private static final Pattern P_METHOD_PARAM    = Pattern.compile("(?:@RequestParam|@PathVariable|@RequestHeader)\\s+(?:[\\w<>\\[\\]]+)\\s+(\\w+)");

    public static void runRules(String content, String[] lines, ChangedFile file, List<Finding> findings, Config config) {
        List<Range> methodRanges = findMethodRanges(lines);
        AnalysisContext context = new AnalysisContext();
        context.className = file.name.replace(".java", "");
        context.analyze(content, methodRanges, lines);

        reviewBugPatterns(content, lines, file, findings, context);
        reviewNullSafety(content, lines, file, findings, context);
        reviewExceptionHandling(content, lines, file, findings, context);
        reviewLogging(content, lines, file, findings, context, methodRanges);
        reviewSpringBoot(content, lines, file, findings, context, config);
        reviewOpenApi(content, lines, file, findings, context);
        reviewPerformance(content, lines, file, findings, context);
        reviewCodeQuality(content, lines, file, findings, context, config);
        reviewJavaModern(content, lines, file, findings, config);
        detectGoodPatterns(content, lines, file);
    }

    private static void detectGoodPatterns(String content, String[] lines, ChangedFile file) {
        // Look for modern Java patterns or robust logic
        if (content.contains("Optional.ofNullable")) {
            // Good pattern: usage of Optional for null safety
        }
        if (content.contains("try (") || content.contains("try(")) {
            // Good pattern: usage of try-with-resources
        }
        if (content.contains("Objects.equals")) {
            // Good pattern: null-safe equality
        }
    }

    private static Set<Integer> buildLoggingScope(ChangedFile file, String[] lines, List<Range> methodRanges) {
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

    private static void reviewBugPatterns(String content, String[] lines, ChangedFile file, List<Finding> findings, AnalysisContext context) {
        Matcher m = P_RESOURCE_OPEN.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            
            String varName = m.group(1);
            String code = lines[line - 1].trim();
            
            // Check if it's inside a try-with-resources header
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
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;

            String code = lines[line - 1].trim();
            String var1 = m.group(1);
            String var2 = m.group(2);

            if (code.contains("BigDecimal") || var1.toLowerCase().contains("amount") || var2.toLowerCase().contains("amount")) {
                findings.add(new Finding(Severity.SHOULD_FIX, Category.CODE_QUALITY, file.name, line, code,
                    "I noticed a BigDecimal comparison that might behave unexpectedly.",
                    "Using .equals() with BigDecimal checks both the value and the scale (so 1.0 isn't equal to 1.00). In most cases, we want to compare just the numerical values using .compareTo().",
                    var1 + ".compareTo(" + var2 + ") == 0"));
            }
        }

        // BigDecimal constructed from numeric literal is scale-sensitive and can lose intent
        m = P_BIGDECIMAL_CTOR.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
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
            int line = getLineNumber(content, m.start());
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
            int line = getLineNumber(content, m.start());
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
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            String code = lines[line - 1].trim();
            
            boolean inTryWithResources = false;
            for (int i = Math.max(1, line - 3); i <= line; i++) {
                if (lines[i-1].contains("try (") || lines[i-1].contains("try(")) { inTryWithResources = true; break; }
            }
            if (inTryWithResources) continue;

            findings.add(new Finding(Severity.SHOULD_FIX, Category.CODE_QUALITY, file.name, line, code,
                "I noticed a resource created without try-with-resources.",
                "Failing to close resources can leak file handles and cause production failures.",
                "try (var resource = " + code + ") { ... }"));
        }
    }

    private static void reviewNullSafety(String content, String[] lines, ChangedFile file, List<Finding> findings, AnalysisContext context) {
        // Optional.of can throw NPE
        Matcher m = P_OPTIONAL_OF.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
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
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.SHOULD_FIX, Category.NULL_SAFETY, file.name, line, lines[line-1].trim(),
                "I noticed a chained method call that could throw a NullPointerException.",
                "If any object in the chain returns null, the whole thing blows up. Using Optional is a cleaner way to handle this without messy null checks.",
                "Optional.ofNullable(root)\n    .map(Root::getNext)\n    .map(Next::getLeaf)\n    .orElse(null);"));
        }

        // List get without bounds check
        m = P_LIST_GET.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            String index = m.group(1);
            findings.add(new Finding(Severity.SHOULD_FIX, Category.NULL_SAFETY, file.name, line, lines[line-1].trim(),
                "I spotted a list access that doesn't check the size first.",
                "If the list is smaller than we expect, this will throw an IndexOutOfBoundsException. A quick size check prevents this.",
                "if (" + index + " < list.size()) {\n    var item = list.get(" + index + ");\n}"));
        }

        // Optional.get without presence check
        m = P_OPT_GET.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            // Combined risk: Optional.get() in a loop
            boolean inLoop = content.substring(0, m.start()).matches("(?s).*for\\s*\\(.*?\\)\\s*\\{.*");
            Severity severity = inLoop ? Severity.MUST_FIX : Severity.SHOULD_FIX;
            String message = inLoop ? "Careful with that .get() inside the loop!" : "I spotted an Optional.get() without a safety check.";
            
            findings.add(new Finding(severity, Category.NULL_SAFETY, file.name, line, lines[line-1].trim(),
                message,
                "If the Optional is empty, this will throw a NoSuchElementException. It's especially risky in a loop where it could cause a batch process to fail halfway through.",
                "// Use orElseThrow to handle missing values\noptional.orElseThrow(() -> new EntityNotFoundException(\"Value not found\"));"));
        }
    }

    private static void reviewExceptionHandling(String content, String[] lines, ChangedFile file, List<Finding> findings, AnalysisContext context) {
        // Empty catch blocks
        Matcher m = P_EMPTY_CATCH.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.MUST_FIX, Category.EXCEPTION_HANDLING, file.name, line, lines[line-1].trim(),
                "Looks like this catch block is empty.",
                "Swallowing exceptions without at least a log entry makes it really hard to track down production issues later. It's usually better to log the error so we have some visibility.",
                "log.error(\"Unexpected error occurred\", " + m.group(2) + ");"));
        }

        // Catching Throwable
        m = P_CATCH_THROWABLE.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.MUST_FIX, Category.EXCEPTION_HANDLING, file.name, line, lines[line-1].trim(),
                "I noticed we're catching Throwable here, which is extremely broad.",
                "This catches everything, including OutOfMemoryErrors and other JVM errors we can't really recover from. It's much safer to catch Exception or specific subtypes.",
                "catch (Exception e) {\n    log.error(\"Error processing\", e);\n}"));
        }

        // Catching generic Exception
        m = P_CATCH_EXCEPTION.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.SHOULD_FIX, Category.EXCEPTION_HANDLING, file.name, line, lines[line-1].trim(),
                "I noticed we're catching generic Exception, which can hide bugs.",
                "When we catch Exception, we might accidentally swallow RuntimeExceptions we didn't expect. Catching specific exceptions makes the error handling logic much clearer.",
                "catch (IOException | SQLException e) {\n    // Handle specific errors\n}"));
        }

        // Swallowed interrupt
        m = P_CATCH_INTERRUPT.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            boolean restores = false;
            for (int i = line; i <= Math.min(lines.length, line + 5); i++) {
                if (lines[i-1].contains("Thread.currentThread().interrupt()")) { restores = true; break; }
            }
            if (!restores) {
                findings.add(new Finding(Severity.MUST_FIX, Category.EXCEPTION_HANDLING, file.name, line, lines[line-1].trim(),
                    "InterruptedException caught without restoring interrupt",
                    "Swallowing interrupts can break cooperative cancellation.",
                    "Thread.currentThread().interrupt();"));
            }
        }
    }

    private static void reviewLogging(String content, String[] lines, ChangedFile file, List<Finding> findings, AnalysisContext context, List<Range> methodRanges) {
        Set<Integer> loggingScope = buildLoggingScope(file, lines, methodRanges);

        // Sensitive data in logs
        Matcher m = P_LOG_SENSITIVE.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
            if (!loggingScope.contains(line)) continue;
            findings.add(new Finding(Severity.MUST_FIX, Category.LOGGING, file.name, line, lines[line-1].trim(),
                "I noticed sensitive data might be getting logged here.",
                "Logging things like passwords, tokens, or secrets is a major security risk because logs are often stored in plain text. We should redact this.",
                "log.info(\"User login attempt: {}\", user.getUsername()); // Don't log the password!"));
        }

        // Logging inside loops
        m = P_LOG_IN_LOOP.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
            if (!loggingScope.contains(line)) continue;
            findings.add(new Finding(Severity.SHOULD_FIX, Category.LOGGING, file.name, line, lines[line-1].trim(),
                "Logging inside a loop can be dangerous.",
                "If this loop runs many times, it could flood the log files, slow down the application, and increase storage costs. It's usually better to log a summary after the loop.",
                "// Log once after the loop\nlog.info(\"Processed {} items\", count);"));
        }

        // System.out/err usage
        m = P_SYS_OUT.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
            if (!loggingScope.contains(line)) continue;
            findings.add(new Finding(Severity.SHOULD_FIX, Category.LOGGING, file.name, line, lines[line-1].trim(),
                "I noticed System.out/err is being used instead of a logger.",
                "System.out doesn't respect logging levels or configuration, so this might get lost or clutter the console in production. A proper Logger is the way to go.",
                "private static final Logger log = LoggerFactory.getLogger(ThisClass.class);\n// ...\nlog.info(\"Message\");"));
        }

        List<LogCall> logCalls = findLogCallsCached(content);

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

                    findings.add(new Finding(Severity.CONSIDER, Category.LOGGING, file.name, firstLine, code,
                        "Variable '" + varEntry.getKey() + "' logged multiple times in method",
                        "Logging the same variable repeatedly (lines " + linesWithVar + ") can be noisy. Consider consolidating these logs or ensuring each log adds unique context.",
                        "// Consolidate logs if they are redundant\nlog.info(\"Processing started for " + varEntry.getKey() + "\");\n..."));
                }
            }
        }

        detectDuplicateLogs(logCalls, lines, loggingScope, file, findings, methodRanges);
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

    private static void reviewSpringBoot(String content, String[] lines, ChangedFile file, List<Finding> findings, AnalysisContext context, Config config) {
        // Transactional on private method
        Matcher m = P_TX_PRIVATE.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.MUST_FIX, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                "I noticed @Transactional on a private method.",
                "Spring's AOP proxies can't see private methods, so this annotation is basically being ignored. The transaction won't actually start.",
                "// Make it public or move to a self-injected service\n@Transactional\npublic void process() { ... }"));
        }

        // Escalate severity for Spring-managed classes
        Severity springSeverity = context.isController || context.isService ? Severity.MUST_FIX : Severity.SHOULD_FIX;

        // @RequestBody without @Valid
        m = P_REQUEST_BODY.matcher(content);
        while (m.find()) {
            int start = m.start();
            // Look behind for @Valid (approximate scan in preceding chars)
            String preceding = content.substring(Math.max(0, start - 50), start);
            // Look ahead for @Valid (approximate scan in following chars)
            String following = content.substring(m.end(), Math.min(content.length(), m.end() + 50));
            
            boolean hasValid = preceding.contains("@Valid") || following.contains("@Valid") || preceding.contains("@Validated") || following.contains("@Validated");
            
            if (hasValid) continue;

            int line = getLineNumber(content, start);
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(springSeverity, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                "I noticed this @RequestBody is missing @Valid.",
                "Since this is a " + (context.isController ? "Controller" : "Service") + ", we should ensure the incoming payload is validated before we process it. This prevents malformed data from causing issues deeper in the logic.",
                "public ResponseEntity<?> endpoint(@Valid @RequestBody MyDto dto) { ... }"));
        }

        // Field injection
        m = P_FIELD_INJECT.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.SHOULD_FIX, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                "We might want to avoid field injection here.",
                "Using @Autowired on fields can make unit testing a bit of a headache because it requires reflection to mock. Constructor injection is usually the preferred way in modern Spring.",
                "private final MyService service;\n\npublic MyController(MyService service) {\n    this.service = service;\n}"));
        }

        // Hardcoded URLs
        m = P_HARDCODED_URL.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.CONSIDER, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                "I spotted a hardcoded URL here.",
                "Hardcoding URLs makes it tough to switch environments (like dev to prod). Putting this in a property makes configuration much easier.",
                "@Value(\"${service.endpoint.url}\")\nprivate String endpointUrl;"));
        }

        // N+1 query heuristic: repository.find* inside loop
        m = P_REPO_FIND.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.SHOULD_FIX, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                "Potential N+1 query detected.",
                "Calling a repository find method inside a loop often leads to N+1 database queries, which kills performance. It's usually better to fetch everything in one go.",
                "// Fetch all IDs at once\nList<Entity> entities = repository.findAllById(ids);"));
        }

        // @ConfigurationProperties missing @Validated
        if (content.contains("@ConfigurationProperties") && !content.contains("@Validated")) {
            int line = getLineNumber(content, content.indexOf("@ConfigurationProperties"));
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
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.SHOULD_FIX, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                "I noticed an @Value annotation without a default value.",
                "If the property happens to be missing in the config file, the application might fail to start. It's safer to provide a default.",
                "@Value(\"${" + m.group(1) + ":defaultValue}\")"));
        }

        // @Value potential secrets
        m = P_VALUE_SECRET.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
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
            int line = getLineNumber(content, m.start());
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
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.CONSIDER, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                "It looks like RestTemplate is being created inline.",
                "Creating a new RestTemplate every time misses out on shared configuration like timeouts and tracing. Injecting it as a bean is better practice.",
                "private final RestTemplate restTemplate;\n\npublic MyService(RestTemplateBuilder builder) {\n    this.restTemplate = builder.build();\n}"));
        }

        // @Scheduled with numeric fixedRate/fixedDelay
        m = P_SCHED_FIXED.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
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
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.MUST_FIX, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                "I noticed @CrossOrigin is allowing all origins with '*'.",
                "Allowing all origins exposes this endpoint to any website, which is a CORS security risk. Restrict it to only the domains you trust.",
                "@CrossOrigin(origins = \"https://your-app.example.com\")"));
        }
        // Also warn on bare @CrossOrigin (no args) which allows all in many Spring versions
        m = P_CROSS_BARE.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
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
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.MUST_FIX, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                "I noticed @Async on a private method.",
                "Spring's AOP proxy cannot intercept private methods, so @Async is silently ignored here — the method will run synchronously. Make it public or move it to a separate @Service bean.",
                "@Async\npublic CompletableFuture<Void> asyncMethod() { ... }"));
        }

        // @PostConstruct / @PreDestroy on static method (Spring ignores them on static methods)
        m = P_LIFECYCLE_STAT.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
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
                int line = getLineNumber(content, m.start());
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
            int line = getLineNumber(content, m.start());
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
                int line = getLineNumber(content, m.start());
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
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            // Check if the called method is @Transactional in this class
            Pattern txOnMethod = Pattern.compile("@Transactional[^;{}]*(?:public|protected)\\s+\\S+\\s+" + Pattern.quote(calledMethod) + "\\s*\\(");
            if (txOnMethod.matcher(content).find()) {
                findings.add(new Finding(Severity.MUST_FIX, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                    "I noticed a self-invocation call this." + calledMethod + "() to a @Transactional method.",
                    "Spring's proxy-based AOP intercepts calls from outside the bean, not internal 'this.' calls. The @Transactional on '" + calledMethod + "' will be silently ignored — no transaction will be started for that inner call.",
                    "// Option 1: Self-inject the bean\n@Autowired private MyService self;\nself." + calledMethod + "();\n\n// Option 2: Move to a separate @Service bean and inject it"));
            }
        }

        // @Cacheable on private method (AOP proxy cannot intercept it)
        m = P_CACHE_PRIVATE.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.MUST_FIX, Category.SPRING_BOOT, file.name, line, lines[line-1].trim(),
                "I noticed @Cacheable on a private method.",
                "Spring's AOP proxy cannot intercept private methods, so @Cacheable is silently ignored here — the method will be called every time without any caching. Make it public or move it to a separate @Service bean.",
                "@Cacheable(value = \"items\", key = \"#id\")\npublic Item findById(Long id) { ... }"));
        }
    }

    private static void reviewOpenApi(String content, String[] lines, ChangedFile file, List<Finding> findings, AnalysisContext context) {
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
            int line = getLineNumber(content, m.start());
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
                int line = getLineNumber(content, om.start());
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
            int line = getLineNumber(content, m.start());
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

    private static void reviewPerformance(String content, String[] lines, ChangedFile file, List<Finding> findings, AnalysisContext context) {
        // orElse(expensiveCall())
        Matcher m = P_OR_ELSE.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            String call = m.group(1);
            if (call.contains("()")) { // Simplified check for method call
                findings.add(new Finding(Severity.SHOULD_FIX, Category.PERFORMANCE, file.name, line, lines[line-1].trim(),
                    "We might want to use .orElseGet() here.",
                    ".orElse() evaluates its argument even if the Optional is present, which can be unnecessary work if the method call is expensive. .orElseGet() is lazy.",
                    ".orElseGet(() -> " + call + ")"));
            }
        }

        // String concatenation in loops
        m = P_CONCAT_LOOP.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.SHOULD_FIX, Category.PERFORMANCE, file.name, line, lines[line-1].trim(),
                "String concatenation in a loop is inefficient.",
                "This creates a new String object in every iteration, which can be slow. StringBuilder is designed exactly for this and is much faster.",
                "StringBuilder sb = new StringBuilder();\nfor (...) {\n    sb.append(str);\n}"));
        }

        // Thread.sleep in production code
        m = P_THREAD_SLEEP.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.CONSIDER, Category.PERFORMANCE, file.name, line, lines[line-1].trim(),
                "I noticed a Thread.sleep() call here.",
                "Thread.sleep hurts responsiveness and is often flaky in tests or production. If you're waiting for something, 'awaitility' or a scheduled executor is usually more robust.",
                "// Use awaitility for conditions\nawait().atMost(5, SECONDS).until(() -> condition);\n\n// Or Schedule for delays\nscheduler.schedule(task, 5, SECONDS);"));
        }
    }

    private static void reviewCodeQuality(String content, String[] lines, ChangedFile file, List<Finding> findings, AnalysisContext context, Config config) {
        // Boxed types compared with ==
        Matcher m = P_BOXED_CMP.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.MUST_FIX, Category.CODE_QUALITY, file.name, line, lines[line-1].trim(),
                "I noticed we're comparing boxed types using '=='.",
                "Using '==' on Integer, Long, or Boolean compares object identity (memory address), not the actual value. This can work for cached values (-128 to 127) but fails for others, causing subtle bugs.",
                "Objects.equals(a, b)"));
        }

        // Hardcoded credentials
        m = P_HARDCODED_CRED.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.MUST_FIX, Category.CODE_QUALITY, file.name, line, lines[line-1].trim(),
                "I spotted a hardcoded credential here.",
                "Hardcoding secrets is a security risk. If this code gets shared, the secret goes with it. It's better to load this from a secure config.",
                "@Value(\"${api.secret}\")\nprivate String secret;"));
        }

        // TODO/FIXME markers
        m = P_TODO_FIXME.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.CONSIDER, Category.LOGGING, file.name, line, lines[line-1].trim(),
                "I noticed some TODO or FIXME markers in the code.",
                "It's always good to keep track of pending work, but we should try to resolve these or move them to a formal tracking system before the final release to keep the codebase clean.",
                "// Jira: PROJ-123"));
        }

        // equals/hashCode mismatch: class overrides one without the other
        if (content.contains("equals(") && !content.contains("hashCode(")) {
            int line = getLineNumber(content, content.indexOf("equals("));
            if (isInChangedLines(file, line)) {
                findings.add(new Finding(Severity.SHOULD_FIX, Category.CODE_QUALITY, file.name, line, lines[line-1].trim(),
                    "It looks like equals() is overridden without hashCode().",
                    "In Java, if you override equals(), you should also override hashCode() to maintain the contract. This is crucial for things to work correctly when this class is used in sets or maps.",
                    "@Override\npublic int hashCode() {\n    return Objects.hash(field1, field2); // Include key fields\n}"));
            }
        } else if (content.contains("hashCode(") && !content.contains("equals(")) {
            int line = getLineNumber(content, content.indexOf("hashCode("));
            if (isInChangedLines(file, line)) {
                findings.add(new Finding(Severity.SHOULD_FIX, Category.CODE_QUALITY, file.name, line, lines[line-1].trim(),
                    "I noticed hashCode() is overridden without equals().",
                    "Overriding hashCode() without equals() can lead to inconsistent behavior in collections. It's best to implement both to ensure equality works as expected.",
                    "@Override\npublic boolean equals(Object o) {\n    if (this == o) return true;\n    if (!(o instanceof MyClass)) return false;\n    // ... compare fields\n}"));
            }
        }

        // Thread.sleep sanity: suspicious very low or very high literals
        m = P_SLEEP_LITERAL.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
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
            int line = getLineNumber(content, content.indexOf("@Builder"));
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
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            String literal = m.group(1);
            if (literal.equalsIgnoreCase("true") || literal.equalsIgnoreCase("false") || literal.equalsIgnoreCase("null")) continue;
            String sourceLine = lines[line-1];
            if (sourceLine.contains("log.") || sourceLine.matches(".*\\blog\\.(info|debug|warn|error|trace)\\(.*")) continue; // allow log messages
            
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
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            String sourceLine = lines[line-1];
            if (sourceLine.contains("for (int") || sourceLine.contains("case ")) continue;
            findings.add(new Finding(Severity.SHOULD_FIX, Category.CODE_QUALITY, file.name, line, sourceLine.trim(),
                "I spotted a magic number here.",
                "Using raw numbers like " + m.group(1) + " can make the code's purpose unclear. It's much easier for the team to understand what this represents if it's stored in a well-named constant.",
                "private static final int CONSTANT_NAME = " + m.group(1) + ";"));
        }

        // Uppercase literals repeated -> promote to constants/enums
        m = P_DOMAIN_LITERAL.matcher(content);
        Map<String, List<Integer>> literalOccurrences = new HashMap<>();
        while (m.find()) {
            literalOccurrences.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(getLineNumber(content, m.start()));
        }
        Set<String> literalSkip = Set.of("GET", "POST", "PUT", "DELETE", "JSON", "XML", "OK", "ID", "UUID");
        for (Map.Entry<String, List<Integer>> entry : literalOccurrences.entrySet()) {
            if (entry.getValue().size() < 2 || literalSkip.contains(entry.getKey())) continue;
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
            int line = getLineNumber(content, m.start());
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
            int line = getLineNumber(content, m.start());
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
            int line = getLineNumber(content, m.start());
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
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            String val = m.group(1);
            String sourceLine = lines[line-1];
            if (sourceLine.contains("static final")) continue;
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
                int line = getLineNumber(content, m.start());
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

    private static void reviewJavaModern(String content, String[] lines, ChangedFile file, List<Finding> findings, Config config) {
        // 1. Legacy java.util.Date / Calendar / SimpleDateFormat usage
        Matcher m = P_LEGACY_DATE.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
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
            int line = getLineNumber(content, m.start());
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
            int line = getLineNumber(content, m.start());
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
            int line = getLineNumber(content, m.start());
            if (!isInChangedLines(file, line)) continue;
            findings.add(new Finding(Severity.SHOULD_FIX, Category.CODE_QUALITY, file.name, line, lines[line-1].trim(),
                "I noticed double-brace initialization here.",
                "Double-brace initialization creates an anonymous inner class that holds an implicit reference to the outer class. This can cause memory leaks, prevents equals() from working correctly, and breaks serialization. Use Map.of(), List.of(), or explicit add() calls instead.",
                "// Use factory methods:\nMap<String, String> map = new HashMap<>(Map.of(\"key\", \"value\"));\n// Or explicit:\nList<String> list = new ArrayList<>();\nlist.add(\"item\");"));
        }

        // 5. Math.random() for anything non-trivial (use ThreadLocalRandom or SecureRandom)
        m = P_MATH_RANDOM.matcher(content);
        while (m.find()) {
            int line = getLineNumber(content, m.start());
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
                int line = getLineNumber(content, m.start());
                if (!isInChangedLines(file, line)) continue;
                String castType = m.group(1);
                findings.add(new Finding(Severity.CONSIDER, Category.CODE_QUALITY, file.name, line, lines[line-1].trim(),
                    "I noticed an instanceof check followed by a cast to " + castType + ".",
                    "Pattern matching for instanceof (Java 16+) lets you combine the check and cast in one step, eliminating the redundant cast and making the code cleaner.",
                    "// Java 16+ pattern matching:\nif (obj instanceof " + castType + " typed) {\n    typed.doSomething();\n}"));
            }
        }
    }

    private static boolean isInChangedLines(ChangedFile file, int line) {
        for (int i = line - 1; i <= line + 1; i++) {
            if (file.changedLines.contains(i)) return true;
        }
        return false;
    }

    private static int getLineNumber(String content, int index) {
        return (int) content.substring(0, Math.min(index, content.length())).chars().filter(c -> c == '\n').count() + 1;
    }
}
