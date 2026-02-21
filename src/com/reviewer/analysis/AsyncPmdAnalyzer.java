package com.reviewer.analysis;

import com.reviewer.cache.CacheManager;
import com.reviewer.model.Models.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class AsyncPmdAnalyzer {
    private final ExecutorService executor;
    private final CacheManager cacheManager;
    private final int threadPoolSize;
    private volatile boolean shutdown = false;

    public AsyncPmdAnalyzer(int threadPoolSize, CacheManager cacheManager) {
        this.threadPoolSize = Math.max(1, Math.min(threadPoolSize, 8));
        this.executor = Executors.newFixedThreadPool(this.threadPoolSize);
        this.cacheManager = cacheManager;
    }

    public List<Finding> analyze(List<ChangedFile> files, Config config) throws Exception {
        if (!config.enableAsyncPmd) {
            // Fallback to synchronous analysis
            return PmdAnalyzer.analyze(files, config);
        }

        if (files == null || files.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            CompletableFuture<List<Finding>> future = analyzeAsync(files, config);
            return future.get(120, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            System.err.println("[WARN] Async PMD analysis timed out after 120 seconds");
            return new ArrayList<>();
        } catch (Exception e) {
            System.err.println("[WARN] Async PMD analysis failed: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public CompletableFuture<List<Finding>> analyzeAsync(List<ChangedFile> files, Config config) {
        if (shutdown) {
            return CompletableFuture.failedFuture(new IllegalStateException("AsyncPmdAnalyzer is shut down"));
        }

        if (!config.enablePmdAnalysis) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        if (files == null || files.isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        // Create tasks for parallel file analysis
        List<CompletableFuture<List<Finding>>> tasks = files.stream()
            .map(file -> analyzeFileAsync(file, config))
            .collect(Collectors.toList());

        // Aggregate all results
        return CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]))
            .thenApply(v -> tasks.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .collect(Collectors.toList())
            );
    }

    private CompletableFuture<List<Finding>> analyzeFileAsync(ChangedFile file, Config config) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return analyzeFileSynchronously(file, config);
            } catch (Exception e) {
                System.err.println("[WARN] Failed to analyze file " + file.path + ": " + e.getMessage());
                return new ArrayList<Finding>();
            }
        }, executor).exceptionally(e -> {
            System.err.println("[WARN] Async exception analyzing " + file.path + ": " + e.getMessage());
            return new ArrayList<Finding>();
        });
    }

    private List<Finding> analyzeFileSynchronously(ChangedFile file, Config config) {
        if (cacheManager != null && config.enableCache) {
            String fileHash = cacheManager.computeFileHash(file.path);
            if (!fileHash.isEmpty()) {
                // Try to get cached results
                List<Finding> cached = cacheManager.getCachedFindings(file.path, fileHash);
                if (cached != null) {
                    return cached;
                }

                // Cache miss - analyze and store
                List<Finding> findings = PmdAnalyzer.analyzeSingleFile(file, config);
                cacheManager.cachePmdResults(file.path, fileHash, findings);
                return findings;
            }
        }

        // No cache or cache disabled - analyze directly
        return PmdAnalyzer.analyzeSingleFile(file, config);
    }

    public void shutdown() {
        shutdown = true;
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    public int getThreadPoolSize() {
        return threadPoolSize;
    }

    public boolean isShutdown() {
        return shutdown;
    }
}
