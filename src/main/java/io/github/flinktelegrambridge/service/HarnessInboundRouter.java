package io.github.flinktelegrambridge.service;

import io.github.flinktelegrambridge.protocol.HarnessEnvelope;
import io.github.flinktelegrambridge.protocol.QuestionCallbackBinding;
import io.github.flinktelegrambridge.protocol.TelegramPayloads;
import io.github.flinktelegrambridge.registry.QuestionCallbackRegistry;

import java.util.Optional;
import java.util.List;

/** Converts ordinary Telegram messages and registered question callbacks to inbound envelopes. */
public final class HarnessInboundRouter {

    public static final String CALLBACK_PREFIX = "hq:";
    public static final String PRIVATE_CHAT_TYPE = "private";
    private final QuestionCallbackRegistry callbackRegistry;
    private final boolean privateOnly;

    public HarnessInboundRouter(QuestionCallbackRegistry callbackRegistry) {
        this(callbackRegistry, true);
    }

    public HarnessInboundRouter(QuestionCallbackRegistry callbackRegistry, boolean privateOnly) {
        this.callbackRegistry = callbackRegistry;
        this.privateOnly = privateOnly;
    }

    public Optional<HarnessEnvelope> route(TelegramPayloads.TelegramInboundMessage inbound) {
        if (!isPrivateChat(inbound)) return Optional.empty();
        if ("callback_query".equals(inbound.updateType())) return handleCallback(inbound).envelope();
        if (inbound.chatId() == null || inbound.chatId().isBlank() || inbound.text() == null || inbound.text().isBlank()) {
            return Optional.empty();
        }
        if ("/renew".equals(inbound.text())) {
            return Optional.of(HarnessEnvelope.fromSystemCommand(inbound, "renew_session"));
        }
        if (inbound.replyToMessageId() != null) {
            try {
                QuestionCallbackBinding freeText = callbackRegistry.consumeFreeText(
                        inbound.chatId(), inbound.botId(), inbound.replyToMessageId());
                if (freeText != null) return Optional.of(freeTextAnswer(inbound, freeText));
                if (callbackRegistry.isFreeTextPrompt(inbound.chatId(), inbound.botId(), inbound.replyToMessageId())) {
                    return Optional.empty(); // expired/already-consumed interaction reply: never leak as chat text
                }
            } catch (RuntimeException ignored) {
                return Optional.empty(); // interaction-shaped reply is unsafe to fall through when lookup failed
            }
        }
        return Optional.of(HarnessEnvelope.fromInbound(inbound));
    }

    public QuestionCallbackResult handleCallback(TelegramPayloads.TelegramInboundMessage inbound) {
        if (!isPrivateChat(inbound)) return QuestionCallbackResult.ignored("This action is no longer available.");
        if (inbound.callbackQueryId() == null || inbound.callbackQueryId().isBlank()
                || inbound.callbackData() == null || !inbound.callbackData().startsWith(CALLBACK_PREFIX)) {
            return QuestionCallbackResult.ignored("This action is no longer available.");
        }
        String callbackKey = inbound.callbackData().substring(CALLBACK_PREFIX.length());
        if (callbackKey.isBlank()) return QuestionCallbackResult.ignored("This action is no longer available.");
        QuestionCallbackBinding binding;
        try {
            binding = callbackRegistry.find(callbackKey);
        } catch (RuntimeException ignored) {
            return QuestionCallbackResult.ignored("Unable to process this action. Please try again.");
        }
        if (binding == null || inbound.chatId() == null || !inbound.chatId().equals(binding.chatId())) {
            return QuestionCallbackResult.ignored("This question has expired.");
        }
        if (binding.botId() != null && !binding.botId().isBlank() && !binding.botId().equals(inbound.botId())) {
            return QuestionCallbackResult.ignored("This question belongs to another bot.");
        }
        try {
            if (callbackRegistry.isCompleted(binding)) {
                return QuestionCallbackResult.ignored("This question was already answered.");
            }
        } catch (RuntimeException ignored) {
            return QuestionCallbackResult.ignored("Unable to process this action. Please try again.");
        }
        if (binding.multiSelect() && !binding.confirm()) {
            try {
                List<String> selected = callbackRegistry.toggle(binding);
                return new QuestionCallbackResult(true, "Selection updated", binding, selected, Optional.empty());
            } catch (RuntimeException ignored) {
                return QuestionCallbackResult.ignored("Unable to update selection. Please try again.");
            }
        }
        QuestionCallbackBinding consumed;
        try {
            consumed = callbackRegistry.consumeAndComplete(binding);
        } catch (RuntimeException ignored) {
            return QuestionCallbackResult.ignored("Unable to process this action. Please try again.");
        }
        if (consumed == null) return QuestionCallbackResult.ignored("This action was already handled.");
        List<String> selected;
        try {
            selected = consumed.multiSelect() ? callbackRegistry.confirm(consumed) : List.of(consumed.value());
        } catch (RuntimeException ignored) {
            return QuestionCallbackResult.ignored("Unable to finish this action. Please try again.");
        }
        return new QuestionCallbackResult(
                true,
                "✓",
                consumed,
                selected,
                Optional.of(questionAnswer(inbound, consumed, selected)));
    }

    private boolean isPrivateChat(TelegramPayloads.TelegramInboundMessage inbound) {
        return !privateOnly || PRIVATE_CHAT_TYPE.equals(inbound.chatType());
    }

    private static HarnessEnvelope questionAnswer(
            TelegramPayloads.TelegramInboundMessage inbound,
            QuestionCallbackBinding binding,
            List<String> values) {
        if (!binding.multiSelect()) return HarnessEnvelope.fromQuestionAnswer(inbound, binding);
        java.util.Map<String, Object> answer = new java.util.LinkedHashMap<>();
        answer.put("id", binding.questionId());
        answer.put("value", values);
        java.util.Map<String, Object> content = new java.util.LinkedHashMap<>();
        content.put("answers", List.of(answer));
        return new HarnessEnvelope(
                HarnessEnvelope.SUPPORTED_VERSION, binding.conversationId(), null,
                "telegram-callback-" + inbound.callbackQueryId(), binding.correlationId(),
                HarnessEnvelope.TYPE_QUESTION_ANSWER, content,
                metadata(inbound));
    }

    private static HarnessEnvelope freeTextAnswer(
            TelegramPayloads.TelegramInboundMessage inbound, QuestionCallbackBinding binding) {
        java.util.Map<String, Object> answer = new java.util.LinkedHashMap<>();
        answer.put("id", binding.questionId());
        answer.put("value", inbound.text());
        java.util.Map<String, Object> content = new java.util.LinkedHashMap<>();
        content.put("answers", List.of(answer));
        return new HarnessEnvelope(
                HarnessEnvelope.SUPPORTED_VERSION, binding.conversationId(), null,
                HarnessEnvelope.buildMessageId(inbound.updateId(), inbound.messageId()), binding.correlationId(),
                HarnessEnvelope.TYPE_QUESTION_ANSWER, content, metadata(inbound));
    }

    private static java.util.Map<String, Object> metadata(TelegramPayloads.TelegramInboundMessage inbound) {
        // Reuse the normal inbound envelope's metadata shape while replacing only its content/type.
        return new java.util.LinkedHashMap<>(HarnessEnvelope.fromInbound(inbound).metadata());
    }
}
