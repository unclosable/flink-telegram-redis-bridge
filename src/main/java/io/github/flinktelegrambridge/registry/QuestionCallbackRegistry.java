package io.github.flinktelegrambridge.registry;

import io.github.flinktelegrambridge.protocol.QuestionCallbackBinding;

/** Persistent callback-key registry shared by the two independent bridge jobs. */
public interface QuestionCallbackRegistry extends AutoCloseable {

    void register(QuestionCallbackBinding binding);

    /** Looks up a binding without consuming it, so routing data can be validated first. */
    QuestionCallbackBinding find(String callbackKey);

    /** Atomically returns and consumes a single-select or confirm binding. */
    QuestionCallbackBinding consume(String callbackKey);

    /** Atomically consumes this answer only if no sibling callback has completed its question. */
    default QuestionCallbackBinding consumeAndComplete(QuestionCallbackBinding binding) {
        return consume(binding.callbackKey());
    }

    /** Whether any option/confirm key for this question has already completed it. */
    default boolean isCompleted(QuestionCallbackBinding binding) {
        return false;
    }

    /** Atomically claims the pending optionless question for this chat and bot. */
    default QuestionCallbackBinding consumeFreeText(String chatId, String botId, Integer replyToMessageId) {
        return null;
    }

    /** Whether this reply target was once a free-text prompt (including an expired one). */
    default boolean isFreeTextPrompt(String chatId, String botId, Integer replyToMessageId) {
        return false;
    }

    /** Registers a free-text binding only after Telegram has returned its prompt message id. */
    default void registerFreeTextPrompt(QuestionCallbackBinding binding, Integer messageId) {
        throw new UnsupportedOperationException("Free-text prompt registration is not configured");
    }

    /** Toggle one multi-select option and return the complete selection after the change. */
    java.util.List<String> toggle(QuestionCallbackBinding binding);

    /** Return the final multi-select values and clear the transient selection state. */
    java.util.List<String> confirm(QuestionCallbackBinding binding);

    @Override
    default void close() {}
}
