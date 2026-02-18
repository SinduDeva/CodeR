package com.reviewer.report;

import com.reviewer.model.Models.*;
import com.reviewer.util.ColorConsole;
import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class HtmlReportGenerator {
    private static final String VERSION = "2.1.0";
    private static final Set<String> NON_DISPLAY_TOKENS;

    static {
        Set<String> tokens = new HashSet<>(Arrays.asList(
            "if", "for", "while", "switch", "case", "default", "try", "catch", "finally"
        ));
        NON_DISPLAY_TOKENS = Collections.unmodifiableSet(tokens);
    }

    public static String generate(List<ChangedFile> files, List<Finding> findings, List<ImpactEntry> impactEntries, Map<String, TestingStatus> testingStatusByFile, String currentBranch, int totalStagedFiles, Config config, Map<String, Set<String>> reverseDependencyGraph) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html data-theme=\"light\">\n<head>\n<meta charset=\"UTF-8\">\n<title>Code Review Report</title>\n");
        
        appendStyles(html);
        html.append("</head>\n<body>\n");
        
        Map<String, List<Finding>> findingsByFile = findings.stream()
            .collect(Collectors.groupingBy(f -> f.file, LinkedHashMap::new, Collectors.toList()));
        Map<String, ImpactEntry> impactByFile = impactEntries == null ? Collections.emptyMap() :
            impactEntries.stream()
                .collect(Collectors.toMap(entry -> entry.fileName, entry -> entry, (a, b) -> a, LinkedHashMap::new));
        
        long mustFix = findings.stream().filter(f -> f.severity == Severity.MUST_FIX).count();
        long shouldFix = findings.stream().filter(f -> f.severity == Severity.SHOULD_FIX).count();
        long consider = findings.stream().filter(f -> f.severity == Severity.CONSIDER).count();
        boolean blocked = mustFix > 0 && config.blockOnMustFix;
        
        appendHeader(html, currentBranch, totalStagedFiles, mustFix, shouldFix, consider);
        
        html.append("<div class='body-container'>\n");
        appendSidebar(html, files, findingsByFile);
        
        html.append("<div class='main-content'>\n");
        html.append("<div class='content'>\n");
        appendStatusBanner(html, blocked, mustFix, shouldFix);
        if (config.showTestingScope) {
            appendTestingNotes(html, testingStatusByFile);
        }

        int fileIndex = 0;
        for (ChangedFile f : files) {
            appendFileSection(html, f, fileIndex++, findingsByFile, impactByFile, reverseDependencyGraph, testingStatusByFile);
        }
        
        if (files.isEmpty()) {
            html.append("<div class='empty-state'>No files to review</div>\n");
        }
        
        html.append("</div>\n</div>\n</div>\n");
        appendScripts(html);
        html.append("</body>\n</html>");
        
        return saveReport(html.toString(), config);
    }

    private static void appendStyles(StringBuilder html) {
        html.append("<style>\n");
        html.append(":root {\n");
        html.append("  --bg-body: #f8fafc;\n");
        html.append("  --bg-header: #f8fafc;\n");
        html.append("  --bg-sidebar: #ffffff;\n");
        html.append("  --bg-main: #f8fafc;\n");
        html.append("  --bg-content: #ffffff;\n");
        html.append("  --bg-hover: #f1f5f9;\n");
        html.append("  --bg-muted: #f8fafc;\n");
        html.append("  --bg-impact: #f8fafc;\n");
        html.append("  --bg-collapsible: #ffffff;\n");
        html.append("  --bg-floating: #ffffff;\n");
        html.append("  --bg-code: #1e293b;\n");
        html.append("  --bg-pill: #e0f2fe;\n");
        html.append("  --border-color: #e2e8f0;\n");
        html.append("  --border-strong: #cbd5e1;\n");
        html.append("  --text-primary: #0f172a;\n");
        html.append("  --text-secondary: #1e293b;\n");
        html.append("  --text-muted: #64748b;\n");
        html.append("  --text-soft: #94a3b8;\n");
        html.append("  --text-inverted: #f8fafc;\n");
        html.append("  --accent-primary: #3b82f6;\n");
        html.append("  --accent-critical: #ef4444;\n");
        html.append("  --accent-warning: #f59e0b;\n");
        html.append("  --accent-low: #3b82f6;\n");
        html.append("  --status-good-bg: #dcfce7;\n");
        html.append("  --status-good-text: #166534;\n");
        html.append("  --status-warning-bg: #fef3c7;\n");
        html.append("  --status-warning-text: #92400e;\n");
        html.append("  --status-error-bg: #fee2e2;\n");
        html.append("  --status-error-text: #991b1b;\n");
        html.append("  --severity-critical-bg: #fee2e2;\n");
        html.append("  --severity-critical-text: #991b1b;\n");
        html.append("  --severity-warning-bg: #fef3c7;\n");
        html.append("  --severity-warning-text: #92400e;\n");
        html.append("  --severity-consider-bg: #dbeafe;\n");
        html.append("  --severity-consider-text: #1e40af;\n");
        html.append("  --scope-changed-bg: #dcfce7;\n");
        html.append("  --scope-changed-text: #166534;\n");
        html.append("  --scope-context-bg: #f1f5f9;\n");
        html.append("  --scope-context-text: #475569;\n");
        html.append("  --pill-text: #0369a1;\n");
        html.append("  --code-text: #e2e8f0;\n");
        html.append("  --mini-badge-bg: #e2e8f0;\n");
        html.append("  --mini-badge-text: #0f172a;\n");
        html.append("  --inline-count-api-bg: rgba(14,165,233,0.2);\n");
        html.append("  --inline-count-api-text: #075985;\n");
        html.append("  --inline-count-methods-bg: rgba(99,102,241,0.18);\n");
        html.append("  --inline-count-methods-text: #3730a3;\n");
        html.append("  --inline-count-notes-bg: rgba(15,23,42,0.12);\n");
        html.append("  --inline-count-notes-text: #0f172a;\n");
        html.append("  --inline-count-tests-bg: rgba(16,185,129,0.18);\n");
        html.append("  --inline-count-tests-text: #047857;\n");
        html.append("  --floating-shadow: 0 15px 30px rgba(15,23,42,0.15);\n");
        html.append("  --card-shadow: 0 1px 3px rgba(0,0,0,0.1);\n");
        html.append("  --toggle-track: #e2e8f0;\n");
        html.append("  --toggle-thumb: #ffffff;\n");
        html.append("}\n");
        html.append("[data-theme='dark'] {\n");
        html.append("  --bg-body: #0f172a;\n");
        html.append("  --bg-header: #0f172a;\n");
        html.append("  --bg-sidebar: #111b2e;\n");
        html.append("  --bg-main: #0f172a;\n");
        html.append("  --bg-content: #1e293b;\n");
        html.append("  --bg-hover: #1f2937;\n");
        html.append("  --bg-muted: #142033;\n");
        html.append("  --bg-impact: #111b2e;\n");
        html.append("  --bg-collapsible: #1f2937;\n");
        html.append("  --bg-floating: #111b2e;\n");
        html.append("  --bg-code: #0b1220;\n");
        html.append("  --bg-pill: rgba(14,165,233,0.2);\n");
        html.append("  --border-color: #334155;\n");
        html.append("  --border-strong: #475569;\n");
        html.append("  --text-primary: #f8fafc;\n");
        html.append("  --text-secondary: #e2e8f0;\n");
        html.append("  --text-muted: #94a3b8;\n");
        html.append("  --text-soft: #94a3b8;\n");
        html.append("  --text-inverted: #0f172a;\n");
        html.append("  --accent-primary: #60a5fa;\n");
        html.append("  --accent-critical: #f87171;\n");
        html.append("  --accent-warning: #fbbf24;\n");
        html.append("  --accent-low: #60a5fa;\n");
        html.append("  --status-good-bg: rgba(34,197,94,0.18);\n");
        html.append("  --status-good-text: #4ade80;\n");
        html.append("  --status-warning-bg: rgba(245,158,11,0.18);\n");
        html.append("  --status-warning-text: #fbbf24;\n");
        html.append("  --status-error-bg: rgba(239,68,68,0.2);\n");
        html.append("  --status-error-text: #fca5a5;\n");
        html.append("  --severity-critical-bg: rgba(239,68,68,0.24);\n");
        html.append("  --severity-critical-text: #fecaca;\n");
        html.append("  --severity-warning-bg: rgba(245,158,11,0.24);\n");
        html.append("  --severity-warning-text: #fde68a;\n");
        html.append("  --severity-consider-bg: rgba(59,130,246,0.25);\n");
        html.append("  --severity-consider-text: #bfdbfe;\n");
        html.append("  --scope-changed-bg: rgba(34,197,94,0.25);\n");
        html.append("  --scope-changed-text: #bbf7d0;\n");
        html.append("  --scope-context-bg: rgba(148,163,184,0.2);\n");
        html.append("  --scope-context-text: #e2e8f0;\n");
        html.append("  --pill-text: #bae6fd;\n");
        html.append("  --code-text: #e2e8f0;\n");
        html.append("  --mini-badge-bg: rgba(148,163,184,0.2);\n");
        html.append("  --mini-badge-text: #e2e8f0;\n");
        html.append("  --inline-count-api-bg: rgba(14,165,233,0.35);\n");
        html.append("  --inline-count-api-text: #e0f2fe;\n");
        html.append("  --inline-count-methods-bg: rgba(99,102,241,0.4);\n");
        html.append("  --inline-count-methods-text: #e0e7ff;\n");
        html.append("  --inline-count-notes-bg: rgba(15,23,42,0.5);\n");
        html.append("  --inline-count-notes-text: #f1f5f9;\n");
        html.append("  --inline-count-tests-bg: rgba(16,185,129,0.35);\n");
        html.append("  --inline-count-tests-text: #d1fae5;\n");
        html.append("  --floating-shadow: 0 15px 30px rgba(0,0,0,0.55);\n");
        html.append("  --card-shadow: 0 1px 3px rgba(0,0,0,0.4);\n");
        html.append("  --toggle-track: #1f2937;\n");
        html.append("  --toggle-thumb: #0f172a;\n");
        html.append("}\n");
        html.append("body { font-family: system-ui, sans-serif; margin: 0; background: var(--bg-body); color: var(--text-primary); display: flex; flex-direction: column; height: 100vh; overflow: hidden; transition: background 0.2s ease, color 0.2s ease; }\n");
        html.append(".header-fixed { background: var(--bg-header); min-height: 100px; padding: 10px 20px; border-bottom: 1px solid var(--border-color); box-shadow: var(--card-shadow); box-sizing: border-box; flex-shrink: 0; color: var(--text-primary); }\n");
        html.append(".body-container { display: flex; flex: 1; overflow: hidden; }\n");
        html.append(".sidebar { width: 320px; background: var(--bg-sidebar); border-right: 1px solid var(--border-color); display: flex; flex-direction: column; overflow-y: auto; flex-shrink: 0; }\n");
        html.append(".main-content { flex: 1; overflow-y: auto; padding: 20px; background: var(--bg-main); }\n");
        html.append(".file-list { flex: 1; overflow-y: auto; }\n");
        html.append(".file-item { padding: 10px 20px; cursor: pointer; border-bottom: 1px solid var(--border-color); transition: all 0.2s; color: var(--text-primary); }\n");
        html.append(".file-item:hover { background: var(--bg-hover); }\n");
        html.append(".file-item.active { background: var(--accent-primary); color: var(--text-inverted); }\n");
        html.append(".file-name { font-weight: 600; margin-bottom: 2px; font-size: 14px; }\n");
        html.append(".file-stats { font-size: 11px; color: var(--text-muted); }\n");
        html.append(".active .file-stats { color: #bfdbfe; }\n");
        html.append(".content { background: var(--bg-content); border-radius: 8px; padding: 20px; box-shadow: var(--card-shadow); transition: background 0.2s ease, color 0.2s ease; }\n");
        html.append(".status { padding: 12px; border-radius: 6px; margin-bottom: 20px; font-weight: 600; font-size: 14px; border: 1px solid transparent; }\n");
        html.append(".status-good { background: var(--status-good-bg); color: var(--status-good-text); border-color: rgba(190,242,100,0.55); }\n");
        html.append(".status-warning { background: var(--status-warning-bg); color: var(--status-warning-text); border-color: rgba(251,191,36,0.5); }\n");
        html.append(".status-error { background: var(--status-error-bg); color: var(--status-error-text); border-color: rgba(248,113,113,0.5); }\n");
        html.append(".finding { border: 1px solid var(--border-color); border-radius: 8px; margin-bottom: 12px; overflow: hidden; cursor: pointer; transition: all 0.2s; background: var(--bg-content); }\n");
        html.append(".finding:hover { border-color: var(--border-strong); box-shadow: 0 2px 4px rgba(0,0,0,0.05); }\n");
        html.append(".finding.unchanged { border-left: 4px solid var(--text-soft); opacity: 0.85; }\n");
        html.append(".finding.unchanged:hover { opacity: 1; }\n");
        html.append(".finding-header { background: var(--bg-muted); padding: 10px 16px; display: flex; align-items: center; gap: 12px; flex-wrap: wrap; border-bottom: 1px solid transparent; color: var(--text-primary); }\n");
        html.append(".finding.expanded .finding-header { border-bottom: 1px solid var(--border-color); }\n");
        html.append(".scope-badge { font-size: 10px; padding: 2px 6px; border-radius: 4px; font-weight: bold; text-transform: uppercase; }\n");
        html.append(".scope-changed { background: var(--scope-changed-bg); color: var(--scope-changed-text); }\n");
        html.append(".scope-context { background: var(--scope-context-bg); color: var(--scope-context-text); }\n");
        html.append(".finding-body { display: none; padding: 0; background: var(--bg-content); }\n");
        html.append(".finding.expanded .finding-body { display: block; }\n");
        html.append(".expand-icon { margin-left: auto; transition: transform 0.2s; color: var(--text-soft); }\n");
        html.append(".finding.expanded .expand-icon { transform: rotate(180deg); }\n");
        html.append(".issue-group { padding: 12px 16px; border-bottom: 1px solid var(--border-color); }\n");
        html.append(".issue-group:last-child { border-bottom: none; }\n");
        html.append(".severity { padding: 4px 8px; border-radius: 4px; font-size: 11px; font-weight: bold; text-transform: uppercase; }\n");
        html.append(".severity-MUST_FIX { background: var(--severity-critical-bg); color: var(--severity-critical-text); }\n");
        html.append(".severity-SHOULD_FIX { background: var(--severity-warning-bg); color: var(--severity-warning-text); }\n");
        html.append(".severity-CONSIDER { background: var(--severity-consider-bg); color: var(--severity-consider-text); }\n");
        html.append(".category-badge { padding: 4px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; background: var(--mini-badge-bg); color: var(--mini-badge-text); text-transform: uppercase; }\n");
        html.append(".code-block { background: var(--bg-code); color: var(--code-text); padding: 12px 16px; font-family: 'Consolas', monospace; font-size: 13px; overflow-x: auto; }\n");
        html.append(".details { padding: 12px 16px; font-size: 14px; color: var(--text-secondary); }\n");
        html.append(".fix-section { background: var(--scope-changed-bg); padding: 12px 16px; border-top: 1px solid rgba(16,185,129,0.35); font-size: 14px; color: var(--scope-changed-text); }\n");
        html.append(".fix-code { font-family: 'Consolas', monospace; color: var(--scope-changed-text); white-space: pre-wrap; margin-top: 4px; }\n");
        html.append(".empty-state { text-align: center; padding: 40px 20px; color: var(--text-muted); }\n");
        html.append(".file-content { display: none; }\n");
        html.append(".file-content.active { display: block; }\n");
        html.append(".impact-map { border: 1px solid var(--border-color); border-radius: 8px; padding: 15px 280px 15px 15px; margin-bottom: 20px; background: var(--bg-impact); position: relative; color: var(--text-primary); }\n");
        html.append(".impact-row { margin: 6px 0; font-size: 13px; color: var(--text-secondary); }\n");
        html.append(".impact-label { font-weight: 600; color: var(--text-primary); display: inline-block; min-width: 140px; }\n");
        html.append(".pill { display: inline-block; padding: 2px 8px; border-radius: 999px; background: var(--bg-pill); color: var(--pill-text); font-size: 11px; margin: 2px 4px 2px 0; }\n");
        html.append(".summary-cards { display: flex; gap: 10px; margin-top: 10px; }\n");
        html.append(".summary-card { background: var(--bg-content); padding: 8px 16px; border-radius: 6px; border: 1px solid var(--border-color); text-align: center; box-shadow: var(--card-shadow); flex: 1; display: flex; align-items: center; justify-content: center; gap: 10px; color: var(--text-primary); }\n");
        html.append(".summary-card.critical { border-left: 3px solid var(--accent-critical); }\n");
        html.append(".summary-card.high { border-left: 3px solid var(--accent-warning); }\n");
        html.append(".summary-card.low { border-left: 3px solid var(--accent-low); }\n");
        html.append(".card-count { font-size: 18px; font-weight: 700; }\n");
        html.append(".card-label { font-size: 11px; color: var(--text-muted); font-weight: 600; text-transform: uppercase; }\n");
        html.append(".impact-flow { display: flex; align-items: center; gap: 10px; margin-top: 10px; padding: 10px; background: var(--bg-muted); border-radius: 6px; border: 1px solid var(--border-color); overflow-x: auto; }\n");
        html.append(".flow-node { background: var(--bg-content); border: 1px solid var(--border-color); padding: 4px 10px; border-radius: 4px; font-size: 12px; font-weight: 600; white-space: nowrap; color: var(--text-primary); }\n");
        html.append(".flow-arrow { color: var(--text-soft); font-weight: bold; }\n");
        html.append(".impact-graph { margin-top: 15px; border-top: 1px dashed var(--border-color); padding-top: 15px; }\n");
        html.append(".impact-graph-title { font-size: 13px; font-weight: 700; color: var(--text-secondary); margin-bottom: 8px; text-transform: uppercase; letter-spacing: 0.05em; }\n");
        html.append(".dependent-node { margin-bottom: 15px; }\n");
        html.append(".collapsible-card { border: 1px solid var(--border-color); border-radius: 8px; margin-top: 6px; background: var(--bg-collapsible); box-shadow: var(--card-shadow); transition: background 0.2s ease; }\n");
        html.append(".collapsible-header { padding: 6px 10px; gap: 8px; display: flex; align-items: center; cursor: pointer; font-size: 13px; color: var(--text-primary); }\n");
        html.append(".collapsible-body { display: none; padding: 0 10px 10px; border-top: 1px solid var(--border-color); }\n");
        html.append(".collapsible-card.expanded .collapsible-body { display: block; }\n");
        html.append(".collapsible-card .chevron { transition: transform 0.2s ease; color: var(--text-soft); }\n");
        html.append(".collapsible-card.expanded .chevron { transform: rotate(180deg); }\n");
        html.append(".impact-list { list-style: none; padding-left: 0; margin: 12px 0 0; display: flex; flex-direction: column; gap: 6px; font-size: 12px; color: var(--text-secondary); }\n");
        html.append(".impact-list li { padding: 4px 8px; background: var(--bg-muted); font-size: 12px; border-radius: 6px; border: 1px solid var(--border-color); }\n");
        html.append(".impact-summary { display: flex; gap: 8px; margin: 8px 0 6px; flex-wrap: wrap; }\n");
        html.append(".mini-badge { flex: 0 1 auto; padding: 5px 10px; border-radius: 999px; font-size: 12px; font-weight: 600; display: flex; align-items: center; gap: 6px; background: var(--mini-badge-bg); color: var(--mini-badge-text); }\n");
        html.append(".mini-badge .count { font-size: 13px; font-weight: 700; }\n");
        html.append(".mini-badge.api { background: rgba(14,165,233,0.18); color: var(--pill-text); }\n");
        html.append(".mini-badge.methods { background: rgba(99,102,241,0.18); color: #4338ca; }\n");
        html.append(".mini-badge.notes { background: rgba(15,23,42,0.1); color: var(--text-primary); }\n");
        html.append(".title-icon { font-size: 14px; }\n");
        html.append(".collapsible-title { display: flex; flex-direction: column; gap: 2px; }\n");
        html.append(".title-subtext { font-size: 10px; color: var(--text-soft); font-weight: 500; }\n");
        html.append(".inline-count { padding: 2px 8px; border-radius: 999px; font-size: 8px; font-weight: 700; margin-left: auto; }\n");
        html.append(".inline-count.api { background: var(--inline-count-api-bg); color: var(--inline-count-api-text); }\n");
        html.append(".inline-count.methods { background: var(--inline-count-methods-bg); color: var(--inline-count-methods-text); }\n");
        html.append(".inline-count.notes { background: var(--inline-count-notes-bg); color: var(--inline-count-notes-text); }\n");
        html.append(".inline-count.tests { background: var(--inline-count-tests-bg); color: var(--inline-count-tests-text); }\n");
        html.append(".floating-summary { position: absolute; top: 15px; right: 15px; width: 230px; background: var(--bg-floating); border: 1px solid var(--border-color); border-radius: 12px; padding: 14px; box-shadow: var(--floating-shadow); display: flex; flex-direction: column; gap: 10px; color: var(--text-primary); }\n");
        html.append(".floating-summary.hidden { display: none; }\n");
        html.append(".floating-summary-title { font-size: 12px; letter-spacing: 0.08em; text-transform: uppercase; color: var(--text-soft); font-weight: 700; }\n");
        html.append(".floating-summary-item { display: flex; flex-direction: column; gap: 2px; }\n");
        html.append(".floating-summary-item .label { font-size: 12px; color: var(--text-soft); text-transform: uppercase; letter-spacing: 0.05em; }\n");
        html.append(".floating-summary-item .count { font-size: 22px; font-weight: 700; color: var(--text-primary); }\n");
        html.append(".floating-summary-item .subtext { font-size: 11px; color: var(--text-muted); }\n");
        html.append(".theme-toggle { position: relative; width: 78px; height: 32px; border-radius: 999px; border: 1px solid var(--border-color); background: var(--toggle-track); display: flex; align-items: center; justify-content: space-between; padding: 0 10px; cursor: pointer; transition: background 0.2s ease, color 0.2s ease; color: var(--text-muted); }\n");
        html.append(".theme-toggle .theme-icon { font-size: 14px; line-height: 1; }\n");
        html.append(".theme-toggle .theme-thumb { position: absolute; top: 4px; left: 6px; width: 24px; height: 24px; border-radius: 999px; background: var(--toggle-thumb); box-shadow: 0 2px 4px rgba(0,0,0,0.2); transition: transform 0.2s ease; }\n");
        html.append(".theme-toggle[data-theme='dark'] .theme-thumb { transform: translateX(34px); }\n");
        html.append(".theme-toggle:focus-visible { outline: 2px solid var(--accent-primary); outline-offset: 2px; }\n");
        html.append("</style>\n");
    }

    private static void appendSidebar(StringBuilder html, List<ChangedFile> files, Map<String, List<Finding>> findingsByFile) {
        html.append("<div class='sidebar'>\n");
        html.append("<div class='header'><h3 style='margin:0;'>Files</h3></div>\n");
        html.append("<div class='file-list'>\n");
        int index = 0;
        for (ChangedFile f : files) {
            List<Finding> fileFindings = findingsByFile.getOrDefault(f.name, Collections.emptyList());
            long mustFix = fileFindings.stream().filter(fi -> fi.severity == Severity.MUST_FIX).count();
            long shouldFix = fileFindings.stream().filter(fi -> fi.severity == Severity.SHOULD_FIX).count();
            String active = (index == 0) ? " active" : "";
            html.append("<div class='file-item").append(active).append("' onclick='showFile(").append(index++).append(")'>\n");
            html.append("<div class='file-name'>").append(escapeHtml(f.name)).append("</div>\n");
            html.append("<div class='file-stats'>").append(mustFix).append(" critical, ").append(shouldFix).append(" warnings</div>\n");
            String lines = formatLineRanges(f.changedLines);
            if (!lines.isEmpty()) {
                html.append("<div class='file-stats'>Lines: ").append(escapeHtml(lines)).append("</div>\n");
            }
            html.append("</div>\n");
        }
        html.append("</div>\n</div>\n");
    }

    private static void appendHeader(StringBuilder html, String branch, int totalFiles, long must, long should, long low) {
        html.append("<div class='header-fixed' style='margin-bottom: 20px; box-shadow: 0 1px 3px rgba(0,0,0,0.05);'>\n");
        html.append("<div style='display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 25px;'>\n");
        html.append("<div><h1 style='margin:0; color: var(--text-primary); font-size: 24px;'>Code Review Report</h1>\n");
        html.append("<div style='color: var(--text-muted); font-size: 14px; margin-top: 6px; display: flex; align-items: center; gap: 15px;'>\n");
        html.append("<span>Branch: <span style='color: var(--accent-primary); font-weight: 600;'>").append(escapeHtml(branch)).append("</span></span>\n");
        html.append("<span>Staged Files: <span style='color: var(--text-primary); font-weight: 600;'>").append(totalFiles).append("</span></span>\n");
        html.append("</div></div>\n");
        html.append("<div style='display:flex; flex-direction:column; align-items:flex-end; gap:10px;'>\n");
        html.append("<button id='theme-toggle' class='theme-toggle' type='button' onclick='toggleTheme()' aria-pressed='false' data-theme='light'>\n");
        html.append("<span class='theme-icon'>☀</span>\n");
        html.append("<span class='theme-thumb'></span>\n");
        html.append("<span class='theme-icon'>🌙</span>\n");
        html.append("</button>\n");
        html.append("<div style='font-size: 12px; color: var(--text-soft); text-align: right;'>Version ").append(VERSION).append("<br>").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("</div>\n");
        html.append("</div>\n");
        html.append("</div>\n");
        html.append("<div class='summary-cards'>\n");
        html.append("<div class='summary-card critical'><span class='card-count' style='color:#ef4444;'>").append(must).append("</span><span class='card-label'>Critical</span></div>\n");
        html.append("<div class='summary-card high'><span class='card-count' style='color:#f59e0b;'>").append(should).append("</span><span class='card-label'>High</span></div>\n");
        html.append("<div class='summary-card low'><span class='card-count' style='color:#3b82f6;'>").append(low).append("</span><span class='card-label'>Low</span></div>\n");
        html.append("</div>\n</div>\n");
    }

    private static void appendStatusBanner(StringBuilder html, boolean blocked, long must, long should) {
        if (blocked) {
            html.append("<div class='status status-error'>&#9888; STATUS: NEEDS ATTENTION - Fix critical issues before committing</div>\n");
        } else if (must > 0 || should > 0) {
            html.append("<div class='status status-warning'>&#10003; STATUS: Review recommended before committing</div>\n");
        } else {
            html.append("<div class='status status-good'>&#10003; STATUS: LOOKS GOOD - Ready to commit</div>\n");
        }
    }

    private static void appendTestingNotes(StringBuilder html, Map<String, TestingStatus> statusMap) {
        if (statusMap == null || statusMap.isEmpty()) return;
        long withTests = statusMap.values().stream().filter(s -> s.hasTests).count();
        long withoutTests = statusMap.size() - withTests;
        html.append("<div class='impact-map' style='background: #f0fdf4; border-color: #bbf7d0;'>\n");
        html.append("<div class='impact-graph-title' style='color: #166534;'>Test Coverage & Testing Notes</div>\n");
        html.append("<ul style='margin: 10px 0; padding-left: 20px; font-size: 14px; color: #166534;'>\n");
        html.append("<li>Total files: ").append(statusMap.size()).append(", with related tests: ").append(withTests)
            .append(", missing tests: ").append(withoutTests).append("</li>\n");
        html.append("</ul>\n</div>\n");
    }

    private static void appendFileSection(StringBuilder html, ChangedFile f, int index, Map<String, List<Finding>> findingsByFile, Map<String, ImpactEntry> impactByFile, Map<String, Set<String>> reverseGraph, Map<String, TestingStatus> statusMap) {
        String active = (index == 0) ? " active" : "";
        html.append("<div class='file-content").append(active).append("' id='file-").append(index).append("'>\n");
        html.append("<h2>").append(escapeHtml(f.name)).append("</h2>\n");
        String lines = formatLineRanges(f.changedLines);
        if (!lines.isEmpty()) {
            html.append("<div style='color:#64748b; margin-bottom:12px;'>Modified lines: ").append(escapeHtml(lines)).append("</div>\n");
        }
        TestingStatus status = statusMap == null ? null : statusMap.get(f.name);
        if (status != null) {
            appendPerFileTesting(html, status);
        }
        List<Finding> fileFindings = findingsByFile.getOrDefault(f.name, Collections.emptyList());
        if (fileFindings.isEmpty()) {
            fileFindings = findingsByFile.getOrDefault(f.path, Collections.emptyList());
        }
        // Ensure we check by name consistently
        ImpactEntry impact = impactByFile.get(f.name);
        if (impact == null) {
            // Fallback to name without path if necessary
            String simpleName = Paths.get(f.path).getFileName().toString();
            impact = impactByFile.get(simpleName);
        }

        if (impact != null) {
            appendImpactMap(html, impact, f, reverseGraph);
        }

        if (fileFindings.isEmpty()) {
            html.append("<div class='empty-state'>No issues found in this file</div>\n");
        } else {
            Map<Integer, List<Finding>> grouped = fileFindings.stream()
                .collect(Collectors.groupingBy(f1 -> f1.line, TreeMap::new, Collectors.toList()));
            
            for (Map.Entry<Integer, List<Finding>> entry : grouped.entrySet()) {
                appendGroupedFindings(html, entry.getKey(), entry.getValue(), f);
            }
        }
        html.append("</div>\n");
    }

    private static void appendImpactSummary(StringBuilder html, ImpactEntry impact, List<String> displayFunctions, List<String> displayNotes) {
        int total = impact.endpoints.size() + displayFunctions.size() + displayNotes.size();
        if (total == 0) return;

        MethodBreakdown breakdown = computeMethodBreakdown(displayNotes, displayFunctions.size());

        html.append("<div class='impact-summary'>");
        appendMiniBadge(html, "mini-badge api", "&#128279;", "APIs", impact.endpoints.size(), pluralize("endpoint", impact.endpoints.size()));
        appendMethodsBadge(html, displayFunctions.size(), breakdown);
        appendMiniBadge(html, "mini-badge notes", "&#128221;", "Notes", displayNotes.size(), pluralize("note", displayNotes.size()));
        html.append("</div>");
    }

    private static void appendMiniBadge(StringBuilder html, String className, String icon, String label, int value, String suffix) {
        html.append("<div class='" + className + "'>");
        html.append("<span class='title-icon'>").append(icon).append("</span>");
        html.append("<span>").append(escapeHtml(label)).append("</span>");
        html.append("<span class='count'>").append(value).append("</span>");
        html.append("<span>").append(escapeHtml(suffix)).append("</span>");
        html.append("</div>");
    }

    private static void appendMethodsBadge(StringBuilder html, int count, MethodBreakdown breakdown) {
        html.append("<div class='mini-badge methods'>");
        html.append("<span class='title-icon'>&#9881;</span>");
        html.append("<span>Methods</span>");
        html.append("<span class='count'>").append(count).append("</span>");
        html.append("<span>").append(escapeHtml(formatMethodSummary(breakdown, count))).append("</span>");
        html.append("</div>");
    }

    private static void appendFloatingSummaryBox(StringBuilder html, ImpactEntry impact, List<String> displayFunctions, List<String> displayNotes) {
        int total = impact.endpoints.size() + displayFunctions.size() + displayNotes.size() + impact.recommendedTests.size();
        if (total == 0) {
            html.append("<div class='floating-summary hidden'></div>");
            return;
        }

        MethodBreakdown breakdown = computeMethodBreakdown(displayNotes, displayFunctions.size());
        html.append("<div class='floating-summary'>");
        html.append("<div class='floating-summary-title'>Impact Snapshot</div>");
        appendFloatingItem(html, "APIs", impact.endpoints.size(), pluralize("endpoint", impact.endpoints.size()));
        appendFloatingItem(html, "Methods", displayFunctions.size(), formatMethodSummary(breakdown, displayFunctions.size()));
        //appendFloatingItem(html, "Notes", displayNotes.size(), pluralize("note", displayNotes.size()));
        //appendFloatingItem(html, "Tests", impact.recommendedTests.size(), impact.recommendedTests.isEmpty() ? "No tests mapped" : "Tests suggested");
        html.append("</div>");
    }

    private static void appendFloatingItem(StringBuilder html, String label, int count, String subtext) {
        html.append("<div class='floating-summary-item'>");
        html.append("<div class='label'>").append(escapeHtml(label)).append("</div>");
        html.append("<div class='count'>").append(count).append("</div>");
        html.append("<div class='subtext'>").append(escapeHtml(subtext)).append("</div>");
        html.append("</div>");
    }

    private static MethodBreakdown computeMethodBreakdown(List<String> notes, int fallbackTotal) {
        MethodBreakdown breakdown = new MethodBreakdown();
        if (notes == null || notes.isEmpty()) {
            breakdown.total = fallbackTotal;
            return breakdown;
        }
        for (String note : notes) {
            Matcher matcher = Pattern.compile("(?i)(Controller|Service|Repository)").matcher(note);
            boolean matched = false;
            while (matcher.find()) {
                breakdown.tally(matcher.group(1));
                matched = true;
            }
            if (!matched && note.toLowerCase(Locale.ROOT).contains("method")) {
                breakdown.total++;
            }
        }
        if (breakdown.total == 0) breakdown.total = fallbackTotal;
        return breakdown;
    }

    private static String formatBreakdown(MethodBreakdown breakdown) {
        if (breakdown.total == 0) return "No callers";
        List<String> parts = new ArrayList<>();
        if (breakdown.controllers > 0) parts.add(breakdown.controllers + " controller");
        if (breakdown.services > 0) parts.add(breakdown.services + " service");
        if (breakdown.repositories > 0) parts.add(breakdown.repositories + " repo");
        if (parts.isEmpty()) parts.add(breakdown.total + " callers");
        return String.join(" • ", parts);
    }

    private static String methodSummarySubtitle(List<String> filteredNotes, int fallbackTotal) {
        MethodBreakdown breakdown = computeMethodBreakdown(filteredNotes, fallbackTotal);
        int total = Math.max(fallbackTotal, breakdown.total);
        return formatMethodSummary(breakdown, total);
    }

    private static String formatMethodSummary(MethodBreakdown breakdown, int totalMethods) {
        if (totalMethods == 0) return "No methods touched";
        List<String> pieces = new ArrayList<>();
        if (breakdown.controllers > 0) {
            pieces.add(breakdown.controllers + " method" + (breakdown.controllers == 1 ? "" : "s")+" in controllers");
        }
        if (breakdown.services > 0) {
            pieces.add(breakdown.services + " method" + (breakdown.services == 1 ? "" : "s")+" in services");
        }
        if (breakdown.repositories > 0) {
            pieces.add(breakdown.repositories + " repo" + (breakdown.repositories == 1 ? "" : "s"));
        }
        if (pieces.isEmpty()) {
            pieces.add(totalMethods + " method" + (totalMethods == 1 ? "" : "s"));
        }
        return String.join(" • ", pieces);
    }

    private static List<String> filterDisplayItems(List<String> items) {
        if (items == null || items.isEmpty()) return Collections.emptyList();
        List<String> filtered = new ArrayList<>();
        for (String item : items) {
            if (item == null) continue;
            String method = extractMethodToken(item);
            if (method.isEmpty() || NON_DISPLAY_TOKENS.contains(method.toLowerCase(Locale.ROOT))) {
                continue;
            }
            filtered.add(item);
        }
        return filtered;
    }

    private static String extractMethodToken(String entry) {
        String token = entry;
        int arrow = entry.lastIndexOf("->");
        if (arrow != -1) {
            token = entry.substring(arrow + 2);
        }
        token = token.trim();
        if (token.endsWith("()")) {
            token = token.substring(0, token.length() - 2);
        }
        int space = token.indexOf(' ');
        if (space != -1) {
            token = token.substring(0, space);
        }
        return token.replaceAll("[^A-Za-z0-9_]", "");
    }

    private static String pluralize(String word, int count) {
        return count == 1 ? word : word + "s";
    }

    private static class MethodBreakdown {
        int controllers;
        int services;
        int repositories;
        int total;

        void tally(String type) {
            total++;
            String lower = type.toLowerCase(Locale.ROOT);
            if (lower.contains("controller")) controllers++;
            else if (lower.contains("service")) services++;
            else if (lower.contains("repo")) repositories++;
        }
    }

    private static void appendPerFileTesting(StringBuilder html, TestingStatus status) {
        String badgeClass = status.hasTests ? "status-good" : "status-warning";
        String message = status.hasTests ? "Related tests detected" : "No related tests found";
        html.append("<div class='status ").append(badgeClass).append("' style='margin-bottom:12px;'>").append(message);
        if (status.hasTests && !status.relatedTests.isEmpty()) {
            html.append("<div style='margin-top:8px; font-size:13px; color:#475569;'>");
            for (String test : status.relatedTests) {
                html.append("<div>").append(escapeHtml(test)).append("</div>");
            }
            html.append("</div>");
        }
        html.append("</div>\n");
    }

    private static void appendChangedCode(StringBuilder html, ChangedFile f, List<Finding> fileFindings) {
        if (f.changedLines == null || f.changedLines.isEmpty()) return;
        try {
            List<String> fileLines = Files.readAllLines(Path.of(f.path));
            List<Integer> sorted = new ArrayList<>(f.changedLines);
            Collections.sort(sorted);

            Map<Integer, List<Finding>> findingsByLine = new HashMap<>();
            for (Finding fi : fileFindings) {
                findingsByLine.computeIfAbsent(fi.line, k -> new ArrayList<>()).add(fi);
            }

            html.append("<div class='finding' style='border-style:dashed; margin-bottom:20px;'>\n");
            html.append("<div class='finding-header'><span style='color:#64748b;'>Changed code</span></div>\n");
            html.append("<div class='code-block' style='background:#0f172a;'>\n");
            for (int ln : sorted) {
                if (ln <= 0 || ln > fileLines.size()) continue;
                String codeLine = fileLines.get(ln - 1);
                html.append("<div style='display:flex; align-items:flex-start; gap:10px;'>");
                html.append("<span style='color:#94a3b8; min-width:40px; display:inline-block;'>").append(ln).append("</span>");
                html.append("<span style='flex:1;'>").append(escapeHtml(codeLine)).append("</span>");
                List<Finding> lineFindings = findingsByLine.getOrDefault(ln, Collections.emptyList());
                if (!lineFindings.isEmpty()) {
                    html.append("<span style='display:flex; flex-direction:column; gap:4px;'>");
                    for (Finding fi : lineFindings) {
                        html.append("<span class='severity severity-").append(fi.severity)
                            .append("' style='font-size:10px; padding:2px 6px;'>")
                            .append(fi.category.displayName).append("</span>");
                    }
                    html.append("</span>");
                }
                html.append("</div>\n");
            }
            html.append("</div>\n</div>\n");
        } catch (IOException ignored) {
            // If reading fails, skip showing snippets
        }
    }

    private static String formatLineRanges(Set<Integer> lines) {
        if (lines == null || lines.isEmpty()) return "";
        List<Integer> sorted = new ArrayList<>(lines);
        Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        int start = sorted.get(0);
        int prev = start;
        for (int i = 1; i < sorted.size(); i++) {
            int curr = sorted.get(i);
            if (curr == prev + 1) {
                prev = curr;
                continue;
            }
            appendRange(sb, start, prev);
            start = prev = curr;
        }
        appendRange(sb, start, prev);
        return sb.toString();
    }

    private static void appendRange(StringBuilder sb, int start, int end) {
        if (sb.length() > 0) sb.append(", ");
        if (start == end) sb.append(start);
        else sb.append(start).append('-').append(end);
    }

    private static void appendImpactMap(StringBuilder html, ImpactEntry impact, ChangedFile f, Map<String, Set<String>> reverseGraph) {
        html.append("<div class='impact-map'>\n");
        if (!impact.layers.isEmpty()) {
            html.append("<div class='impact-row'><span class='impact-label'>Layers:</span><span>");
            for (String l : impact.layers) html.append("<span class='pill'>").append(escapeHtml(l)).append("</span>");
            html.append("</span></div>\n");
        }

        List<String> displayFunctions = filterDisplayItems(impact.functions);
        List<String> displayNotes = filterDisplayItems(impact.notes);

        //appendImpactSummary(html, impact, displayFunctions, displayNotes);
        appendFloatingSummaryBox(html, impact, displayFunctions, displayNotes);

        String apiSubtitle = "Endpoints touched";
        boolean hasPotential = impact.endpoints.stream().anyMatch(e -> e != null && e.startsWith("Potential:"));
        if (hasPotential) {
            apiSubtitle = "Endpoints touched (includes Potential)";
        }
        appendCollapsibleList(html, "Impacted APIs", apiSubtitle, impact.endpoints, "api");
        appendCollapsibleList(html, "Modified Methods", methodSummarySubtitle(displayNotes, displayFunctions.size()), displayFunctions, "methods");
        appendCollapsibleList(html, "Impact Notes", "Contextual insights", displayNotes, "notes");
        appendCollapsibleList(html, "Related Tests", impact.recommendedTests.isEmpty() ? "No mapped tests" : "Tests to run", impact.recommendedTests, "tests");

        // Reverse Dependency Graph Visualization
        String impactKey = null;
        if (impact != null && impact.fullyQualifiedName != null && !impact.fullyQualifiedName.isBlank()) {
            impactKey = impact.fullyQualifiedName;
        }
        if (impactKey == null) {
            impactKey = f.name.replace(".java", "");
        }
        Set<String> dependents = reverseGraph == null ? Collections.emptySet() :
            reverseGraph.getOrDefault(impactKey, reverseGraph.getOrDefault(f.name.replace(".java", ""), Collections.emptySet()));
        appendCollapsibleGraph(html, impactKey, dependents);
        html.append("</div>\n");
    }

    private static void appendCollapsibleList(StringBuilder html, String title, String subtitle, Collection<String> items, String kind) {
        if (items == null || items.isEmpty()) return;
        String cardId = "card-" + UUID.randomUUID().toString().replace("-", "");
        html.append("<div class='collapsible-card' id='").append(cardId).append("'>");
        html.append("<div class='collapsible-header' onclick=\"toggleCard('").append(cardId).append("')\">");
        html.append("<div class='collapsible-title'>");
        html.append("<span>").append(escapeHtml(title)).append("</span>");
        if (subtitle != null && !subtitle.isBlank()) {
            html.append("<span class='title-subtext'>").append(escapeHtml(subtitle)).append("</span>");
        }
        html.append("</div>");
        html.append("<span class='inline-count ").append(kind).append("'>").append(items.size()).append("</span>");
        html.append("<span class='chevron'>&#9662;</span>");
        html.append("</div>");
        html.append("<div class='collapsible-body'>");
        html.append("<ul class='impact-list'>");
        for (String item : items) {
            html.append("<li>").append(escapeHtml(item)).append("</li>");
        }
        html.append("</ul></div></div>");
    }

    private static void appendCollapsibleGraph(StringBuilder html, String impactKey, Set<String> dependents) {
        if (dependents == null || dependents.isEmpty()) return;
        List<String> prodDeps = new ArrayList<>();
        List<String> testDeps = new ArrayList<>();
        for (String dep : dependents) {
            if (isTestDependency(dep)) {
                testDeps.add(dep);
            } else {
                prodDeps.add(dep);
            }
        }

        String cardId = "graph-" + UUID.randomUUID().toString().replace("-", "");
        html.append("<div class='collapsible-card' id='").append(cardId).append("'>");
        html.append("<div class='collapsible-header' onclick=\"toggleCard('").append(cardId).append("')\">");
        html.append("<span>Reverse Dependency Mapping</span>");
        html.append("<span class='inline-count api'>").append(dependents.size()).append("</span>");
        html.append("<span class='chevron'>&#9662;</span>");
        html.append("</div>");
        html.append("<div class='collapsible-body'>");

        appendDependencySection(html, impactKey, "Application Classes", prodDeps);
        appendDependencySection(html, impactKey, "Test Classes", testDeps);

        html.append("</div></div>");
    }

    private static void appendDependencySection(StringBuilder html, String impactKey, String title, List<String> deps) {
        if (deps == null || deps.isEmpty()) return;
        html.append("<div style='margin-bottom:12px;'>");
        html.append("<div style='font-size:13px;font-weight:600;color:#475569;margin-bottom:6px;'>")
            .append(escapeHtml(title)).append(" (" + deps.size() + ")</div>");
        for (String dep : deps) {
            Path depPath = Path.of(dep);
            String depName = depPath.getFileName().toString();
            html.append("<div class='dependent-node'>\n");
            html.append("<div class='impact-flow'>\n");
            html.append("<div class='flow-node'>").append(escapeHtml(impactKey.replaceAll(".*\\.", ""))).append("</div>\n");
            html.append("<div class='flow-arrow'>&rarr;</div>\n");
            html.append("<div class='flow-node'>").append(escapeHtml(depName)).append("</div>\n");
            html.append("</div>\n</div>\n");
        }
        html.append("</div>");
    }

    private static boolean isTestDependency(String path) {
        if (path == null) return false;
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return normalized.contains("/test/") || normalized.endsWith("test.java");
    }

    private static void appendGroupedFindings(StringBuilder html, int line, List<Finding> findings, ChangedFile file) {
        Severity maxSev = findings.stream().map(f -> f.severity).min(Comparator.naturalOrder()).orElse(Severity.CONSIDER);
        String code = findings.get(0).code;
        boolean isChangedLine = file.changedLines.contains(line);
        String scopeClass = isChangedLine ? "" : " unchanged";
        String scopeLabel = isChangedLine ? "Changed" : "Context";
        String badgeClass = isChangedLine ? "scope-changed" : "scope-context";

        html.append("<div class='finding").append(scopeClass).append("' onclick='this.classList.toggle(\"expanded\")'>\n");
        html.append("<div class='finding-header'>\n");
        html.append("<span class='scope-badge ").append(badgeClass).append("'>").append(scopeLabel).append("</span>\n");
        html.append("<span class='severity severity-").append(maxSev).append("'>").append(maxSev).append("</span>\n");
        html.append("<span style='font-weight:600; color:#0f172a;'>Line ").append(line).append("</span>\n");
        html.append("<span style='color:#64748b; font-size:13px; font-family:monospace; margin-left:8px;'>").append(escapeHtml(truncate(code, 60))).append("</span>\n");
        html.append("<span class='pill'>").append(findings.size()).append(findings.size() > 1 ? " Issues" : " Issue").append("</span>\n");
        html.append("<span class='expand-icon'>&#9662;</span>\n");
        html.append("</div>\n");
        
        html.append("<div class='finding-body'>\n");
        html.append("<div class='code-block'>").append(escapeHtml(code)).append("</div>\n");
        
        // Deduplicate findings to avoid repetitive lists
        List<Finding> uniqueFindings = findings.stream()
            .collect(Collectors.collectingAndThen(
                Collectors.toCollection(() -> new TreeSet<>(Comparator.comparing((Finding f) -> f.message + f.suggestedFix))),
                ArrayList::new
            ));

        for (Finding find : uniqueFindings) {
            html.append("<div class='issue-group'>\n");
            
            html.append("<div style='display:flex; align-items:center; gap:8px; margin-bottom:8px;'>");
            html.append("<span class='category-badge'>").append(find.category.displayName).append("</span>");
            html.append("<span style='font-weight:600; font-size:14px; color:#1e293b;'>").append(escapeHtml(find.message)).append("</span>");
            html.append("</div>\n");
            
            html.append("<div class='details' style='margin-bottom:12px; color:#475569;'>");
            html.append(escapeHtml(find.explanation));
            html.append("</div>\n");

            html.append("<div class='fix-section'><strong>Suggested Fix:</strong>\n");
            html.append("<div class='fix-code'>").append(escapeHtml(find.suggestedFix)).append("</div>");
            html.append("</div>\n");
            
            html.append("</div>\n");
        }
        
        html.append("</div>\n</div>\n");
    }

    private static String truncate(String s, int len) {
        if (s == null || s.length() <= len) return s;
        return s.substring(0, len) + "...";
    }

    private static void appendScripts(StringBuilder html) {
        html.append("<script>\n");
        html.append("function showFile(index) {\n");
        html.append("  document.querySelectorAll('.file-item').forEach(i => i.classList.remove('active'));\n");
        html.append("  document.querySelectorAll('.file-content').forEach(c => c.classList.remove('active'));\n");
        html.append("  document.querySelectorAll('.file-item')[index].classList.add('active');\n");
        html.append("  document.getElementById('file-'+index).classList.add('active');\n");
        html.append("}\n");
        html.append("function toggleCard(id) {\n");
        html.append("  const card = document.getElementById(id);\n");
        html.append("  if (!card) return;\n");
        html.append("  card.classList.toggle('expanded');\n");
        html.append("}\n");
        html.append("const THEME_KEY = 'review-theme';\n");
        html.append("const root = document.documentElement;\n");
        html.append("function applyTheme(theme) {\n");
        html.append("  const target = theme === 'dark' ? 'dark' : 'light';\n");
        html.append("  root.setAttribute('data-theme', target);\n");
        html.append("  localStorage.setItem(THEME_KEY, target);\n");
        html.append("  const toggle = document.getElementById('theme-toggle');\n");
        html.append("  if (toggle) {\n");
        html.append("    toggle.setAttribute('data-theme', target);\n");
        html.append("    toggle.setAttribute('aria-pressed', target === 'dark');\n");
        html.append("  }\n");
        html.append("}\n");
        html.append("function toggleTheme() {\n");
        html.append("  const next = root.getAttribute('data-theme') === 'dark' ? 'light' : 'dark';\n");
        html.append("  applyTheme(next);\n");
        html.append("}\n");
        html.append("(function initTheme(){\n");
        html.append("  const saved = localStorage.getItem(THEME_KEY) || 'light';\n");
        html.append("  applyTheme(saved);\n");
        html.append("})();\n");
        html.append("</script>\n");
    }

    private static String saveReport(String content, Config config) {
        try {
            Path path = Path.of(System.getProperty("java.io.tmpdir"), "code-review-report.html");
            Files.writeString(path, content);
            System.out.println(ColorConsole.CYAN + "  Report: " + ColorConsole.WHITE + ColorConsole.BOLD + path + ColorConsole.RESET);
            return path.toAbsolutePath().toString();
        } catch (IOException e) {
            System.err.println("Failed to write report: " + e.getMessage());
            return null;
        }
    }

    private static String escapeHtml(String t) {
        if (t == null) return "";
        return t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
