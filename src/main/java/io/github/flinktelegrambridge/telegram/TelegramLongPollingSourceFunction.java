package io.github.flinktelegrambridge.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.flinktelegrambridge.config.AppConfig;
import io.github.flinktelegrambridge.protocol.TelegramPayloads;
import org.apache.flink.streaming.api.functions.source.legacy.RichSourceFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.longpolling.BotSession;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.message.MaybeInaccessibleMessage;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Flink source that receives Telegram bot updates through long polling.
 */
public final class TelegramLongPollingSourceFunction extends RichSourceFunction<String> {

    private static final long RUN_LOOP_WAIT_MILLIS = 200L;
    private static final long RECONNECT_BACKOFF_MILLIS = 1000L;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger LOG = LoggerFactory.getLogger(TelegramLongPollingSourceFunction.class);

    private final String botId;
    private final String botToken;
    private final AtomicBoolean running;
    private final AtomicReference<Exception> asyncFailure;

    private transient TelegramBotsLongPollingApplication application;
    private transient BotSession botSession;

    public TelegramLongPollingSourceFunction() {
        this(null, AppConfig.load().telegramBotToken());
    }

    public TelegramLongPollingSourceFunction(String botToken) {
        this(null, botToken);
    }

    public TelegramLongPollingSourceFunction(String botId, String botToken) {
        this.botId = normalizeBotId(botId);
        this.botToken = requireNonBlank(botToken, "Telegram bot token must not be blank.");
        this.running = new AtomicBoolean(true);
        this.asyncFailure = new AtomicReference<>();
    }

    @Override
    public void run(SourceContext<String> ctx) throws Exception {
        try {
            startSession(ctx);
            while (running.get()) {
                Exception failure = asyncFailure.getAndSet(null);
                if (failure != null || botSession == null || !botSession.isRunning()) {
                    LOG.warn(
                            "Telegram long-polling session is restarting: {}",
                            failure == null ? "session stopped" : failure.getMessage(),
                            failure);
                    Thread.sleep(RECONNECT_BACKOFF_MILLIS);
                    if (!running.get()) {
                        break;
                    }
                    startSession(ctx);
                    continue;
                }
                Thread.sleep(RUN_LOOP_WAIT_MILLIS);
            }
        } finally {
            closeApplication();
        }
    }

    private void startSession(SourceContext<String> ctx) throws Exception {
        closeApplication();
        application = new TelegramBotsLongPollingApplication();
        botSession =
                application.registerBot(
                        botToken,
                        new LongPollingSingleThreadUpdateConsumer() {
                            @Override
                            public void consume(Update update) {
                                if (!running.get()) {
                                    return;
                                }
                                try {
                                    String payload = MAPPER.writeValueAsString(normalizeUpdate(update, botId));
                                    synchronized (ctx.getCheckpointLock()) {
                                        if (running.get()) {
                                            ctx.collect(payload);
                                        }
                                    }
                                } catch (Exception exception) {
                                    LOG.error("Telegram update consumption failed: {}", exception.getMessage(), exception);
                                    asyncFailure.compareAndSet(null, exception);
                                    if (botSession != null) {
                                        botSession.stop();
                                    }
                                }
                            }
                        });
    }

    @Override
    public void cancel() {
        running.set(false);
        if (botSession != null) {
            botSession.stop();
        }
    }

    private void closeApplication() throws Exception {
        if (application != null) {
            application.close();
        }
    }

    static TelegramPayloads.TelegramInboundMessage normalizeUpdate(Update update, String botId) throws Exception {
        Update normalizedUpdate = requireNonNull(update, "Telegram update must not be null.");
        String updateType = resolveUpdateType(normalizedUpdate);
        CallbackQuery callbackQuery = normalizedUpdate.hasCallbackQuery() ? normalizedUpdate.getCallbackQuery() : null;
        Message message = callbackQuery == null ? resolveMessage(normalizedUpdate) : null;
        MaybeInaccessibleMessage callbackMessage = callbackQuery == null ? null : callbackQuery.getMessage();
        Chat chat = message != null ? message.getChat() : callbackMessage == null ? null : callbackMessage.getChat();
        User from = message != null ? message.getFrom() : callbackQuery == null ? null : callbackQuery.getFrom();
        return new TelegramPayloads.TelegramInboundMessage(
                "telegram",
                botId,
                updateType,
                normalizedUpdate.getUpdateId(),
                message != null ? message.getMessageId() : callbackMessage == null ? null : callbackMessage.getMessageId(),
                chat == null || chat.getId() == null ? null : chat.getId().toString(),
                chat == null ? null : chat.getType(),
                chat == null ? null : firstNonBlank(chat.getTitle(), chat.getUserName()),
                from == null ? null : from.getId(),
                from == null ? null : from.getUserName(),
                from == null ? null : from.getFirstName(),
                message == null ? null : message.getText(),
                message != null ? message.getDate() : callbackMessage == null ? null : callbackMessage.getDate(),
                MAPPER.writeValueAsString(normalizedUpdate),
                callbackQuery == null ? null : callbackQuery.getId(),
                callbackQuery == null ? null : callbackQuery.getData(),
                message == null || message.getReplyToMessage() == null ? null : message.getReplyToMessage().getMessageId());
    }

    private static String normalizeBotId(String botId) {
        if (botId == null || botId.isBlank()) {
            return "";
        }
        return botId.trim();
    }

    private static Message resolveMessage(Update update) {
        if (update.hasMessage()) {
            return update.getMessage();
        }
        if (update.hasEditedMessage()) {
            return update.getEditedMessage();
        }
        if (update.hasChannelPost()) {
            return update.getChannelPost();
        }
        if (update.hasEditedChannelPost()) {
            return update.getEditedChannelPost();
        }
        if (update.hasBusinessMessage()) {
            return update.getBusinessMessage();
        }
        if (update.hasEditedBusinessMessage()) {
            return update.getEditedBuinessMessage();
        }
        return null;
    }

    private static String resolveUpdateType(Update update) {
        if (update.hasMessage()) {
            return "message";
        }
        if (update.hasEditedMessage()) {
            return "edited_message";
        }
        if (update.hasChannelPost()) {
            return "channel_post";
        }
        if (update.hasEditedChannelPost()) {
            return "edited_channel_post";
        }
        if (update.hasBusinessMessage()) {
            return "business_message";
        }
        if (update.hasEditedBusinessMessage()) {
            return "edited_business_message";
        }
        if (update.hasCallbackQuery()) {
            return "callback_query";
        }
        if (update.hasInlineQuery()) {
            return "inline_query";
        }
        if (update.hasChosenInlineQuery()) {
            return "chosen_inline_query";
        }
        if (update.hasMyChatMember()) {
            return "my_chat_member";
        }
        if (update.hasChatMember()) {
            return "chat_member";
        }
        if (update.hasChatJoinRequest()) {
            return "chat_join_request";
        }
        return "unknown";
    }

    private static String firstNonBlank(String primaryValue, String fallbackValue) {
        if (primaryValue != null && !primaryValue.isBlank()) {
            return primaryValue;
        }
        return fallbackValue;
    }

    private static <T> T requireNonNull(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
