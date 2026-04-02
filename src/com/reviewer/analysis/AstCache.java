package com.reviewer.analysis;

import java.util.*;

/**
 * Shared LRU parse cache for JavaParser CompilationUnit objects.
 *
 * <p>Keyed on a {@code Long} that combines {@code content.length()} and
 * {@code content.hashCode()} — two different files would need identical
 * length AND the same 32-bit hash to collide, making accidental cache hits
 * practically impossible. Capped at 64 entries to bound memory use.
 * Both {@link AstRuleFilter} and {@link AstInvocationFinder} use this cache
 * so each file is parsed at most once per JVM run.
 */
public final class AstCache {

    private static final int CACHE_LIMIT = 64;

    @SuppressWarnings("unchecked")
    private static final Map<Long, Object> CACHE =
            Collections.synchronizedMap(new LinkedHashMap<Long, Object>(CACHE_LIMIT, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Object> e) {
                    return size() > CACHE_LIMIT;
                }
            });

    private AstCache() {}

    /** Combines content length and hashCode into a single 64-bit cache key. */
    private static long cacheKey(String content) {
        return ((long) content.length() << 32) ^ (content.hashCode() & 0xFFFFFFFFL);
    }

    /**
     * Returns a cached or freshly-parsed CompilationUnit for {@code content}.
     * Throws if JavaParser is unavailable or the source cannot be parsed.
     */
    @SuppressWarnings("unchecked")
    public static com.github.javaparser.ast.CompilationUnit get(String content) throws Exception {
        long key = cacheKey(content);
        Object cached = CACHE.get(key);
        if (cached != null) return (com.github.javaparser.ast.CompilationUnit) cached;
        com.github.javaparser.ast.CompilationUnit cu =
                com.github.javaparser.StaticJavaParser.parse(content);
        CACHE.put(key, cu);
        return cu;
    }
}
