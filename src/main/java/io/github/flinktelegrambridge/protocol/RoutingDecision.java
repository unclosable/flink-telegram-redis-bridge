package io.github.flinktelegrambridge.protocol;

import java.util.List;

/**
 * Result of routing a parsed outbound envelope: either forward a concrete
 * Telegram outbound message or drop the entry (with a logged reason).
 */
public final class RoutingDecision {

    private final boolean forward;
    private final List<TelegramPayloads.TelegramOutboundMessage> outboundMessages;
    private final List<QuestionCallbackBinding> callbackBindings;
    private final String reason;

    private RoutingDecision(
            boolean forward,
            List<TelegramPayloads.TelegramOutboundMessage> outboundMessages,
            List<QuestionCallbackBinding> callbackBindings,
            String reason) {
        this.forward = forward;
        this.outboundMessages = outboundMessages;
        this.callbackBindings = callbackBindings;
        this.reason = reason;
    }

    public static RoutingDecision forward(TelegramPayloads.TelegramOutboundMessage message) {
        return forward(List.of(message), List.of());
    }

    public static RoutingDecision forward(
            List<TelegramPayloads.TelegramOutboundMessage> messages,
            List<QuestionCallbackBinding> callbackBindings) {
        return new RoutingDecision(true, List.copyOf(messages), List.copyOf(callbackBindings), null);
    }

    public static RoutingDecision drop(String reason) {
        return new RoutingDecision(false, List.of(), List.of(), reason);
    }

    public boolean shouldForward() {
        return forward;
    }

    public TelegramPayloads.TelegramOutboundMessage outboundMessage() {
        return outboundMessages.isEmpty() ? null : outboundMessages.get(0);
    }

    public List<TelegramPayloads.TelegramOutboundMessage> outboundMessages() {
        return outboundMessages;
    }

    public List<QuestionCallbackBinding> callbackBindings() {
        return callbackBindings;
    }

    public String reason() {
        return reason;
    }
}
