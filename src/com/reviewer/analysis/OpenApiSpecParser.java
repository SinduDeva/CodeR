package com.reviewer.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight YAML parser for OpenAPI 3.x / Swagger 2.x spec files.
 *
 * <p>Builds a lookup table:
 * <pre>  operationId  →  "HTTP_METHOD /full/path"</pre>
 *
 * <p>No external YAML library is required — the structure is parsed with
 * indentation-aware regexes that handle the canonical OpenAPI layout:
 * <pre>
 *   paths:
 *     /affiliate/v1/lead:
 *       post:
 *         operationId: processAffiliateLead
 * </pre>
 *
 * <p>Caveats (acceptable for this use-case):
 * <ul>
 *   <li>Flow-style YAML (JSON-like braces on one line) is not supported.</li>
 *   <li>YAML anchors / aliases are not resolved.</li>
 *   <li>Multi-document YAML files (---) use only the first document.</li>
 * </ul>
 */
public class OpenApiSpecParser {

    private static final List<String> HTTP_METHODS =
            List.of("get", "post", "put", "delete", "patch", "options", "head", "trace");

    private static final Pattern PATH_KEY   = Pattern.compile("^(\\s{2,})(/[\\w\\-/.{}]*)\\s*:\\s*$");
    private static final Pattern METHOD_KEY = Pattern.compile("^(\\s{4,})(get|post|put|delete|patch|options|head|trace)\\s*:\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OP_ID_LINE = Pattern.compile("^\\s+operationId\\s*:\\s*([\\w]+)\\s*$");

    private OpenApiSpecParser() {}

    /**
     * Parses {@code specPath} and returns a map of
     * {@code operationId -> "HTTP_METHOD /path"}.
     * Returns an empty map if the file cannot be read or has no paths section.
     */
    public static Map<String, String> parse(Path specPath) {
        if (specPath == null || !Files.exists(specPath)) {
            return Collections.emptyMap();
        }
        try {
            String content = Files.readString(specPath);
            return parseContent(content);
        } catch (IOException e) {
            return Collections.emptyMap();
        }
    }

    /**
     * Parses multiple spec files and merges the results.
     * Later files override earlier ones when operationIds collide.
     */
    public static Map<String, String> parseAll(List<Path> specPaths) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (specPaths == null) return merged;
        for (Path p : specPaths) {
            merged.putAll(parse(p));
        }
        return merged;
    }

    static Map<String, String> parseContent(String content) {
        Map<String, String> result = new LinkedHashMap<>();
        if (content == null || content.isBlank()) return result;

        String[] lines = content.split("\\R", -1);

        boolean inPaths = false;
        String currentPath = null;
        String currentMethod = null;
        int pathIndent = -1;
        int methodIndent = -1;

        for (String line : lines) {
            if (line.isBlank() || line.trim().startsWith("#")) continue;

            String trimmed = line.trim();

            // Detect the 'paths:' section start
            if (!inPaths) {
                if (trimmed.equals("paths:")) {
                    inPaths = true;
                }
                continue;
            }

            // Exit 'paths:' if we hit a top-level key (0 indent, not a path)
            if (!line.startsWith(" ") && !line.startsWith("\t")) {
                if (!trimmed.startsWith("/")) {
                    inPaths = false;
                    currentPath = null;
                    currentMethod = null;
                    continue;
                }
            }

            int indent = leadingSpaces(line);

            // Detect a path key (e.g. "  /affiliate/v1/lead:")
            Matcher pathMatcher = PATH_KEY.matcher(line);
            if (pathMatcher.matches()) {
                int pIndent = pathMatcher.group(1).length();
                // Only accept as a path key if it is at depth 1 inside 'paths:'
                // (i.e. indent is consistent with a top-level path entry)
                if (currentPath == null || pIndent <= pathIndent || pathIndent == -1) {
                    currentPath = pathMatcher.group(2);
                    pathIndent = pIndent;
                    currentMethod = null;
                    methodIndent = -1;
                }
                continue;
            }

            if (currentPath == null) continue;

            // Detect an HTTP method key (e.g. "    post:")
            Matcher methodMatcher = METHOD_KEY.matcher(line);
            if (methodMatcher.matches()) {
                int mIndent = methodMatcher.group(1).length();
                if (mIndent > pathIndent) {
                    currentMethod = methodMatcher.group(2).toUpperCase();
                    methodIndent = mIndent;
                }
                continue;
            }

            if (currentMethod == null) continue;

            // Detect operationId inside the current method block
            if (indent > methodIndent) {
                Matcher opMatcher = OP_ID_LINE.matcher(line);
                if (opMatcher.matches()) {
                    String operationId = opMatcher.group(1).trim();
                    result.put(operationId, currentMethod + " " + currentPath);
                    // Keep currentMethod — an operation block may have other fields after operationId
                }
            } else if (indent <= methodIndent && indent > pathIndent) {
                // Moved to a sibling method key
                currentMethod = null;
            } else if (indent <= pathIndent) {
                // Moved to a sibling path key or out of paths
                currentPath = null;
                currentMethod = null;
            }
        }

        return result;
    }

    private static int leadingSpaces(String line) {
        int count = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') count++;
            else if (c == '\t') count += 2;
            else break;
        }
        return count;
    }
}
