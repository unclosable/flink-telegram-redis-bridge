# Redis Streams protocol

Each Redis Stream entry has exactly one field named `json`. Its value is a JSON envelope with `version: 1` and snake_case field names.

```json
{"version":1,"conversation_id":"telegram:bot-1:chat:123","message_id":"telegram-1-2","type":"message","content":{"text":"hello"},"metadata":{}}
```

`conversation_id` identifies a conversation and is stable for the Telegram bot/chat pair. `message_id` identifies one inbound Telegram update. `correlation_id` identifies one interactive request and is echoed by its answer; it is never a substitute for `conversation_id`. `session_id` is optional routing context for consumers. Unknown fields may be ignored.

## Streams and groups

Telegram-to-consumer traffic is appended to `harness:inbound` (configurable). Consumers may use their own consumer group. Consumer-to-Telegram traffic is read from `harness:outbound` (configurable) by the bridge's configurable group, which acknowledges successful or safely handled entries and reclaims stale pending entries.

## Inbound envelopes

The bridge emits `message`, `steer`, `new_session`, and `question_answer`.

- `message` and `steer` contain `content.text`; metadata carries Telegram routing details.
- `new_session` requests a new consumer-side session for the conversation.
- `question_answer` has `correlation_id` and `content.answers`, an array of `{ "id": "question-id", "value": value }`. `value` is a string for single-select or free-text and an array of strings for confirmed multi-select.

## Outbound envelopes

Consumers may publish `assistant_message`, `error`, and `question_request`.

- `assistant_message` and `error` use `content.text` and are delivered to the selected Telegram destination.
- `question_request` requires `conversation_id`, `correlation_id`, and `content.questions`. Each question is `{ "id": "...", "text": "...", "options": ["..."], "multi_select": false }`. Omit `options` for free text.

The bridge preserves the separation between conversation routing and interaction correlation. Invalid, expired, duplicate, or unknown callback interactions are acknowledged and never fall through as ordinary messages.
