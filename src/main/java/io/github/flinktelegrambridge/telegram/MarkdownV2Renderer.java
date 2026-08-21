package io.github.flinktelegrambridge.telegram;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative CommonMark subset renderer for Telegram's MarkdownV2 dialect. */
public final class MarkdownV2Renderer {
    private static final Pattern ENTITY = Pattern.compile(
            "(?s)```(.*?)```|\\[([^\\]\\n]+)]\\(([^)\\n]+)\\)|`([^`\\n]+)`|\\*\\*([^*\\n]+)\\*\\*|\\*([^*\\n]+)\\*");
    private static final String SPECIALS = "_*[]()~`>#+-=|{}.!";

    private MarkdownV2Renderer() {}

    public static String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) return markdown == null ? "" : markdown;
        String normalized = normalizeLines(markdown);
        StringBuilder rendered = new StringBuilder();
        Matcher matcher = ENTITY.matcher(normalized);
        int cursor = 0;
        while (matcher.find()) {
            rendered.append(escape(normalized.substring(cursor, matcher.start())));
            if (matcher.group(1) != null) {
                rendered.append("```").append(matcher.group(1)).append("```");
            } else if (matcher.group(2) != null) {
                rendered.append('[').append(escape(matcher.group(2))).append("](")
                        .append(escapeUrl(matcher.group(3))).append(')');
            } else if (matcher.group(4) != null) {
                rendered.append('`').append(matcher.group(4)).append('`');
            } else if (matcher.group(5) != null) {
                rendered.append('*').append(escape(matcher.group(5))).append('*');
            } else {
                rendered.append('_').append(escape(matcher.group(6))).append('_');
            }
            cursor = matcher.end();
        }
        return rendered.append(escape(normalized.substring(cursor))).toString();
    }

    private static String normalizeLines(String text) {
        String[] lines = text.split("\\n", -1);
        StringBuilder result = new StringBuilder();
        boolean fenced = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!fenced && line.matches("^#{1,3}\\s+.*")) line = "**" + line.replaceFirst("^#{1,3}\\s+", "") + "**";
            else if (!fenced && line.matches("^[-*]\\s+.*")) line = "• " + line.replaceFirst("^[-*]\\s+", "");
            if (i > 0) result.append('\n');
            result.append(line);
            if (count(line, "```") % 2 != 0) fenced = !fenced;
        }
        return result.toString();
    }

    private static int count(String text, String token) {
        int count = 0;
        for (int index = text.indexOf(token); index >= 0; index = text.indexOf(token, index + token.length())) count++;
        return count;
    }

    private static String escape(String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (SPECIALS.indexOf(character) >= 0) result.append('\\');
            result.append(character);
        }
        return result.toString();
    }

    private static String escapeUrl(String url) {
        return url.replace("\\", "\\\\").replace(")", "\\)");
    }
}
