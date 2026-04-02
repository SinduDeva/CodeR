package com.reviewer.language;

import com.reviewer.model.Models.*;
import java.util.List;

public interface Language {
    String getName();
    List<String> getSupportedExtensions();
    void analyzeFile(String content, String[] lines, ChangedFile file, List<Finding> findings, Config config);
}
