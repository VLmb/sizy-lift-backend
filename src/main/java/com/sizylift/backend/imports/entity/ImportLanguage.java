package com.sizylift.backend.imports.entity;

/** Язык интерфейса сессии импорта, отдельно от языка пользовательского исходника. */
public enum ImportLanguage {
    RUSSIAN("ru", "Импортированная программа"), ENGLISH("en", "Imported program");

    private final String code;
    private final String defaultName;
    ImportLanguage(String code, String defaultName) { this.code = code; this.defaultName = defaultName; }
    public String defaultName() { return defaultName; }
    public String text(String russian, String english) { return this == ENGLISH ? english : russian; }
    public String code() { return code; }
    public static ImportLanguage of(String code) {
        return ENGLISH.code.equals(code) ? ENGLISH : RUSSIAN;
    }
}
