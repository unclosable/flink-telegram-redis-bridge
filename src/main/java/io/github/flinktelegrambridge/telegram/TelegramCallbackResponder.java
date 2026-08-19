package io.github.flinktelegrambridge.telegram;

import io.github.flinktelegrambridge.protocol.TelegramPayloads;
import io.github.flinktelegrambridge.service.QuestionCallbackResult;

/** Sends Bot API feedback for a processed inline-keyboard callback. */
interface TelegramCallbackResponder extends AutoCloseable {
    void respond(TelegramPayloads.TelegramInboundMessage inbound, QuestionCallbackResult result);
    @Override default void close() {}
}
