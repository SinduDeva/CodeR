package com.reviewer.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JavaSymbolIndex {
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([\\w\\.]+)\\s*;");
    private static final Pattern TYPE_PATTERN = Pattern.compile(
        "(?m)^\\s*(?:@\\w+\\s+)*" +
        "(?:public|protected|private)?\\s*(?:abstract\\s+|final\\s+)?" +
        "(?:class|interface|enum|record)\\s+(\\w+)"
    );
    private static final Pattern IMPORT_PATTERN = Pattern.compile("(?m)^\\s*import\\s+([\\w\\.\\*]+)\\s*;");
    private static final Pattern EXTENDS_CLAUSE  = Pattern.compile("\\bextends\\s+(.+?)(?=\\bimplements\\b|\\{)");
    private static final Pattern IMPLEMENTS_CLAUSE = Pattern.compile("\\bimplements\\s+(.+?)(?=\\{)");
    // Caches Pattern.compile("\\b<token>\\b") — built once per unique token per JVM session
    private static final Map<String, Pattern> TOKEN_PATTERN_CACHE = new HashMap<>();

    private final Map<String, ClassInfo> classesByPath = new HashMap<>();
    private final Map<String, Set<ClassInfo>> classesBySimpleName = new HashMap<>();

    private JavaSymbolIndex() {}

    public static JavaSymbolIndex build(Path repoRoot) throws IOException {
        JavaSymbolIndex index = new JavaSymbolIndex();
        try (Stream<Path> paths = Files.walk(repoRoot)) {
            paths.filter(Files::isRegularFile)
                 .filter(p -> p.toString().endsWith(".java"))
                 .filter(JavaSymbolIndex::isEligibleSourceFile)
                 .forEach(p -> {
                     try {
                         parseClassInfo(p).ifPresent(index::add);
                     } catch (IOException ignored) {}
                 });
        }
        return index;
    }

    private static boolean isEligibleSourceFile(Path path) {
        String normalized = normalize(path).replace('\\', '/');
        boolean inMain = normalized.contains("/src/main/java/");
        boolean inTest = normalized.contains("/src/test/java/");
        if (!inMain && !inTest) return false;
        return !normalized.contains("/target/") && !normalized.contains("/build/") &&
               !normalized.contains("/.git/") && !normalized.contains("/.gradle/");
    }

    private void add(ClassInfo info) {
        classesByPath.put(normalize(info.path), info);
        classesBySimpleName.computeIfAbsent(info.simpleName, k -> new HashSet<>()).add(info);
    }

    public Optional<ClassInfo> getClassInfo(Path path) {
        return Optional.ofNullable(classesByPath.get(normalize(path)));
    }

    public Collection<ClassInfo> getAllClasses() {
        return new ArrayList<>(classesByPath.values());
    }

    public boolean isSimpleNameUnique(String simpleName) {
        Set<ClassInfo> entries = classesBySimpleName.get(simpleName);
        return entries != null && entries.size() == 1;
    }

    public Map<String, Set<String>> buildReverseDependencyGraph(Collection<ClassInfo> targets) {
        return buildReverseDependencyGraph(targets, null);
    }

    /**
     * Builds a reverse dependency graph for the given targets.
     * @param contentCache optional shared in-memory cache (path -> content); entries are read from
     *                     and written to the cache so callers avoid redundant disk reads.
     */
    public Map<String, Set<String>> buildReverseDependencyGraph(Collection<ClassInfo> targets, Map<String, String> contentCache) {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        if (targets == null || targets.isEmpty()) {
            return graph;
        }

        for (ClassInfo target : targets) {
            graph.putIfAbsent(target.fqn, new LinkedHashSet<>());
        }

        for (ClassInfo candidate : classesByPath.values()) {
            String content;
            String cacheKey = candidate.path.toString();
            if (contentCache != null && contentCache.containsKey(cacheKey)) {
                content = contentCache.get(cacheKey);
            } else {
                try {
                    content = Files.readString(candidate.path);
                    if (contentCache != null) contentCache.put(cacheKey, content);
                } catch (IOException e) {
                    continue;
                }
            }

            Imports imports = parseImports(content);
            for (ClassInfo target : targets) {
                boolean unique = isSimpleNameUnique(target.simpleName);
                if (dependsOn(content, imports, candidate, target, unique)) {
                    graph.computeIfAbsent(target.fqn, k -> new LinkedHashSet<>()).add(candidate.path.toString());
                }
            }
        }

        return graph;
    }

    public static Optional<ClassInfo> parseClassInfo(Path path) throws IOException {
        String content = Files.readString(path);
        Matcher typeMatcher = TYPE_PATTERN.matcher(content);
        if (!typeMatcher.find()) return Optional.empty();

        Matcher pkgMatcher = PACKAGE_PATTERN.matcher(content);
        String pkg = pkgMatcher.find() ? pkgMatcher.group(1) : "";
        String simpleName = typeMatcher.group(1);
        String fqn = pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
        List<String> supertypes = parseSupertypeSimpleNames(content, typeMatcher.end());
        return Optional.of(new ClassInfo(path.toAbsolutePath().normalize(), pkg, simpleName, fqn, supertypes));
    }

    /** Extracts simple names of superclasses and interfaces from the class header (between class name and opening brace). */
    private static List<String> parseSupertypeSimpleNames(String content, int fromPos) {
        int bracePos = content.indexOf('{', fromPos);
        if (bracePos == -1) return Collections.emptyList();
        String header = content.substring(fromPos, bracePos);

        List<String> supertypes = new ArrayList<>();

        Matcher extMatcher = EXTENDS_CLAUSE.matcher(header);
        if (extMatcher.find()) {
            for (String token : splitTypeList(extMatcher.group(1))) {
                String s = simpleNameOf(token);
                if (!s.isEmpty()) supertypes.add(s);
            }
        }

        Matcher implMatcher = IMPLEMENTS_CLAUSE.matcher(header);
        if (implMatcher.find()) {
            for (String token : splitTypeList(implMatcher.group(1))) {
                String s = simpleNameOf(token);
                if (!s.isEmpty()) supertypes.add(s);
            }
        }

        return supertypes;
    }

    /** Splits a comma-separated type list, respecting angle-bracket nesting (e.g. "B<K,V>, C"). */
    private static List<String> splitTypeList(String types) {
        List<String> result = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < types.length(); i++) {
            char c = types.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                result.add(types.substring(start, i).trim());
                start = i + 1;
            }
        }
        String last = types.substring(start).trim();
        if (!last.isEmpty()) result.add(last);
        return result;
    }

    /** Returns the simple name of a (possibly qualified, possibly generic) type token. */
    private static String simpleNameOf(String type) {
        int lt = type.indexOf('<');
        String base = lt >= 0 ? type.substring(0, lt).trim() : type.trim();
        int dot = base.lastIndexOf('.');
        return dot >= 0 ? base.substring(dot + 1) : base;
    }

    public static Imports parseImports(String content) {
        Set<String> explicit = new HashSet<>();
        Set<String> wildcard = new HashSet<>();
        Matcher matcher = IMPORT_PATTERN.matcher(content);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (value.endsWith(".*")) {
                wildcard.add(value.substring(0, value.length() - 2));
            } else {
                explicit.add(value);
            }
        }
        return new Imports(explicit, wildcard);
    }

    public static boolean dependsOn(String content, Imports imports, ClassInfo candidate, ClassInfo target, boolean simpleNameUnique) {
        if (candidate == null || target == null) return false;
        if (candidate.fqn.equals(target.fqn)) return false;

        if (imports.hasExplicit(target.fqn)) return true;
        boolean simpleToken = containsToken(content, target.simpleName);
        if (imports.hasWildcard(target.packageName) && simpleToken) return true;
        if (!target.packageName.isEmpty() && candidate.packageName.equals(target.packageName) && simpleToken && isSameModule(candidate.path, target.path)) return true;
        if (containsToken(content, target.fqn)) return true;

        // Check if candidate depends on any of target's supertypes (interfaces / superclass).
        // Handles the common Spring pattern: inject UserService (interface) while the changed
        // file is UserServiceImpl.  The candidate imports the interface, not the implementation.
        for (String supertype : target.supertypeSimpleNames) {
            if (!containsToken(content, supertype)) continue;
            // Require that the reference plausibly points to the same package as the target.
            if (imports.hasExplicit(target.packageName + "." + supertype)) return true;
            if (imports.hasWildcard(target.packageName)) return true;
            if (!target.packageName.isEmpty()
                    && candidate.packageName.equals(target.packageName)
                    && isSameModule(candidate.path, target.path)) return true;
        }

        // Enhancement: detect @Autowired/@Inject field/param types and match against target's supertypes.
        // Handles the case where candidate injects target via an interface, possibly through inheritance.
        // Example: Controller extends BaseController; BaseController has @Autowired IUserService service;
        // This makes Controller depend on UserServiceImpl even though Controller doesn't directly import anything.
        return hasAutowiredDependencyOn(content, target);
    }

    /**
     * Detects if {@code content} has @Autowired/@Inject fields/parameters typed as
     * (or implementing) one of {@code target}'s supertypes.
     *
     * Example: if target is UserServiceImpl with supertypes=[IUserService],
     * and content has @Autowired IUserService service; then returns true.
     * This is particularly important for base classes that inject interfaces,
     * and subclasses that inherit those injections.
     */
    private static boolean hasAutowiredDependencyOn(String content, ClassInfo target) {
        if (target == null || target.supertypeSimpleNames.isEmpty()) {
            return false;
        }

        // Match @Autowired/@Inject with the following field/param declaration
        // Captures the field/param type name. Handles multi-line declarations.
        // Pattern: @Autowired/@Inject (optional whitespace/newlines) type(possibly qualified) fieldName
        Pattern autowiredPattern = Pattern.compile(
            "@(?:Autowired|Inject)\\b[\\s\\n]*(?:(?:\\((?:[^)]*)\\)\\s*)?)*[\\s\\n]*" +
            "([\\w\\.]+)(?:<[^>]*>)?\\s+\\w+\\b",
            Pattern.MULTILINE
        );

        Matcher matcher = autowiredPattern.matcher(content);
        while (matcher.find()) {
            String fieldType = matcher.group(1);
            // Extract simple name (last component after dots)
            String fieldTypeSimple = fieldType.contains(".")
                ? fieldType.substring(fieldType.lastIndexOf('.') + 1)
                : fieldType;

            // Check if this field type matches any of target's supertypes (interfaces/superclass)
            for (String supertype : target.supertypeSimpleNames) {
                if (fieldTypeSimple.equals(supertype)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean isSameModule(Path a, Path b) {
        String ma = moduleRoot(a);
        String mb = moduleRoot(b);
        return ma != null && mb != null && ma.equals(mb);
    }

    private static String moduleRoot(Path path) {
        if (path == null) return null;
        String normalized = normalize(path).replace('\\', '/');
        int idx = normalized.indexOf("/src/main/java/");
        if (idx < 0) idx = normalized.indexOf("/src/test/java/");
        if (idx < 0) idx = normalized.indexOf("/src/");
        if (idx < 0) {
            Path parent = path.toAbsolutePath().normalize().getParent();
            return parent == null ? normalize(path) : parent.toString().replace('\\', '/');
        }
        return normalized.substring(0, idx);
    }

    private static boolean containsToken(String content, String token) {
        if (token == null || token.isEmpty()) return false;
        Pattern p = TOKEN_PATTERN_CACHE.computeIfAbsent(token,
            k -> Pattern.compile("\\b" + Pattern.quote(k) + "\\b"));
        return p.matcher(content).find();
    }

    private static String normalize(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    public static class Imports {
        private final Set<String> explicit;
        private final Set<String> wildcard;

        public Imports(Set<String> explicit, Set<String> wildcard) {
            this.explicit = explicit;
            this.wildcard = wildcard;
        }

        public boolean hasExplicit(String fqn) {
            return explicit.contains(fqn);
        }

        public boolean hasWildcard(String pkg) {
            return pkg != null && !pkg.isEmpty() && wildcard.contains(pkg);
        }

        public Set<String> getExplicit() {
            return explicit;
        }
    }

    public static class ClassInfo {
        public final Path path;
        public final String packageName;
        public final String simpleName;
        public final String fqn;
        /** Simple names of superclass and implemented interfaces (e.g. ["UserService", "Auditable"]). */
        public final List<String> supertypeSimpleNames;

        public ClassInfo(Path path, String packageName, String simpleName, String fqn) {
            this(path, packageName, simpleName, fqn, Collections.emptyList());
        }

        public ClassInfo(Path path, String packageName, String simpleName, String fqn, List<String> supertypeSimpleNames) {
            this.path = path;
            this.packageName = packageName == null ? "" : packageName;
            this.simpleName = simpleName;
            this.fqn = fqn;
            this.supertypeSimpleNames = supertypeSimpleNames == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(supertypeSimpleNames));
        }
    }
}
