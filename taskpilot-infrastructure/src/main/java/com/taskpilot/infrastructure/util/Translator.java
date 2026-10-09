package com.taskpilot.infrastructure.util;

import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

@Component
public class Translator {

    private static MessageSource messageSource;

    public Translator(MessageSource messageSource) {
        Translator.messageSource = messageSource;
    }

    public static String toLocale(String msgCode, Object... args) {
        if (messageSource == null) {
            return msgCode;
        }
        Locale locale = LocaleContextHolder.getLocale();
        return messageSource.getMessage(msgCode, args, msgCode, locale);
    }

    public static String toLocaleWithDefault(String msgCode, String defaultMessage, Object... args) {
        if (messageSource == null) {
            return defaultMessage != null ? defaultMessage : msgCode;
        }
        Locale locale = LocaleContextHolder.getLocale();
        return messageSource.getMessage(msgCode, args, defaultMessage, locale);
    }
}
