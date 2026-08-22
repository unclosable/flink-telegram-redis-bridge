# Redis Streams protocol

Companion DSH-side contract: [harness-redis-connector PROTOCOL.md](https://github.com/unclosable/harness-redis-connector/blob/main/PROTOCOL.md).

Each Redis Stream entry has exactly one field named `json`. Its value is a JSON envelope with `version: 1` and snake_case field names.

```json
{"version":1,"conversation_id":"telegram:bot-1:chat:123","message_id":"telegram-1-2","type":"message","content":{"text":"hello"},"metadata":{}}
```

`conversation_id` identifies a conversation and is stable for the Telegram bot/chat pair. `message_id` identifies one inbound Telegram update. `correlation_id` identifies one interactive request and is echoed by its answer; it is never a substitute for `conversation_id`. `session_id` is optional routing context for consumers. Unknown fields may be ignored.

## Streams and groups

Telegram-to-consumer traffic is appended to `harness:inbound` (configurable). Consumers may use their own consumer group. Consumer-to-Telegram traffic is read from `harness:outbound` (configurable) by the bridge's configurable group, which acknowledges successful or safely handled entries and reclaims stale pending entries.

## Inbound envelopes

The bridge emits `message`, `steer`, `new_session`, `question_answer`, and `system_command`. Inbound admission is private-chat-only by default; group, supergroup, channel, and unknown chat types are not appended unless `telegram.inbound.private-only` is disabled.

- `message` and `steer` contain `content.text`; metadata carries Telegram routing details.
- `new_session` requests a new consumer-side session for the conversation.
- `question_answer` has `correlation_id` and `content.answers`, an array of `{ "id": "question-id", "value": value }`. `value` is a string for single-select or free-text and an array of strings for confirmed multi-select.
- `system_command` is a frontend-independent request for connector-owned system control. It uses `content.command`, for example `{ "command": "renew_session" }`. A bare private-chat Telegram `/renew` is normalized to `renew_session`; it is never emitted as a `message`, so it cannot invoke an LLM. Matching is exact: `/renew `, `/renew anything`, and `/Renew` remain ordinary messages. Future frontends and commands reuse this envelope/type by mapping their UI syntax to a stable command name.

`/renew` takes precedence over an interactive free-text reply: a `/renew` reply to
a pending question is emitted as a `system_command` and does not consume the
pending free-text answer.

## Outbound envelopes

Consumers may publish `assistant_message`, `error`, `question_request`, `system_command_result`, and (when enabled) `group_message`.

- `assistant_message` and `error` use `content.text` and are delivered to the selected Telegram destination.
- Additively, `assistant_message` and `error` may set top-level `content_type` to
  `text/markdown` and carry `content` as a plain string. Version remains `1`.
  The bridge converts its supported CommonMark subset to Telegram MarkdownV2;
  object-shaped `content: {"text":"..."}` remains supported, including with
  `content_type`, while envelopes without it retain legacy plain-text behavior.
- `question_request` requires `conversation_id`, `correlation_id`, and `content.questions`. Each question is `{ "id": "...", "text": "...", "options": ["..."], "multi_select": false }`. Omit `options` for free text.
- `system_command_result` is the connector's response to a `system_command`. It echoes the inbound `message_id`, uses `content.command` and `content.status` (`ok` or `error`), and may provide frontend-facing `content.message`. The bridge sends that message directly to the originating chat/bot using normal metadata/conversation routing. A missing or blank message falls back to `Command succeeded.` for `ok`, otherwise `Command failed.`; it is not an `assistant_message` and receives no error prefix.
- `group_message` is a non-conversational, one-way broadcast. Its v1 shape uses `content.text`, `metadata.target_chat_id`, and `metadata.bot_id`; the bot id must name an existing indexed bot. It never falls back to `conversation_id`, `metadata.chatId`, `metadata.botId`, or a default chat. The side feature is disabled unless `telegram.group-message.enabled` is set.

## Configuration

- `telegram.inbound.private-only=true` fails closed so only Telegram private chats reach `harness:inbound`; set it to `false` to admit groups and channels.
- `telegram.group-message.enabled=false` keeps explicit `group_message` broadcasts disabled by default.

The bridge preserves the separation between conversation routing and interaction correlation. Invalid, expired, duplicate, or unknown callback interactions are acknowledged and never fall through as ordinary messages.

## System-control ownership

The Telegram bridge owns frontend recognition and normalization (currently `/renew` to `renew_session`) plus delivery of result text. The shared Redis protocol owns the frontend-independent `system_command` and `system_command_result` shapes. The connector owns command execution, session lifecycle changes, and creation of the success/failure result. This boundary keeps system commands out of the ordinary conversation/LLM path and lets future frontends reuse the same normalized command model.
