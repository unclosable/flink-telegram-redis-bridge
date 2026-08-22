package io.github.flinktelegrambridge.pipeline;

import io.github.flinktelegrambridge.telegram.HarnessRedisStreamSinkFunction;
import io.github.flinktelegrambridge.telegram.TelegramLongPollingSourceFunction;
import io.github.flinktelegrambridge.telegram.TelegramToHarnessEnvelopeFunction;
import io.github.flinktelegrambridge.config.AppConfig;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

/**
 * Outbound job: Telegram updates are normalized, wrapped in the INBOUND harness
 * envelope, and written to the {@code harness:inbound} Redis Stream via
 * {@code XADD}.
 */
public class TelegramToHarnessRedisPipeline {

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.load();

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);

        DataStream<String> telegramUpdates;
        if (config.telegramBots().size() > 1) {
            AppConfig.TelegramBotConfig personalBot = config.telegramBots().get(0);
            DataStream<String> unioned =
                    env.addSource(new TelegramLongPollingSourceFunction(personalBot.id(), personalBot.token()))
                            .name("telegram-source-" + personalBot.id());
            for (int index = 1; index < config.telegramBots().size(); index++) {
                AppConfig.TelegramBotConfig bot = config.telegramBots().get(index);
                DataStream<String> stream =
                        env.addSource(new TelegramLongPollingSourceFunction(bot.id(), bot.token()))
                                .name("telegram-source-" + bot.id());
                unioned = unioned.union(stream);
            }
            telegramUpdates = unioned;
        } else {
            telegramUpdates = env.addSource(new TelegramLongPollingSourceFunction()).name("telegram-source");
        }

        DataStream<String> envelopes =
                telegramUpdates
                        .assignTimestampsAndWatermarks(WatermarkStrategy.noWatermarks())
                        .flatMap(new TelegramToHarnessEnvelopeFunction())
                        .name("telegram-to-harness-inbound-envelope");

        envelopes
                .addSink(new HarnessRedisStreamSinkFunction(config.harnessRedisInboundStream()))
                .name("harness-inbound-stream-sink");

        env.execute("telegram-to-harness-redis");
    }
}
