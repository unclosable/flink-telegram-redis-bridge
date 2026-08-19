# Architecture

## DeepSeek Harness ecosystem

```
Telegram <-> flink-telegram-redis-bridge <-> Redis Streams <-> harness-redis-connector <-> DeepSeek Harness session/agent
```

This repository owns the transport-only Telegram <-> Redis Streams bridge. The companion [harness-redis-connector](https://github.com/unclosable/harness-redis-connector) repository owns the adapter-only Redis Streams <-> DeepSeek Harness session/agent integration.

### Relationship to harness-redis-connector

Both projects communicate only through the Redis Streams envelope protocol in [PROTOCOL.md](../PROTOCOL.md): `harness:inbound`, `harness:outbound`, the `json` field, and consumer groups. Either side can be replaced by any compatible implementation. When the wire contract changes, keep this repository's `PROTOCOL.md` and the connector's [PROTOCOL.md](https://github.com/unclosable/harness-redis-connector/blob/main/PROTOCOL.md) in sync to avoid drift.

`pipeline` entry points are currently the two top-level Flink main classes. `telegram` owns Telegram API source, sink, callback response, and envelope conversion. `redis` owns the Redis Streams client. `registry` owns the Redis-backed question callback registry. `protocol` contains the versioned wire records. `service` parses envelopes and performs inbound/outbound routing.

Inbound flow: `TelegramLongPollingSourceFunction` -> `TelegramToHarnessEnvelopeFunction` -> `HarnessRedisStreamSinkFunction` -> Redis `XADD`.

Outbound flow: Redis group read -> `HarnessOutboundSourceFunction` -> `HarnessOutboundProcessor` / `HarnessOutboundRouter` -> `TelegramMessageSinkFunction`.

Questions use the registry as shared state across the two independent Flink jobs. It stores opaque callback bindings and free-text prompt bindings with a 24-hour TTL, while selection and completion keys prevent duplicate answers.
