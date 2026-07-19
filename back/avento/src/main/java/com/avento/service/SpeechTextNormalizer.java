package com.avento.service;

import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** Converts rich chat output into text that sounds natural when spoken. */
@Service
public class SpeechTextNormalizer {

    private static final Pattern CODE_BLOCK = Pattern.compile("(?s)```.*?```");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^]]+)]\\((?:https?://|/)[^)]+\\)");
    private static final Pattern RAW_URL = Pattern.compile("https?://\\S+");
    private static final Pattern MARKDOWN_PREFIX = Pattern.compile("(?m)^\\s{0,3}(?:#{1,6}|>|[-+*]|\\d+[.)])\\s+");
    private static final Pattern MARKDOWN_MARKER = Pattern.compile("(?:\\*\\*|__|~~|(?<!\\w)[*_](?!\\w))");
    private static final Pattern METRICS_LINE = Pattern.compile("(?m)^\\s*[⏱📦].*$");
    private static final Pattern EMOJI =
            Pattern.compile("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = CODE_BLOCK.matcher(text).replaceAll(" ");
        normalized = MARKDOWN_LINK.matcher(normalized).replaceAll("$1");
        normalized = RAW_URL.matcher(normalized).replaceAll(" link ");
        normalized = INLINE_CODE.matcher(normalized).replaceAll("$1");
        normalized = METRICS_LINE.matcher(normalized).replaceAll(" ");
        normalized = MARKDOWN_PREFIX.matcher(normalized).replaceAll("");
        normalized = MARKDOWN_MARKER.matcher(normalized).replaceAll("");
        normalized = EMOJI.matcher(normalized).replaceAll(" ");
        normalized = normalized.replace("&", " e ");
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ");
        normalized = normalized.replaceAll("\\s+([,.;:!?])", "$1");
        normalized = normalized.replaceAll("([.!?]){2,}", "$1");
        return normalized.trim();
    }
}
