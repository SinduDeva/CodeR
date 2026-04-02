package com.reviewer.language;

import com.reviewer.analysis.RuleEngine;
import com.reviewer.model.Models.*;
import java.util.List;

public class JavaLanguage implements Language {

    @Override
    public String getName() {
        return "java";
    }

    @Override
    public List<String> getSupportedExtensions() {
        return List.of(".java", ".kt", ".groovy");
    }

    @Override
    public void analyzeFile(String content, String[] lines, ChangedFile file,
                            List<Finding> findings, Config config) {
        RuleEngine.runRules(content, lines, file, findings, config);
    }
}
