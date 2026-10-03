package com.August.chunkcleaner.util;

import com.August.chunkcleaner.config.Config;

import java.util.Locale;

public class LanguageUtil {

    public static boolean isRussian(Config config) {
        if (config == null) {
            return isSystemRussian();
        }
        String lang = config.getLanguage();
        if ("ru".equalsIgnoreCase(lang)) {
            return true;
        }
        if ("en".equalsIgnoreCase(lang)) {
            return false;
        }
        return isSystemRussian();
    }

    private static boolean isSystemRussian() {
        String sysLang = Locale.getDefault().getLanguage();
        return "ru".equalsIgnoreCase(sysLang) || "be".equalsIgnoreCase(sysLang) || "uk".equalsIgnoreCase(sysLang);
    }

    public static String msg(Config config, String ru, String en, Object... args) {
        String template = isRussian(config) ? ru : en;
        if (args != null && args.length > 0) {
            return String.format(template, args);
        }
        return template;
    }
}
