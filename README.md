# Flink Telegram Redis Bridge

A small Apache Flink application that bridges Telegram updates and Redis Streams. It is designed to sit between Telegram users and any service that understands the JSON contract in [PROTOCOL.md](PROTOCOL.md).

```
Telegram <-> inbound Flink job <-> harness:inbound (Redis Stream) <-> consumer
Telegram <-> outbound Flink job <-> harness:outbound (Redis Stream) <-> producer
```

The inbound job normalizes messages, replies, and callback queries. The outbound job consumes responses and sends Telegram messages. Redis consumer groups provide acknowledgement and stale-message recovery.

## Quick start

Build the shaded artifact with Java 21:

```sh
mvn package
```

Submit `io.github.flinktelegrambridge.pipeline.TelegramToHarnessRedisPipeline` and `io.github.flinktelegrambridge.pipeline.HarnessRedisToTelegramPipeline` to a Flink 2.2 cluster. Set the required environment variables in each job's deployment.

| Variable | Required | Meaning |
| --- | --- | --- |
| `REDIS_URI` | yes | Redis endpoint, for example `redis://localhost:6379` |
| `REDIS_USERNAME` | no | Redis ACL user |
| `REDIS_PASSWORD` | no | Redis ACL credential |
| `HARNESS_REDIS_INBOUND_STREAM` | no | Inbound stream; default `harness:inbound` |
| `HARNESS_REDIS_OUTBOUND_STREAM` | no | Outbound stream; default `harness:outbound` |
| `HARNESS_REDIS_CONSUMER_GROUP` | no | Outbound consumer group; default `flink-harness-inbound` |
| `TELEGRAM_BOT_1_ID` | no | First bot identifier; defaults to `bot-1` |
| `TELEGRAM_BOT_1_TOKEN` | yes | First Telegram bot credential |
| `TELEGRAM_BOT_1_DEFAULT_CHAT_ID` | no | Fallback destination for this bot |
| `REDIS_QUEUE_CONSUMER_IDLE_WAIT_MILLIS` | no | Redis read block time; default `1000` |
| `TELEGRAM_CALLBACK_REGISTRY_TTL_SECONDS` | no | Callback binding lifetime; default `86400` |

Use `TELEGRAM_BOT_2_*` through `TELEGRAM_BOT_16_*` for additional bots. A bot exists only when its numbered credential variable is non-empty. The legacy `TELEGRAM_BOT_TOKEN` and `TELEGRAM_OUTBOUND_DEFAULT_CHAT_ID` variables remain supported for a single bot.

## Multi-bot routing

Inbound conversation IDs include the bot ID (`telegram:<bot>:chat:<chat>`), so different bots never share a conversation. Outbound payload metadata may select a bot; otherwise the bridge uses the bot encoded in the conversation ID, then the first configured bot. Each bot may have its own fallback chat ID.

## Interactive questions

An outbound `question_request` renders either an inline keyboard or a free-text prompt. Single-select answers are emitted immediately. Multi-select answers toggle options and emit only after **Confirm**. Free-text requests use Telegram's `ForceReply`. Callback data is a compact `hq:<uuid>` key; the question context is held in Redis for 24 hours. Completed prompts are edited, and stale, malformed, or duplicate interactions are acknowledged without becoming ordinary messages.

## Build and test

```sh
mvn test
mvn -q -DskipTests package
```

The package goal produces `target/flink-telegram-redis-bridge-*-all.jar` with Netty relocated.

## License

Apache-2.0. See [LICENSE](LICENSE).
