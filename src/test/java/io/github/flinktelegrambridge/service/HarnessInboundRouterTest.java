package io.github.flinktelegrambridge.service;

import io.github.flinktelegrambridge.protocol.QuestionCallbackBinding;
import io.github.flinktelegrambridge.protocol.QuestionCallbackOption;
import io.github.flinktelegrambridge.protocol.TelegramPayloads;
import io.github.flinktelegrambridge.registry.QuestionCallbackRegistry;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessInboundRouterTest {

    @Test
    void routesRegisteredCallbackAsQuestionAnswerAndConsumesIt() {
        FakeRegistry registry = new FakeRegistry();
        registry.register(new QuestionCallbackBinding(
                "callback-key", "opaque-correlation", "telegram:ops-bot:chat:12345", "deploy",
                "Rolling", "12345", "ops-bot", false));
        HarnessInboundRouter router = new HarnessInboundRouter(registry);

        var result = router.route(callback("callback-key", "12345", "ops-bot"));

        assertTrue(result.isPresent());
        assertEquals("question_answer", result.get().type());
        assertEquals("opaque-correlation", result.get().correlationId());
        assertEquals("telegram-callback-callback-id", result.get().messageId());
        assertEquals("deploy", result.get().content().get("answers").get(0).get("id").asText());
        assertEquals("Rolling", result.get().content().get("answers").get(0).get("value").asText());
        assertEquals("12345", result.get().metadata().get("chatId"));
        assertFalse(router.route(callback("callback-key", "12345", "ops-bot")).isPresent());
    }

    @Test
    void rejectsCallbackFromAnotherChat() {
        FakeRegistry registry = new FakeRegistry();
        registry.register(new QuestionCallbackBinding(
                "callback-key", "c", "telegram:chat:12345", "q", "yes", "12345", "", false));
        assertFalse(new HarnessInboundRouter(registry).route(callback("callback-key", "other-chat", "")).isPresent());
    }

    @Test
    void emitsArrayValueForMultiSelectQuestion() {
        FakeRegistry registry = new FakeRegistry();
        var options = java.util.List.of(new QuestionCallbackOption("metrics", "Metrics"), new QuestionCallbackOption("alerts", "Alerts"));
        registry.register(new QuestionCallbackBinding(
                "metrics", "c", "telegram:chat:12345", "features", "Metrics", "12345", "", true, options, "confirm", false));
        registry.register(new QuestionCallbackBinding(
                "alerts", "c", "telegram:chat:12345", "features", "Alerts", "12345", "", true, options, "confirm", false));
        registry.register(new QuestionCallbackBinding(
                "confirm", "c", "telegram:chat:12345", "features", "", "12345", "", true, options, "confirm", true));
        HarnessInboundRouter router = new HarnessInboundRouter(registry);

        assertFalse(router.route(callback("metrics", "12345", "")).isPresent(), "toggle must not emit an answer");
        assertFalse(router.route(callback("alerts", "12345", "")).isPresent(), "toggle must not emit an answer");
        var result = router.route(callback("confirm", "12345", ""));

        assertEquals(java.util.List.of("Metrics", "Alerts"), new com.fasterxml.jackson.databind.ObjectMapper()
                .convertValue(result.orElseThrow().content().get("answers").get(0).get("value"), java.util.List.class));
    }

    @Test
    void singleSelectSiblingCannotProduceSecondAnswerAfterCompletion() {
        FakeRegistry registry = new FakeRegistry();
        registry.register(new QuestionCallbackBinding("one", "c", "telegram:chat:12345", "choice", "One", "12345", "", false,
                java.util.List.of(), null, false, false, "Choose", "interaction"));
        registry.register(new QuestionCallbackBinding("two", "c", "telegram:chat:12345", "choice", "Two", "12345", "", false,
                java.util.List.of(), null, false, false, "Choose", "interaction"));
        HarnessInboundRouter router = new HarnessInboundRouter(registry);

        assertTrue(router.route(callback("one", "12345", "")).isPresent());
        assertFalse(router.route(callback("two", "12345", "")).isPresent());
    }

    @Test
    void multiSelectOptionAfterConfirmIsIgnoredWithoutUpdatingUiOrAnswering() {
        FakeRegistry registry = new FakeRegistry();
        var options = java.util.List.of(new QuestionCallbackOption("one", "One"), new QuestionCallbackOption("two", "Two"));
        registry.register(new QuestionCallbackBinding("one", "c", "telegram:chat:12345", "features", "One", "12345", "", true,
                options, "confirm", false, false, "Choose", "interaction"));
        registry.register(new QuestionCallbackBinding("two", "c", "telegram:chat:12345", "features", "Two", "12345", "", true,
                options, "confirm", false, false, "Choose", "interaction"));
        registry.register(new QuestionCallbackBinding("confirm", "c", "telegram:chat:12345", "features", "", "12345", "", true,
                options, "confirm", true, false, "Choose", "interaction"));
        HarnessInboundRouter router = new HarnessInboundRouter(registry);

        assertFalse(router.route(callback("one", "12345", "")).isPresent());
        assertTrue(router.route(callback("confirm", "12345", "")).isPresent());
        QuestionCallbackResult delayed = router.handleCallback(callback("two", "12345", ""));
        assertFalse(delayed.handled());
        assertTrue(delayed.envelope().isEmpty());
    }

    @Test
    void routesNextTextMessageAsAnswerForPendingFreeTextQuestion() {
        FakeRegistry registry = new FakeRegistry();
        registry.pendingFreeText = new QuestionCallbackBinding(
                "free", "c", "telegram:ops-bot:chat:12345", "reason", "", "12345", "ops-bot",
                false, java.util.List.of(), null, false, true);
        HarnessInboundRouter router = new HarnessInboundRouter(registry);

        TelegramPayloads.TelegramInboundMessage reply = new TelegramPayloads.TelegramInboundMessage(
                "telegram", "ops-bot", "message", 100, 12, "12345", "private", null,
                7L, "alice", "Alice", "Need a canary first", 1700000000, "{}", null, null, 77);
        var result = router.route(reply).orElseThrow();

        assertEquals("question_answer", result.type());
        assertEquals("c", result.correlationId());
        assertEquals("telegram-update-100", result.messageId());
        assertEquals("reason", result.content().get("answers").get(0).get("id").asText());
        assertEquals("Need a canary first", result.content().get("answers").get(0).get("value").asText());
        assertNull(registry.pendingFreeText);
    }

    @Test
    void expiredInteractionReplyAndRegistryFailureDoNotFallThrough() {
        QuestionCallbackRegistry failing = new QuestionCallbackRegistry() {
            @Override public void register(QuestionCallbackBinding binding) {}
            @Override public QuestionCallbackBinding find(String callbackKey) { throw new IllegalStateException("redis unavailable"); }
            @Override public QuestionCallbackBinding consume(String callbackKey) { throw new IllegalStateException("redis unavailable"); }
            @Override public QuestionCallbackBinding consumeFreeText(String chatId, String botId, Integer replyToMessageId) { throw new IllegalStateException("redis unavailable"); }
            @Override public java.util.List<String> toggle(QuestionCallbackBinding binding) { throw new IllegalStateException("redis unavailable"); }
            @Override public java.util.List<String> confirm(QuestionCallbackBinding binding) { throw new IllegalStateException("redis unavailable"); }
        };
        HarnessInboundRouter router = new HarnessInboundRouter(failing);

        assertFalse(router.route(callback("expired", "12345", "")).isPresent());
        TelegramPayloads.TelegramInboundMessage message = new TelegramPayloads.TelegramInboundMessage(
                "telegram", "", "message", 1, 2, "12345", "private", null,
                7L, null, null, "still deliver this", 1, "{}", null, null, 88);
        assertFalse(router.route(message).isPresent());
        TelegramPayloads.TelegramInboundMessage normal = new TelegramPayloads.TelegramInboundMessage(
                "telegram", "", "message", 2, 3, "12345", "private", null,
                7L, null, null, "ordinary message", 1, "{}");
        assertEquals("message", router.route(normal).orElseThrow().type());
    }

    @Test
    void replyToNonPendingMessageRemainsNormalButExpiredPromptIsSuppressed() {
        FakeRegistry registry = new FakeRegistry();
        HarnessInboundRouter router = new HarnessInboundRouter(registry);
        TelegramPayloads.TelegramInboundMessage nonPending = new TelegramPayloads.TelegramInboundMessage(
                "telegram", "", "message", 1, 2, "12345", "private", null,
                7L, null, null, "ordinary reply", 1, "{}", null, null, 70);
        assertEquals("message", router.route(nonPending).orElseThrow().type());
        registry.expiredPrompt = true;
        TelegramPayloads.TelegramInboundMessage expired = new TelegramPayloads.TelegramInboundMessage(
                "telegram", "", "message", 2, 3, "12345", "private", null,
                7L, null, null, "late answer", 1, "{}", null, null, 71);
        assertFalse(router.route(expired).isPresent());
    }

    @Test
    void privateOnlyDropsNonPrivateMessages() {
        HarnessInboundRouter router = new HarnessInboundRouter(new FakeRegistry());
        for (String chatType : java.util.List.of("group", "supergroup", "channel", "", "unknown")) {
            assertTrue(router.route(message(chatType)).isEmpty(), chatType);
        }
        assertTrue(router.route(message(null)).isEmpty());
    }

    @Test
    void privateOnlyDropsGroupCallbacks() {
        FakeRegistry registry = new FakeRegistry();
        HarnessInboundRouter router = new HarnessInboundRouter(registry);
        for (String chatType : java.util.List.of("group", "supergroup")) {
            registry.register(new QuestionCallbackBinding(
                    "callback-key-" + chatType, "c", "telegram:chat:12345", "q", "yes", "12345", "", false));
            TelegramPayloads.TelegramInboundMessage callback = callback("callback-key-" + chatType, "12345", "");
            callback = new TelegramPayloads.TelegramInboundMessage(
                    callback.source(), callback.botId(), callback.updateType(), callback.updateId(), callback.messageId(),
                    callback.chatId(), chatType, callback.chatTitle(), callback.fromUserId(), callback.fromUsername(),
                    callback.fromFirstName(), callback.text(), callback.messageDate(), callback.rawUpdateJson(),
                    callback.callbackQueryId(), callback.callbackData(), callback.replyToMessageId());
            QuestionCallbackResult result = router.handleCallback(callback);
            assertFalse(result.handled());
            assertTrue(result.envelope().isEmpty());
        }
    }

    @Test
    void nonPrivateAdmissionCanBeEnabled() {
        HarnessInboundRouter router = new HarnessInboundRouter(new FakeRegistry(), false);
        assertEquals("message", router.route(message("group")).orElseThrow().type());
    }

    private static TelegramPayloads.TelegramInboundMessage message(String chatType) {
        return new TelegramPayloads.TelegramInboundMessage(
                "telegram", "", "message", 1, 2, "12345", chatType, null,
                7L, null, null, "ordinary message", 1, "{}");
    }

    private static TelegramPayloads.TelegramInboundMessage callback(String key, String chatId, String botId) {
        return new TelegramPayloads.TelegramInboundMessage(
                "telegram", botId, "callback_query", 99, 11, chatId, "private", null,
                7L, "alice", "Alice", null, 1700000000, "{}", "callback-id", "hq:" + key);
    }

    private static final class FakeRegistry implements QuestionCallbackRegistry {
        private final Map<String, QuestionCallbackBinding> bindings = new HashMap<>();

        @Override
        public void register(QuestionCallbackBinding binding) { bindings.put(binding.callbackKey(), binding); }

        @Override
        public QuestionCallbackBinding find(String callbackKey) { return bindings.get(callbackKey); }

        @Override
        public QuestionCallbackBinding consume(String callbackKey) { return bindings.remove(callbackKey); }

        @Override
        public QuestionCallbackBinding consumeAndComplete(QuestionCallbackBinding binding) {
            String key = binding.completionKey() == null ? binding.callbackKey() : binding.completionKey();
            if (!completed.add(key)) return null;
            return bindings.remove(binding.callbackKey());
        }

        @Override
        public boolean isCompleted(QuestionCallbackBinding binding) {
            String key = binding.completionKey() == null ? binding.callbackKey() : binding.completionKey();
            return completed.contains(key);
        }

        @Override
        public QuestionCallbackBinding consumeFreeText(String chatId, String botId, Integer replyToMessageId) {
            QuestionCallbackBinding binding = pendingFreeText;
            pendingFreeText = null;
            return binding;
        }

        @Override
        public boolean isFreeTextPrompt(String chatId, String botId, Integer replyToMessageId) { return expiredPrompt; }

        @Override
        public java.util.List<String> toggle(QuestionCallbackBinding binding) {
            String key = binding.correlationId() + ":" + binding.questionId();
            java.util.List<String> selected = selections.computeIfAbsent(key, ignored -> new java.util.ArrayList<>());
            if (selected.contains(binding.value())) selected.remove(binding.value()); else selected.add(binding.value());
            return java.util.List.copyOf(selected);
        }

        @Override
        public java.util.List<String> confirm(QuestionCallbackBinding binding) {
            return java.util.List.copyOf(selections.getOrDefault(binding.correlationId() + ":" + binding.questionId(), java.util.List.of()));
        }

        private final Map<String, java.util.List<String>> selections = new HashMap<>();
        private QuestionCallbackBinding pendingFreeText;
        private boolean expiredPrompt;
        private final java.util.Set<String> completed = new HashSet<>();
    }
}
