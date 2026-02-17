package com.reviewer;

import com.reviewer.core.ReviewEngine;
import com.reviewer.model.Models.Config;
import com.reviewer.model.Models.ChangedFile;
import com.reviewer.util.ColorConsole;
import java.util.*;
import java.nio.file.*;
import java.io.*;

public class Main {
    private static final String CONFIG_FILE_NAME = ".code-reviewer.properties";
    public static void main(String[] args) {
        try {
            Config config = loadConfig();
            ReviewEngine engine = new ReviewEngine(config);
            
            List<ChangedFile> files = new ArrayList<>();
            if (args.length == 0) {
                // Hook mode: auto-detect staged files
                files = engine.getStagedFiles();
            } else if (args.length == 1 && args[0].equals("--install-hook")) {
                // Handled by run-reviewer.bat for now, but Main could also handle it
                System.out.println("Hook installation requested.");
                System.exit(0);
            } else {
                for (String arg : args) {
                    Path p = Paths.get(arg);
                    if (Files.exists(p)) {
                        files.add(new ChangedFile(p.toString(), p.getFileName().toString(), Collections.emptySet()));
                    }
                }
            }

            String reportPath = engine.run(files);
            
            if (reportPath != null) {
                openReport(reportPath);
            }
            
            System.exit(engine.getExitCode());
        } catch (Exception e) {
            ColorConsole.error("Execution failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static Config loadConfig() {
        Config config = new Config();
        Properties props = new Properties();
        File repoConfigFile = new File(CONFIG_FILE_NAME);
        // Try to load the default config from the tool's directory
        try (InputStream defaultConfig = Main.class.getResourceAsStream("/.code-reviewer.properties")) {
            if (defaultConfig != null) {
                props.load(defaultConfig);
                applyConfig(props, config);
            }
        } catch (IOException e) {
            ColorConsole.warning("Could not load default .code-reviewer.properties");
        }

        File toolConfig = resolveToolConfig();
        if (toolConfig != null && toolConfig.exists()) {
            loadConfigFile(toolConfig, props, config);
        }
        // Override with local config if it exists
        if (repoConfigFile.exists()) {
            loadConfigFile(repoConfigFile, props, config);
        }

        // If PMD path is relative, make it absolute relative to the tool's directory
        if (config.pmdPath != null && !new File(config.pmdPath).isAbsolute()) {
            try {
                String toolDir = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
                config.pmdPath = new File(toolDir, config.pmdPath).getCanonicalPath();
            } catch (Exception e) {
                ColorConsole.warning("Could not resolve PMD path: " + e.getMessage());
            }
        }

        return config;
    }

    private static File resolveToolConfig() {
        try {
            File jarLocation = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File toolDir = jarLocation.getParentFile();
            if (toolDir == null) return null;
            File cfg = new File(toolDir, CONFIG_FILE_NAME);
            return cfg.exists() ? cfg : null;
        } catch (Exception e) {
            ColorConsole.warning("Unable to locate tool-level config: " + e.getMessage());
            return null;
        }
    }

    private static void loadConfigFile(File file, Properties props, Config config) {
        try (FileInputStream fis = new FileInputStream(file)) {
            props.load(fis);
            applyConfig(props, config);
        } catch (IOException e) {
            ColorConsole.warning("Could not load config from " + file + ": " + e.getMessage());
        }
    }

    private static void applyConfig(Properties props, Config config) {
        config.blockOnMustFix = Boolean.parseBoolean(props.getProperty("block.on.must.fix", String.valueOf(config.blockOnMustFix)));
        config.onlyChangedLines = Boolean.parseBoolean(props.getProperty("only.changed.lines", String.valueOf(config.onlyChangedLines)));
        config.expandChangedScopeToMethod = Boolean.parseBoolean(props.getProperty("expand.changed.scope.to.method", String.valueOf(config.expandChangedScopeToMethod)));
        config.strictJava = Boolean.parseBoolean(props.getProperty("strict.java", String.valueOf(config.strictJava)));
        config.strictSpring = Boolean.parseBoolean(props.getProperty("strict.spring", String.valueOf(config.strictSpring)));
        config.showGoodPatterns = Boolean.parseBoolean(props.getProperty("show.good.patterns", String.valueOf(config.showGoodPatterns)));
        config.showTestingScope = Boolean.parseBoolean(props.getProperty("show.testing.scope", String.valueOf(config.showTestingScope)));
        config.openReport = Boolean.parseBoolean(props.getProperty("open.report", String.valueOf(config.openReport)));
        config.enablePmdAnalysis = Boolean.parseBoolean(props.getProperty("enable.pmd.analysis", String.valueOf(config.enablePmdAnalysis)));
        config.enableStructuralImpact = Boolean.parseBoolean(props.getProperty("enable.structural.impact", String.valueOf(config.enableStructuralImpact)));
        config.pmdPath = props.getProperty("pmd.path", config.pmdPath);
        config.pmdRulesetPath = props.getProperty("pmd.ruleset.path", config.pmdRulesetPath);
    }

    private static void openReport(String reportPath) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd", "/c", "start", "", reportPath);
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", reportPath);
            } else {
                pb = new ProcessBuilder("xdg-open", reportPath);
            }
            pb.start();
        } catch (Exception e) {
            ColorConsole.warning("Could not automatically open report: " + e.getMessage());
        }
    }
}
