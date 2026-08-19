package io.github.flinktelegrambridge.protocol;

/**
 * Server-side data referenced by an opaque Telegram callback key. The callback
 * data deliberately carries only the key, keeping correlation and routing data
 * out of Telegram's 64-byte callback-data limit.
 */
public record QuestionCallbackBinding(
        String callbackKey,
        String correlationId,
        String conversationId,
        String questionId,
        String value,
        String chatId,
        String botId,
        boolean multiSelect,
        java.util.List<QuestionCallbackOption> options,
        String confirmCallbackKey,
        boolean confirm,
        boolean freeText,
        String questionText,
        String completionKey) {

    public QuestionCallbackBinding(
            String callbackKey, String correlationId, String conversationId, String questionId, String value,
            String chatId, String botId, boolean multiSelect) {
        this(callbackKey, correlationId, conversationId, questionId, value, chatId, botId, multiSelect,
                java.util.List.of(), null, false, false, null, null);
    }

    /** Source-compatible constructor for keyboard question bindings. */
    public QuestionCallbackBinding(
            String callbackKey, String correlationId, String conversationId, String questionId, String value,
            String chatId, String botId, boolean multiSelect, java.util.List<QuestionCallbackOption> options,
            String confirmCallbackKey, boolean confirm) {
        this(callbackKey, correlationId, conversationId, questionId, value, chatId, botId, multiSelect,
                options, confirmCallbackKey, confirm, false, null, null);
    }

    public QuestionCallbackBinding(
            String callbackKey, String correlationId, String conversationId, String questionId, String value,
            String chatId, String botId, boolean multiSelect, java.util.List<QuestionCallbackOption> options,
            String confirmCallbackKey, boolean confirm, boolean freeText) {
        this(callbackKey, correlationId, conversationId, questionId, value, chatId, botId, multiSelect,
                options, confirmCallbackKey, confirm, freeText, null, null);
    }

    /** Source-compatible constructor used before interaction-wide completion keys were added. */
    public QuestionCallbackBinding(
            String callbackKey, String correlationId, String conversationId, String questionId, String value,
            String chatId, String botId, boolean multiSelect, java.util.List<QuestionCallbackOption> options,
            String confirmCallbackKey, boolean confirm, boolean freeText, String questionText) {
        this(callbackKey, correlationId, conversationId, questionId, value, chatId, botId, multiSelect,
                options, confirmCallbackKey, confirm, freeText, questionText, null);
    }

    public QuestionCallbackBinding {
        options = options == null ? java.util.List.of() : java.util.List.copyOf(options);
    }
}
