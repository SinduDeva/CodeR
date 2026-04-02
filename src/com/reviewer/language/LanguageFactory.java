package com.reviewer.language;

import java.util.Map;

public class LanguageFactory {

    private static final Map<String, Language> LANGUAGES = Map.of(
        "java",   new JavaLanguage(),
        "python", new PythonLanguage()
    );

    public static Language getLanguage(String name) {
        Language lang = LANGUAGES.get(name == null ? "java" : name.trim().toLowerCase());
        return lang != null ? lang : new JavaLanguage();
    }

    public static boolean isSupported(String name) {
        return name != null && LANGUAGES.containsKey(name.trim().toLowerCase());
    }
}
