package com.reviewer.cache;

import com.reviewer.model.Models.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class CacheManager {
    private static CacheManager instance;
    private final Path cacheDir;
    private final Path pmdResultsDir;
    private final Path fileHashesDir;
    private final Map<String, CacheEntry> memoryCache = new ConcurrentHashMap<>();
    private int cacheHits = 0;
    private int cacheMisses = 0;

    private static class CacheEntry {
        final List<Finding> findings;
        final long timestamp;

        CacheEntry(List<Finding> findings) {
            this.findings = findings;
            this.timestamp = System.currentTimeMillis();
        }
    }

    public static class CacheStats {
        public int hits;
        public int misses;
        public int hitRate;
        public int totalCachedFiles;

        CacheStats(int hits, int misses, int totalCachedFiles) {
            this.hits = hits;
            this.misses = misses;
            this.hitRate = (hits + misses) > 0 ? (hits * 100) / (hits + misses) : 0;
            this.totalCachedFiles = totalCachedFiles;
        }
    }

    private CacheManager(String cacheDirPath) {
        this.cacheDir = Paths.get(cacheDirPath).toAbsolutePath();
        this.pmdResultsDir = cacheDir.resolve("pmd-results");
        this.fileHashesDir = cacheDir.resolve("file-hashes");

        try {
            Files.createDirectories(pmdResultsDir);
            Files.createDirectories(fileHashesDir);
        } catch (IOException e) {
            System.err.println("[WARN] Failed to create cache directories: " + e.getMessage());
        }
    }

    public static synchronized CacheManager getInstance(String cacheDirPath) {
        if (instance == null) {
            instance = new CacheManager(cacheDirPath);
        }
        return instance;
    }

    public List<Finding> getCachedFindings(String filePath, String fileHash) {
        if (filePath == null || fileHash == null) {
            cacheMisses++;
            return null;
        }

        String cacheKey = sanitizeFileName(filePath);

        // Check memory cache first
        CacheEntry entry = memoryCache.get(cacheKey);
        if (entry != null) {
            // Verify hash matches
            String storedHash = readStoredHash(cacheKey);
            if (fileHash.equals(storedHash)) {
                cacheHits++;
                return new ArrayList<>(entry.findings);
            } else {
                // Hash mismatch - cache is stale
                memoryCache.remove(cacheKey);
            }
        }

        // Check disk cache
        Path resultsFile = pmdResultsDir.resolve(cacheKey + ".json");
        if (Files.exists(resultsFile)) {
            String storedHash = readStoredHash(cacheKey);
            if (fileHash.equals(storedHash)) {
                try {
                    List<Finding> findings = loadFindingsFromJson(Files.readString(resultsFile));
                    memoryCache.put(cacheKey, new CacheEntry(findings));
                    cacheHits++;
                    return new ArrayList<>(findings);
                } catch (Exception e) {
                    System.err.println("[WARN] Failed to load cache for " + filePath + ": " + e.getMessage());
                }
            }
        }

        cacheMisses++;
        return null;
    }

    public void cachePmdResults(String filePath, String fileHash, List<Finding> findings) {
        if (filePath == null || fileHash == null || findings == null) {
            return;
        }

        String cacheKey = sanitizeFileName(filePath);

        try {
            // Store hash
            Path hashFile = fileHashesDir.resolve(cacheKey + ".hash");
            Files.write(hashFile, fileHash.getBytes());

            // Store findings as JSON
            Path resultsFile = pmdResultsDir.resolve(cacheKey + ".json");
            String json = findingsToJson(findings);
            Files.write(resultsFile, json.getBytes());

            // Update memory cache
            memoryCache.put(cacheKey, new CacheEntry(findings));
        } catch (Exception e) {
            System.err.println("[WARN] Failed to cache results for " + filePath + ": " + e.getMessage());
        }
    }

    public String computeFileHash(String filePath) {
        try {
            Path path = Paths.get(filePath);
            if (!Files.exists(path)) {
                return "";
            }

            byte[] fileBytes = Files.readAllBytes(path);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(fileBytes);

            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            System.err.println("[WARN] Failed to compute hash for " + filePath + ": " + e.getMessage());
            return "";
        }
    }

    public void invalidateCache(String filePath) {
        String cacheKey = sanitizeFileName(filePath);
        memoryCache.remove(cacheKey);

        try {
            Path hashFile = fileHashesDir.resolve(cacheKey + ".hash");
            Path resultsFile = pmdResultsDir.resolve(cacheKey + ".json");
            Files.deleteIfExists(hashFile);
            Files.deleteIfExists(resultsFile);
        } catch (IOException e) {
            System.err.println("[WARN] Failed to invalidate cache for " + filePath + ": " + e.getMessage());
        }
    }

    public void clearExpiredCache(long maxAgeMillis) {
        try {
            long now = System.currentTimeMillis();
            Files.list(pmdResultsDir)
                .filter(Files::isRegularFile)
                .filter(p -> {
                    try {
                        long fileAge = now - Files.getLastModifiedTime(p).toMillis();
                        return fileAge > maxAgeMillis;
                    } catch (IOException e) {
                        return false;
                    }
                })
                .forEach(p -> {
                    try {
                        Files.delete(p);
                        String baseName = p.getFileName().toString().replace(".json", "");
                        Files.deleteIfExists(fileHashesDir.resolve(baseName + ".hash"));
                    } catch (IOException e) {
                        System.err.println("[WARN] Failed to delete cache file: " + e.getMessage());
                    }
                });
        } catch (IOException e) {
            System.err.println("[WARN] Failed to clear expired cache: " + e.getMessage());
        }
    }

    public CacheStats getStatistics() {
        try {
            int cachedCount = (int) Files.list(pmdResultsDir)
                .filter(Files::isRegularFile)
                .count();
            return new CacheStats(cacheHits, cacheMisses, cachedCount);
        } catch (IOException e) {
            return new CacheStats(cacheHits, cacheMisses, 0);
        }
    }

    private String readStoredHash(String cacheKey) {
        try {
            Path hashFile = fileHashesDir.resolve(cacheKey + ".hash");
            if (Files.exists(hashFile)) {
                return Files.readString(hashFile).trim();
            }
        } catch (IOException e) {
            // Ignore - cache miss
        }
        return "";
    }

    private String sanitizeFileName(String filePath) {
        return filePath.replace('/', '_')
                      .replace('\\', '_')
                      .replace(':', '_')
                      .replace('.', '_');
    }

    private String findingsToJson(List<Finding> findings) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < findings.size(); i++) {
            Finding f = findings.get(i);
            sb.append("  {\n");
            sb.append("    \"severity\": \"").append(f.severity).append("\",\n");
            sb.append("    \"category\": \"").append(f.category.name()).append("\",\n");
            sb.append("    \"file\": \"").append(escapeJson(f.file)).append("\",\n");
            sb.append("    \"line\": ").append(f.line).append(",\n");
            sb.append("    \"code\": \"").append(escapeJson(f.code)).append("\",\n");
            sb.append("    \"message\": \"").append(escapeJson(f.message)).append("\",\n");
            sb.append("    \"explanation\": \"").append(escapeJson(f.explanation)).append("\",\n");
            sb.append("    \"suggestedFix\": \"").append(escapeJson(f.suggestedFix)).append("\"\n");
            sb.append("  }");
            if (i < findings.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]");
        return sb.toString();
    }

    private List<Finding> loadFindingsFromJson(String json) {
        List<Finding> findings = new ArrayList<>();
        // Simple JSON parsing without external library
        // Note: This is a basic implementation; production code would use Jackson/Gson
        String[] entries = json.split("\\{");
        for (String entry : entries) {
            if (!entry.trim().startsWith("\"severity\"")) continue;

            try {
                Severity severity = extractJsonValue(entry, "severity", Severity::valueOf);
                String categoryStr = extractJsonString(entry, "category");
                Category category = Category.valueOf(categoryStr);
                String file = extractJsonString(entry, "file");
                int line = Integer.parseInt(extractJsonString(entry, "line"));
                String code = extractJsonString(entry, "code");
                String message = extractJsonString(entry, "message");
                String explanation = extractJsonString(entry, "explanation");
                String suggestedFix = extractJsonString(entry, "suggestedFix");

                findings.add(new Finding(severity, category, file, line, code, message, explanation, suggestedFix));
            } catch (Exception e) {
                // Skip malformed entries
            }
        }
        return findings;
    }

    private String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        return m.find() ? unescapeJson(m.group(1)) : "";
    }

    private <T> T extractJsonValue(String json, String key, java.util.function.Function<String, T> mapper) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"([^\"]*)\"";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher m = p.matcher(json);
        if (m.find()) {
            return mapper.apply(m.group(1));
        }
        return null;
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r");
    }

    private String unescapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\n", "\n")
                  .replace("\\r", "\r")
                  .replace("\\\"", "\"")
                  .replace("\\\\", "\\");
    }
}
