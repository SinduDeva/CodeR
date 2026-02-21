package com.reviewer.cache;

import com.reviewer.model.Models.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class FileChangeDetector {
    private final CacheManager cacheManager;
    private final Set<String> skippedFiles = Collections.synchronizedSet(new HashSet<>());

    public FileChangeDetector(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public List<ChangedFile> filterUnchangedFiles(List<ChangedFile> files) {
        if (files == null || files.isEmpty()) {
            return files;
        }

        List<ChangedFile> changedFiles = new ArrayList<>();
        skippedFiles.clear();

        for (ChangedFile file : files) {
            if (hasChanged(file)) {
                changedFiles.add(file);
            } else {
                skippedFiles.add(file.path);
            }
        }

        return changedFiles;
    }

    private boolean hasChanged(ChangedFile file) {
        if (file == null || file.path == null) {
            return true; // Analyze unknown files
        }

        String currentHash = cacheManager.computeFileHash(file.path);
        if (currentHash.isEmpty()) {
            return true; // File not found or unreadable - let other layers handle it
        }

        // Try to get cached findings with current hash
        List<Finding> cached = cacheManager.getCachedFindings(file.path, currentHash);
        return cached == null; // If cache miss, file has changed or not cached
    }

    public Set<String> getSkippedFiles() {
        return new HashSet<>(skippedFiles);
    }

    public int getSkipCount() {
        return skippedFiles.size();
    }

    public String getFileHash(String filePath) {
        return cacheManager.computeFileHash(filePath);
    }
}
