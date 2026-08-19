# Architecture

`pipeline` entry points are currently the two top-level Flink main classes. `telegram` owns Telegram API source, sink, callback response, and envelope conversion. `redis` owns the Redis Streams client. `registry` owns the Redis-backed question callback registry. `protocol` contains the versioned wire records. `service` parses envelopes and performs inbound/outbound routing.

Inbound flow: `TelegramLongPollingSourceFunction` -> `TelegramToHarnessEnvelopeFunction` -> `HarnessRedisStreamSinkFunction` -> Redis `XADD`.

Outbound flow: Redis group read -> `HarnessOutboundSourceFunction` -> `HarnessOutboundProcessor` / `HarnessOutboundRouter` -> `TelegramMessageSinkFunction`.

Questions use the registry as shared state across the two independent Flink jobs. It stores opaque callback bindings and free-text prompt bindings with a 24-hour TTL, while selection and completion keys prevent duplicate answers.
