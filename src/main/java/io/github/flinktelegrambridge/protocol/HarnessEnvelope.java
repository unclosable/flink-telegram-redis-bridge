package io.github.flinktelegrambridge.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single JSON envelope exchanged over the Redis Streams harness bridge.
 *
 * <p>Envelope field names are snake_case and match {@code PROTOCOL.md} exactly.
 * The same record carries both the inbound {@code message_id} and the outbound
 * {@code correlation_id}; the unused one is omitted from JSON so each direction
 * serializes to the exact wire shape described by the protocol.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HarnessEnvelope(
        Integer version,
        @JsonProperty("conversation_id") String conversationId,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("message_id") @JsonInclude(JsonInclude.Include.NON_NULL) String messageId,
        @JsonProperty("correlation_id") @JsonInclude(JsonInclude.Include.NON_NULL) String correlationId,
        String type,
        @JsonProperty("content_type") @JsonInclude(JsonInclude.Include.NON_NULL) String contentType,
        @JsonProperty("content") JsonNode content,
        Map<String, Object> metadata) {

    public static final int SUPPORTED_VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String TYPE_MESSAGE = "message";
    public static final String TYPE_NEW_SESSION = "new_session";
    public static final String TYPE_STEER = "steer";
    public static final String TYPE_ASSISTANT_MESSAGE = "assistant_message";
    public static final String TYPE_ERROR = "error";
    /** Harness requests an interactive answer from the Telegram user. */
    public static final String TYPE_QUESTION_REQUEST = "question_request";
    /** Telegram callback answer to a prior {@link #TYPE_QUESTION_REQUEST}. */
    public static final String TYPE_QUESTION_ANSWER = "question_answer";

    public static final String CONTENT_TEXT = "text";

    public HarnessEnvelope {
        content = content == null ? JSON.createObjectNode() : content;
        metadata = metadata == null ? new LinkedHashMap<>() : metadata;
    }

    /** Compatibility constructor for the established object-shaped content form. */
    public HarnessEnvelope(
            Integer version, String conversationId, String sessionId, String messageId, String correlationId,
            String type, Map<String, Object> content, Map<String, Object> metadata) {
        this(version, conversationId, sessionId, messageId, correlationId, type, null,
                JSON.valueToTree(content == null ? new LinkedHashMap<>() : content), metadata);
    }

    /**
     * Builds the INBOUND envelope ({@code type = message}) from a normalized
     * Telegram inbound message.
     */
    public static HarnessEnvelope fromInbound(TelegramPayloads.TelegramInboundMessage inbound) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put(CONTENT_TEXT, inbound.text());

        Map<String, Object> metadata = metadataFromInbound(inbound);

        return new HarnessEnvelope(
                SUPPORTED_VERSION,
                buildConversationId(inbound.source(), inbound.botId(), inbound.chatId()),
                null,
                buildMessageId(inbound.updateId(), inbound.messageId()),
                null,
                TYPE_MESSAGE,
                null,
                JSON.valueToTree(content),
                metadata);
    }

    /** Builds an inbound answer envelope from a validated Telegram callback. */
    public static HarnessEnvelope fromQuestionAnswer(
            TelegramPayloads.TelegramInboundMessage inbound, QuestionCallbackBinding callback) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("id", callback.questionId());
        answer.put("value", callback.multiSelect() ? java.util.List.of(callback.value()) : callback.value());
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("answers", java.util.List.of(answer));

        return new HarnessEnvelope(
                SUPPORTED_VERSION,
                callback.conversationId(),
                null,
                "telegram-callback-" + inbound.callbackQueryId(),
                callback.correlationId(),
                TYPE_QUESTION_ANSWER,
                null,
                JSON.valueToTree(content),
                metadataFromInbound(inbound));
    }

    private static Map<String, Object> metadataFromInbound(TelegramPayloads.TelegramInboundMessage inbound) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("chatId", inbound.chatId());
        metadata.put("source", inbound.source());
        metadata.put("sourceType", inbound.updateType());
        metadata.put("updateId", inbound.updateId());
        metadata.put("messageId", inbound.messageId());
        metadata.put("fromUserId", inbound.fromUserId());
        metadata.put("fromUsername", inbound.fromUsername());
        metadata.put("fromFirstName", inbound.fromFirstName());
        metadata.put("messageDate", inbound.messageDate());
        metadata.put("chatType", inbound.chatType());
        metadata.put("chatTitle", inbound.chatTitle());
        if (inbound.botId() != null && !inbound.botId().isBlank()) {
            metadata.put("botId", inbound.botId().trim());
        }
        return metadata;
    }

    /**
     * Builds the conversation id {@code <source>:chat:<chatId>} (e.g.
     * {@code telegram:chat:12345}).
     */
    public static String buildConversationId(String source, String chatId) {
        return buildConversationId(source, null, chatId);
    }

    /**
     * Builds the conversation id as {@code <source>:chat:<chatId>} for legacy
     * single-bot messages and {@code <source>:<botId>:chat:<chatId>} when bot id
     * is present.
     */
    public static String buildConversationId(String source, String botId, String chatId) {
        String normalizedSource = source == null || source.isBlank() ? "telegram" : source;
        String normalizedBotId = botId == null ? "" : botId.trim();
        String normalizedChatId = chatId == null ? "" : chatId;
        if (normalizedBotId.isBlank()) {
            return normalizedSource + ":chat:" + normalizedChatId;
        }
        return normalizedSource + ":" + normalizedBotId + ":chat:" + normalizedChatId;
    }

    /**
     * Builds the message id {@code telegram-update-<updateId>}, falling back to
     * {@code telegram-message-<messageId>} when the update id is missing, and to
     * a generated id when both are missing.
     */
    public static String buildMessageId(Integer updateId, Integer messageId) {
        if (updateId != null) {
            return "telegram-update-" + updateId;
        }
        if (messageId != null) {
            return "telegram-message-" + messageId;
        }
        return "telegram-generated-" + java.util.UUID.randomUUID();
    }
}
