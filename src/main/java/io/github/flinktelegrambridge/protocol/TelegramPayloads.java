package io.github.flinktelegrambridge.protocol;

/**
 * Shared Telegram payload shapes for pipeline source and sink components.
 */
public final class TelegramPayloads {

    private TelegramPayloads() {}

    /**
     * Normalized inbound Telegram update payload emitted by the long-polling source.
     */
    public record TelegramInboundMessage(
            String source,
            String botId,
            String updateType,
            Integer updateId,
            Integer messageId,
            String chatId,
            String chatType,
            String chatTitle,
            Long fromUserId,
            String fromUsername,
            String fromFirstName,
            String text,
            Integer messageDate,
            String rawUpdateJson,
            String callbackQueryId,
            String callbackData,
            Integer replyToMessageId) {

        /** Source-compatible constructor used before callback fields were added. */
        public TelegramInboundMessage(
                String source, String botId, String updateType, Integer updateId, Integer messageId,
                String chatId, String chatType, String chatTitle, Long fromUserId, String fromUsername,
                String fromFirstName, String text, Integer messageDate, String rawUpdateJson) {
            this(source, botId, updateType, updateId, messageId, chatId, chatType, chatTitle, fromUserId,
                    fromUsername, fromFirstName, text, messageDate, rawUpdateJson, null, null, null);
        }

        /** Source-compatible constructor used before reply-to tracking was added. */
        public TelegramInboundMessage(
                String source, String botId, String updateType, Integer updateId, Integer messageId,
                String chatId, String chatType, String chatTitle, Long fromUserId, String fromUsername,
                String fromFirstName, String text, Integer messageDate, String rawUpdateJson,
                String callbackQueryId, String callbackData) {
            this(source, botId, updateType, updateId, messageId, chatId, chatType, chatTitle, fromUserId,
                    fromUsername, fromFirstName, text, messageDate, rawUpdateJson, callbackQueryId, callbackData, null);
        }
    }

    /**
     * Structured outbound Telegram message payload accepted by the sink.
     */
    public record TelegramOutboundMessage(
            String chatId,
            String text,
            String parseMode,
            Boolean disableNotification,
            String botId,
            java.util.List<java.util.List<TelegramInlineKeyboardButton>> inlineKeyboard,
            Boolean forceReply,
            QuestionCallbackBinding freeTextBinding) {

        public TelegramOutboundMessage(
                String chatId, String text, String parseMode, Boolean disableNotification, String botId) {
            this(chatId, text, parseMode, disableNotification, botId, java.util.List.of(), false, null);
        }

        public TelegramOutboundMessage(
                String chatId, String text, String parseMode, Boolean disableNotification, String botId,
                java.util.List<java.util.List<TelegramInlineKeyboardButton>> inlineKeyboard) {
            this(chatId, text, parseMode, disableNotification, botId, inlineKeyboard, false, null);
        }

        public TelegramOutboundMessage {
            inlineKeyboard = inlineKeyboard == null ? java.util.List.of() : inlineKeyboard;
        }
    }

    /** One Telegram inline-keyboard button, represented independently of the Bot API. */
    public record TelegramInlineKeyboardButton(String text, String callbackData) {}
}
