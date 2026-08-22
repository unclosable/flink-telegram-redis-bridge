package io.github.flinktelegrambridge.service;

import io.github.flinktelegrambridge.protocol.HarnessEnvelope;
import io.github.flinktelegrambridge.protocol.HarnessParseResult;
import io.github.flinktelegrambridge.protocol.QuestionCallbackBinding;
import io.github.flinktelegrambridge.protocol.QuestionCallbackOption;
import io.github.flinktelegrambridge.protocol.RoutingDecision;
import io.github.flinktelegrambridge.protocol.TelegramPayloads;
import io.github.flinktelegrambridge.telegram.MarkdownV2Renderer;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.Set;

/**
 * Routes a parsed outbound envelope to a Telegram delivery decision.
 *
 * <ul>
 *   <li>{@code assistant_message} &rarr; forward {@code content.text} to Telegram.</li>
 *   <li>{@code error} &rarr; forward a short error text when a chat id is resolvable.</li>
 *   <li>{@code question_request} &rarr; render each question with opaque callback buttons.</li>
 *   <li>malformed / unsupported / unknown &rarr; drop safely (never throw, never retry forever).</li>
 * </ul>
 *
 * <p>Pure logic: no Redis, no Telegram, no Flink. The chat id is recovered from
 * {@code metadata.chatId}, falling back to the trailing {@code :chat:<chatId>}
 * marker in {@code conversation_id}.
 */
public final class HarnessOutboundRouter {

    public static final String CONVERSATION_CHAT_MARKER = ":chat:";
    private static final int MAX_ERROR_TEXT_LENGTH = 500;
    private final Set<String> allowedBotIds;
    private final boolean groupMessageEnabled;

    public HarnessOutboundRouter() {
        this(Set.of(), false);
    }

    public HarnessOutboundRouter(Set<String> allowedBotIds, boolean groupMessageEnabled) {
        this.allowedBotIds = Set.copyOf(allowedBotIds);
        this.groupMessageEnabled = groupMessageEnabled;
    }

    public RoutingDecision route(HarnessParseResult result) {
        if (result.isMalformed()) {
            return RoutingDecision.drop("malformed envelope dropped: " + result.reason());
        }
        if (result.isUnsupportedType()) {
            return RoutingDecision.drop("unsupported envelope type dropped: " + result.type());
        }
        HarnessEnvelope envelope = result.envelope();
        switch (envelope.type()) {
            case HarnessEnvelope.TYPE_ASSISTANT_MESSAGE:
                return routeAssistantMessage(envelope);
            case HarnessEnvelope.TYPE_ERROR:
                return routeError(envelope);
            case HarnessEnvelope.TYPE_GROUP_MESSAGE:
                return routeGroupMessage(envelope);
            case HarnessEnvelope.TYPE_QUESTION_REQUEST:
                return routeQuestionRequest(envelope);
            default:
                return RoutingDecision.drop("unsupported outbound type dropped: " + envelope.type());
        }
    }

    private RoutingDecision routeGroupMessage(HarnessEnvelope envelope) {
        if (!groupMessageEnabled) return RoutingDecision.drop("group_message dropped: feature disabled");
        String text = textOf(envelope.content());
        if (text == null || text.isBlank()) return RoutingDecision.drop("group_message dropped: blank text");
        String targetChatId = metadataString(envelope, "target_chat_id");
        if (targetChatId == null) return RoutingDecision.drop("group_message dropped: missing target_chat_id");
        String botId = metadataString(envelope, "bot_id");
        if (botId == null) return RoutingDecision.drop("group_message dropped: missing bot_id");
        if (!allowedBotIds.contains(botId)) return RoutingDecision.drop("group_message dropped: unknown bot_id " + botId);
        boolean markdown = envelope.contentType() != null && "text/markdown".equalsIgnoreCase(envelope.contentType());
        String rendered = markdown ? MarkdownV2Renderer.render(text) : text;
        return RoutingDecision.forward(new TelegramPayloads.TelegramOutboundMessage(
                targetChatId, rendered, markdown ? "MarkdownV2" : "", false, botId));
    }

    private RoutingDecision routeAssistantMessage(HarnessEnvelope envelope) {
        String chatId = resolveChatId(envelope);
        if (chatId == null) {
            return RoutingDecision.drop("assistant_message dropped: no resolvable chatId");
        }
        String text = textOf(envelope.content());
        if (text == null || text.isBlank()) {
            return RoutingDecision.drop("assistant_message dropped: blank text");
        }
        return forwardText(chatId, text, envelope);
    }

    private RoutingDecision routeError(HarnessEnvelope envelope) {
        String chatId = resolveChatId(envelope);
        String detail = textOf(envelope.content());
        if (detail == null || detail.isBlank()) {
            detail = "An unexpected error occurred.";
        }
        if (detail.length() > MAX_ERROR_TEXT_LENGTH) {
            detail = detail.substring(0, MAX_ERROR_TEXT_LENGTH) + "...";
        }
        if (chatId == null) {
            return RoutingDecision.drop("error dropped (no resolvable chatId): " + detail);
        }
        return forwardText(chatId, "Error: " + detail, envelope);
    }

    private RoutingDecision routeQuestionRequest(HarnessEnvelope envelope) {
        String chatId = resolveChatId(envelope);
        if (chatId == null) {
            return RoutingDecision.drop("question_request dropped: no resolvable chatId");
        }
        if (envelope.correlationId() == null || envelope.correlationId().isBlank()) {
            return RoutingDecision.drop("question_request dropped: missing correlation_id");
        }
        JsonNode rawQuestions = envelope.content() == null ? null : envelope.content().get("questions");
        if (rawQuestions == null || !rawQuestions.isArray() || rawQuestions.isEmpty()) {
            return RoutingDecision.drop("question_request dropped: missing questions");
        }

        List<TelegramPayloads.TelegramOutboundMessage> messages = new ArrayList<>();
        List<QuestionCallbackBinding> bindings = new ArrayList<>();
        String botId = resolveBotId(envelope);
        for (JsonNode rawQuestion : rawQuestions) {
            if (!rawQuestion.isObject()) {
                return RoutingDecision.drop("question_request dropped: invalid question shape");
            }
            String questionId = nonBlankString(rawQuestion.get("id"));
            String text = nonBlankString(rawQuestion.get("text"));
            if (questionId == null || text == null) {
                return RoutingDecision.drop("question_request dropped: question id/text missing");
            }
            boolean multiSelect = rawQuestion.path("multi_select").asBoolean(false);
            List<QuestionCallbackOption> callbackOptions = new ArrayList<>();
            JsonNode rawOptions = rawQuestion.get("options");
            if (rawOptions != null && rawOptions.isArray()) {
                for (JsonNode rawOption : rawOptions) {
                    String option = nonBlankString(rawOption);
                    if (option == null) continue;
                    String callbackKey = UUID.randomUUID().toString();
                    callbackOptions.add(new QuestionCallbackOption(callbackKey, option));
                }
            }
            String completionKey = callbackOptions.isEmpty() ? null : UUID.randomUUID().toString();
            List<List<TelegramPayloads.TelegramInlineKeyboardButton>> keyboard = new ArrayList<>();
            String confirmKey = multiSelect && !callbackOptions.isEmpty() ? UUID.randomUUID().toString() : null;
            for (QuestionCallbackOption option : callbackOptions) {
                keyboard.add(List.of(new TelegramPayloads.TelegramInlineKeyboardButton(option.value(), "hq:" + option.callbackKey())));
                bindings.add(new QuestionCallbackBinding(
                        option.callbackKey(), envelope.correlationId(), envelope.conversationId(), questionId,
                        option.value(), chatId, botId, multiSelect, callbackOptions, confirmKey, false, false, text, completionKey));
            }
            if (confirmKey != null) {
                keyboard.add(List.of(new TelegramPayloads.TelegramInlineKeyboardButton("Confirm", "hq:" + confirmKey)));
                bindings.add(new QuestionCallbackBinding(
                        confirmKey, envelope.correlationId(), envelope.conversationId(), questionId,
                        "", chatId, botId, true, callbackOptions, confirmKey, true, false, text, completionKey));
            }
            QuestionCallbackBinding freeTextBinding = null;
            if (callbackOptions.isEmpty()) {
                String freeTextKey = UUID.randomUUID().toString();
                freeTextBinding = new QuestionCallbackBinding(
                        freeTextKey, envelope.correlationId(), envelope.conversationId(), questionId,
                        "", chatId, botId, false, List.of(), null, false, true, text);
                bindings.add(freeTextBinding);
            }
            messages.add(new TelegramPayloads.TelegramOutboundMessage(
                    chatId, text, "", false, botId, keyboard, freeTextBinding != null, freeTextBinding));
        }
        return RoutingDecision.forward(messages, bindings);
    }

    /**
     * Recovers the Telegram chat id from {@code metadata.chatId}, falling back to
     * parsing the trailing {@code :chat:<chatId>} marker in {@code conversation_id}.
     */
    public static String resolveChatId(HarnessEnvelope envelope) {
        if (envelope == null) {
            return null;
        }
        Object metadataChatId = envelope.metadata() == null ? null : envelope.metadata().get("chatId");
        if (metadataChatId != null && !metadataChatId.toString().isBlank()) {
            return metadataChatId.toString().trim();
        }
        String conversationId = envelope.conversationId();
        if (conversationId == null) {
            return null;
        }
        int markerIndex = conversationId.lastIndexOf(CONVERSATION_CHAT_MARKER);
        if (markerIndex < 0) {
            return null;
        }
        String chatId = conversationId.substring(markerIndex + CONVERSATION_CHAT_MARKER.length());
        return chatId.isBlank() ? null : chatId;
    }

    private static String resolveBotId(HarnessEnvelope envelope) {
        if (envelope == null || envelope.metadata() == null) {
            return null;
        }
        Object metadataBotId = envelope.metadata().get("botId");
        if (metadataBotId == null) {
            return null;
        }
        String botId = metadataBotId.toString().trim();
        return botId.isBlank() ? null : botId;
    }

    private static String metadataString(HarnessEnvelope envelope, String key) {
        if (envelope == null || envelope.metadata() == null) return null;
        Object value = envelope.metadata().get(key);
        if (value == null) return null;
        String text = value.toString().trim();
        return text.isBlank() ? null : text;
    }

    private static String textOf(JsonNode content) {
        if (content == null || content.isNull()) {
            return null;
        }
        if (content.isTextual()) return content.asText();
        JsonNode text = content.get(HarnessEnvelope.CONTENT_TEXT);
        return text == null || text.isNull() ? null : text.asText();
    }

    private static RoutingDecision forwardText(String chatId, String text, HarnessEnvelope envelope) {
        boolean markdown = envelope.contentType() != null && "text/markdown".equalsIgnoreCase(envelope.contentType());
        String rendered = markdown ? MarkdownV2Renderer.render(text) : text;
        return RoutingDecision.forward(new TelegramPayloads.TelegramOutboundMessage(
                chatId, rendered, markdown ? "MarkdownV2" : "", false, resolveBotId(envelope)));
    }

    private static String nonBlankString(JsonNode value) {
        if (value == null) return null;
        String text = value.asText().trim();
        return text.isBlank() ? null : text;
    }
}
