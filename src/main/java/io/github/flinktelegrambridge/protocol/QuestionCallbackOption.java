package io.github.flinktelegrambridge.protocol;

/** One opaque callback key and its corresponding visible answer value. */
public record QuestionCallbackOption(String callbackKey, String value) {}
