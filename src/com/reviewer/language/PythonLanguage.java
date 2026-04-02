package com.reviewer.language;

import com.reviewer.model.Models.*;
import java.util.*;
import java.util.regex.*;

public class PythonLanguage implements Language {

    private static final Pattern P_BARE_EXCEPT    = Pattern.compile("except\\s*:", Pattern.MULTILINE);
    private static final Pattern P_NONE_EQ        = Pattern.compile("\\b(\\w+)\\s*(==|!=)\\s*None\\b");
    private static final Pattern P_WILDCARD_IMPORT= Pattern.compile("^from\\s+\\S+\\s+import\\s+\\*", Pattern.MULTILINE);
    private static final Pattern P_PRINT_STMT     = Pattern.compile("\\bprint\\s*\\(");
    private static final Pattern P_MUTABLE_DEFAULT= Pattern.compile("def\\s+\\w+\\s*\\([^)]*=\\s*(\\[|\\{)");
    private static final Pattern P_ASSERT_IN_PROD = Pattern.compile("^\\s*assert\\s+", Pattern.MULTILINE);

    @Override
    public String getName() {
        return "python";
    }

    @Override
    public List<String> getSupportedExtensions() {
        return List.of(".py");
    }

    @Override
    public void analyzeFile(String content, String[] lines, ChangedFile file,
                            List<Finding> findings, Config config) {
        checkBareExcept(content, lines, file, findings);
        checkNoneComparison(content, lines, file, findings);
        checkWildcardImports(content, lines, file, findings);
        checkPrintStatements(content, lines, file, findings);
        checkMutableDefaultArgs(content, lines, file, findings);
        checkAssertInProd(content, lines, file, findings);
    }

    private void checkBareExcept(String content, String[] lines, ChangedFile file, List<Finding> findings) {
        Matcher m = P_BARE_EXCEPT.matcher(content);
        while (m.find()) {
            int line = lineOf(content, m.start());
            if (!inChangedLines(file, line)) continue;
            findings.add(new Finding(
                Severity.MUST_FIX, Category.EXCEPTION_HANDLING,
                file.name, line, lines[line - 1].trim(),
                "Bare except clause catches everything, including SystemExit and KeyboardInterrupt.",
                "A bare 'except:' makes debugging very hard and can hide real errors. "
                + "Always catch a specific exception type.",
                "except ValueError as e:\n    # handle it\nexcept Exception as e:\n    # fallback"
            ));
        }
    }

    private void checkNoneComparison(String content, String[] lines, ChangedFile file, List<Finding> findings) {
        Matcher m = P_NONE_EQ.matcher(content);
        while (m.find()) {
            int line = lineOf(content, m.start());
            if (!inChangedLines(file, line)) continue;
            String op  = m.group(2);
            String var = m.group(1);
            String fix = "==".equals(op) ? var + " is None" : var + " is not None";
            findings.add(new Finding(
                Severity.SHOULD_FIX, Category.CODE_QUALITY,
                file.name, line, lines[line - 1].trim(),
                "None comparison using '" + op + "' instead of identity check.",
                "PEP 8 mandates 'is' / 'is not' for None comparisons. "
                + "Using == can be misleading and may invoke __eq__.",
                fix
            ));
        }
    }

    private void checkWildcardImports(String content, String[] lines, ChangedFile file, List<Finding> findings) {
        Matcher m = P_WILDCARD_IMPORT.matcher(content);
        while (m.find()) {
            int line = lineOf(content, m.start());
            if (!inChangedLines(file, line)) continue;
            findings.add(new Finding(
                Severity.SHOULD_FIX, Category.CODE_QUALITY,
                file.name, line, lines[line - 1].trim(),
                "Wildcard import pollutes the namespace.",
                "Wildcard imports make it unclear which names are in scope and can shadow "
                + "built-ins or other imported names. Use explicit imports instead.",
                "from module import SpecificClass, specific_function"
            ));
        }
    }

    private void checkPrintStatements(String content, String[] lines, ChangedFile file, List<Finding> findings) {
        Matcher m = P_PRINT_STMT.matcher(content);
        while (m.find()) {
            int line = lineOf(content, m.start());
            if (!inChangedLines(file, line)) continue;
            String code = lines[line - 1].trim();
            if (code.startsWith("#")) continue;
            findings.add(new Finding(
                Severity.CONSIDER, Category.LOGGING,
                file.name, line, code,
                "print() statement detected in production code.",
                "Use the logging module for production output. "
                + "print() cannot be filtered by log level and goes directly to stdout.",
                "import logging\nlogging.getLogger(__name__).info('message')"
            ));
        }
    }

    private void checkMutableDefaultArgs(String content, String[] lines, ChangedFile file, List<Finding> findings) {
        Matcher m = P_MUTABLE_DEFAULT.matcher(content);
        while (m.find()) {
            int line = lineOf(content, m.start());
            if (!inChangedLines(file, line)) continue;
            findings.add(new Finding(
                Severity.MUST_FIX, Category.CODE_QUALITY,
                file.name, line, lines[line - 1].trim(),
                "Mutable default argument (list or dict) detected.",
                "Default argument values are evaluated once at function definition time. "
                + "Using a mutable object ([] or {}) as a default causes it to be shared "
                + "across all calls, leading to hard-to-trace bugs.",
                "def my_func(items=None):\n    if items is None:\n        items = []"
            ));
        }
    }

    private void checkAssertInProd(String content, String[] lines, ChangedFile file, List<Finding> findings) {
        Matcher m = P_ASSERT_IN_PROD.matcher(content);
        while (m.find()) {
            int line = lineOf(content, m.start());
            if (!inChangedLines(file, line)) continue;
            String code = lines[line - 1].trim();
            if (code.startsWith("#")) continue;
            findings.add(new Finding(
                Severity.CONSIDER, Category.CODE_QUALITY,
                file.name, line, code,
                "assert statement in production code.",
                "assert statements are stripped out when Python runs with the -O (optimise) flag. "
                + "Use explicit if/raise for production guards.",
                "if not condition:\n    raise ValueError('condition failed')"
            ));
        }
    }

    private static int lineOf(String content, int index) {
        return (int) content.substring(0, Math.min(index, content.length()))
                            .chars().filter(c -> c == '\n').count() + 1;
    }

    private static boolean inChangedLines(ChangedFile file, int line) {
        for (int i = line - 1; i <= line + 1; i++) {
            if (file.changedLines.contains(i)) return true;
        }
        return false;
    }
}
