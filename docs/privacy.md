# Privacy and player data

For operators. This page lists what VoiceWarden stores about players, where, and for how long, and how to answer a
player who asks for their data or asks you to delete it. It is not legal advice.

Speech recognition runs on your server. Audio and transcripts go nowhere else unless you turn on Discord webhooks.

## What is stored

All paths are in `plugins/VoiceWarden/`. "Days" settings are under `privacy.retention` in `config.yml`, and `0`
keeps that record forever. Old records are deleted once an hour.

| What | Where | Kept for (default) |
|---|---|---|
| Recent speech: transcript lines, plus audio for `/reportvoice` | Memory only, lost on restart | `transcript-buffer.retention` (12 hours) and up to 500 lines a player. With `transcript-buffer.enabled: false`, only the last `moderation.context-lines` lines for 5 minutes, without audio. |
| Recordings of flagged speech and reports: a WAV file and a JSON file with the transcript, rule matches, player name and UUID, and location | `recordings/` | `recordings.retention-days` (30 days), and the oldest go first above `recordings.max-total-size-mb`. A recording's WAV and JSON are deleted together. With `recordings.mode: all`, every utterance is recorded. |
| Transcript log of everything said, with name and UUID | `logs/transcripts-<date>.log` | `transcript-logs-days` (30 days). Only written when `logging.transcripts` is true. |
| Active voice mutes: name, reason, who muted, expiry | `data/voicewarden.db` | Until the mute ends. |
| Mute history: mutes, unmutes, expiries and pardons, with reasons | `data/voicewarden.db` | `mute-history-days` (365 days). Kept while the player is still muted. |
| Offence count for the mute ladder | `data/voicewarden.db` | Until `moderation.mute-ladder.reset-after-days` (30 days) resets it. With `0` it is never reset or deleted. |
| Voice reports: reporter and target names and UUIDs, the target's transcript lines, the reviewer | `data/voicewarden.db` | `reports-days` (90 days), and at most 1000 reports. |
| Consent answers: accepted or denied, rules version, when they answered, when they were last seen | `data/voicewarden.db` | Deleted once the player hasn't joined for `consent-days` (365 days). In `confirm` mode they are asked again if they come back. |
| Exports made with `/vw export` | `exports/` | `exports-days` (7 days). |

If `data/voicewarden.db` is ever unreadable, it is moved aside as `voicewarden.db.corrupt-<UUID>`. Those copies hold
the same data, are never deleted automatically, and `/vw forget` doesn't touch them. Delete them once you no longer
need them.

### Data that leaves VoiceWarden

- **Discord.** Once a webhook is enabled, alerts are sent automatically to that Discord channel: player names, UUIDs,
  transcripts, context lines, location (with `include-server-info`) and, with `include-audio`, the recording. Discord is a third party.
  VoiceWarden can't delete messages it has sent, so remove them in Discord yourself.
- **Server console log.** With `alerts.console: true`, flagged speech and voice reports are logged with names and
  transcripts. With `logging.debug: true`, every transcript is logged. These lines go to the server's `logs/` folder,
  which VoiceWarden doesn't manage.
- **PlaceholderAPI.** `%voicewarden_mute_reason%` shows a player's mute reason wherever you use it, such as a
  scoreboard or tab list, so other players can see it.
- **Custom commands.** `custom-commands` pass the player's name, UUID and transcript to whatever console commands you
  set, and those commands' plugins keep what they keep.
- **LuckPerms.** With `mute.luckperms.enabled`, a mute is copied to LuckPerms as a temporary deny node. It holds no
  reason and is removed when the mute ends.

## Telling players

`privacy.join-message` shows the `privacy-join` text from `messages.yml` on join, and `consent.dialog` holds the
consent menu's text. Both say staff may keep recordings and that alerts may go to Discord. If you change what you
record or where it goes, such as `recordings.mode: all`, update them to match. With
`consent.mode: confirm`, players accept or deny before any of their speech is processed. See
[Consent](configuration.md#consent).

Servers that installed an older version keep their own `messages.yml`, so their consent menu may still say nothing
is sent to an outside service. Copy the new `privacy-join` and `consent.dialog` texts from the bundled file if you
use Discord.

When a player withdraws consent with `/voiceconsent`, their recent speech in memory is deleted straight away and
nothing more is processed. Recordings, reports and history from before stay until their retention runs out, or until
you run `/vw forget`.

## Requests from players

Both commands need `voicewarden.admin`. They take a player name or, for a player VoiceWarden can't find by name, their
UUID.

### A copy of their data

`/vw export <player>` writes one JSON file to `plugins/VoiceWarden/exports/<uuid>-<time>.json` and tells you the path.
It holds their active mute, mute history, offence count, consent answer, reports about them and reports they filed,
their recent speech, their transcript log lines, and the content of their recordings. Recording audio isn't copied
into the file; the export lists each WAV's path so you can send it alongside.

To protect other players, an export leaves out who reported the player, and for reports they filed it leaves out the
other player's speech. Read the file before you send it, and delete it afterwards. It is deleted after
`exports-days` anyway.

### Erasing their data

`/vw forget <player>` explains what will happen. `/vw forget <player> confirm` (or the button) then:

- lifts any voice mute, without announcing it, and removes its LuckPerms deny nodes
- deletes their mute, mute history, offence count and consent answer
- deletes every report they filed or were reported in
- deletes their recordings (WAV and JSON) and their exports
- removes their lines from recent speech and from every transcript log file

Recordings of reports they filed hold the other player's voice, and are deleted too. It reports how much of each was
deleted. If LuckPerms can't remove the deny nodes, nothing is erased; run the command
again. Discord messages and the server console log are not erased, so delete those yourself.

In `confirm` consent mode, an erased player is asked for consent again: straight away if they are online, or on
their next join.
