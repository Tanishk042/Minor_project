# Telegram alerts — setup and test

ForestEar sends alert messages through the Telegram Bot API. Delivery is
**optional** and disabled by default. The repository contains no token.

## 1. Create a bot and get credentials

1. In Telegram, open **@BotFather** → `/newbot` → choose a name and username.
2. BotFather returns a token like `123456789:AAExampleToken`. This is
   `TELEGRAM_BOT_TOKEN`.
3. Send any message to your new bot from the account or group that should
   receive alerts.
4. Get the chat ID:
   - Direct chat: open `https://api.telegram.org/bot<TOKEN>/getUpdates` and
     read `result[].message.chat.id`.
   - Group: add the bot to the group, send a message, then read
     `result[].message.chat.id` (usually a negative number).
   That value is `TELEGRAM_CHAT_ID`.

Never commit the token. Set it as an environment variable:

```bash
export TELEGRAM_BOT_TOKEN="123456789:AAExampleToken"
export TELEGRAM_CHAT_ID="987654321"
```

## 2. Enable delivery

Edit `configs/default.yaml`:

```yaml
alerts:
  telegram:
    enable: true      # was false
    dry_run: false    # was true
```

`dry_run: true` always wins: the message is logged to the console and nothing
is sent, even with `enable: true`. If credentials are missing while delivery is
enabled, the router logs a warning and falls back to dry-run rather than
crashing.

The Bot API base is `https://api.telegram.org` unless you set
`alerts.telegram.api_base` or `TELEGRAM_API_BASE` (used by tests/mirrors only).

## 3. Automated test (no network, no real bot)

```bash
cd forestear
../.venv/bin/python -m pytest tests/test_telegram.py -v
```

The tests start a local fake HTTP server and assert the exact request path,
payload, dedup behaviour, non-200 handling, dry-run isolation and bot polling.
They prove the client code works; they do **not** prove Telegram accepts your
real token.

## 4. Manual end-to-end test (needs your real bot)

Run the backend with credentials exported:

```bash
cd forestear
../.venv/bin/python -m backend.main --port 8000
```

In another terminal, send one event through the ingest endpoint:

```bash
curl -s -X POST http://127.0.0.1:8000/ingest/event \
  -H "X-Node-Token: forestear-dev-token" \
  -H "Content-Type: application/json" \
  -d '{
    "node_id": "node-manual",
    "timestamp_utc": "2026-01-01T12:00:00Z",
    "lat": 12.9716,
    "lon": 77.5946,
    "class": "chainsaw",
    "severity": "WARNING",
    "confidence": 0.93,
    "persistence": "3/5"
  }'
```

Expected: one Telegram message with the class, confidence, node, timestamp and
a Google Maps link. Re-sending the same class within `dedup_window_s` (45 s)
should be suppressed — this is intentional anti-spam behaviour, not a failure.

Also verify the bot commands by messaging the bot: `/status`, `/events`,
`/ack <event-id>`.

## 5. Checklist for the report

Record these as measured only when they actually happened:

- [ ] `pytest tests/test_telegram.py` → all passed (local fake server).
- [ ] One real alert delivered to a real chat.
- [ ] Dedup suppression observed within the window.
- [ ] `/status`, `/events`, `/ack` replied correctly.
- [ ] Bot token stored only as an environment variable (not in git).

If a box is unchecked, report Telegram as *implemented, not verified
end-to-end*.
