# VoiceWarden

[![Build and test](https://github.com/danielmillar/VoiceWarden/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/danielmillar/VoiceWarden/actions/workflows/build.yml) [![Licence: MIT](https://img.shields.io/badge/licence-MIT-blue.svg)](https://github.com/danielmillar/VoiceWarden/blob/main/LICENSE) ![Paper 26.1+](https://img.shields.io/badge/Paper-26.1%2B-informational) ![Java 25](https://img.shields.io/badge/Java-25%2B-orange)

VoiceWarden is a Paper plugin that listens to voice chat and helps you deal with players who swear, harass others or
break your rules. It turns speech into text, checks the text against rules you control, and warns or mutes the player
automatically. Your staff get an alert and can listen to a recording.

Everything runs **on your own server**. Speech recognition uses no outside service, and you don't need an account or
API key. Nothing leaves your server unless you turn on Discord alerts: once a webhook is set up, alerts are sent to it
automatically, with the transcript and, if you choose, the recording. See [Privacy](docs/privacy.md).

## How it works

```
player speaks → local speech-to-text → your rules → warn or mute → staff alert (in game and Discord)
```

## Features

- **Local speech recognition.** A speech model runs on your server. Like any speech recognition, it can make
  mistakes, so test it with your own microphones before relying on it.
- **Rules you control.** Swear words, slurs, threats, harassment, advertising and more. Add or remove words and rules
  to suit your community.
- **Automatic mutes.** Set how many mute-rule hits within a time window trigger a mute. Repeat offenders get longer
  mutes. Below the threshold, players can be warned instead.
- **Staff tools.** In-game alerts show what was said, what was said just before, and where the player is, with a button
  to mute them. Alerts can also go to Discord with the recording attached.
- **Player reports.** Players report each other with `/reportvoice`. Staff review reports and listen to the recording.
- **Player consent.** By default, players are told on join that voice chat is moderated. Or ask each player to accept
  in an in-game menu first: until they do, their voice is never processed.
- **Light on the server.** Audio is processed in the background and should not slow your server down.

## Requirements

- A **Paper** server, version **26.1 or newer**, running **Java 25 or newer**.
- [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) (version 2.6.x), which provides voice chat.
- Optional: [LuckPerms](https://luckperms.net) (version 5.5 or newer). VoiceWarden enforces mutes itself, so it works
  without it. With LuckPerms, mutes also apply on other servers sharing your LuckPerms storage, and commands can find
  players who have never joined this server.
- Optional: [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi), to show a player's voice mute on
  scoreboards, tab lists, chat formats and holograms. See [Placeholders](#placeholders).
- Linux, Windows or macOS.
- About **3 GB of spare memory (RAM)** for the best-quality speech model, plus free disk space for the model
  (between about 400 MB and 1 GB, depending on which you choose).

## Setup

1. Download `VoiceWarden-<version>.jar` from the [Releases page](https://github.com/danielmillar/VoiceWarden/releases)
   (the `.sha256` file next to it can be used to check the download) and put it in your server's `plugins` folder,
   alongside Simple Voice Chat (and LuckPerms, if you use it).
2. Start the server. The first time, VoiceWarden downloads the speech recognition files it needs. This takes a few
   minutes and your server stays playable meanwhile. Watch the server console for the message
   **`Speech engine ready`**.
3. Make sure players can use voice chat. Simple Voice Chat 2.6 gives `voicechat.speak` and `voicechat.listen` only to
   ops by default, so everyone else hears nothing and is told they have no permission to speak. With LuckPerms, run
   this in the console:

   ```
   /lp group default permission set voicechat.speak true
   /lp group default permission set voicechat.listen true
   ```
4. Let your staff see alerts by giving them the `voicewarden.alerts` permission. With LuckPerms, run this in the
   console, replacing `moderator` with your staff group's name:

   ```
   /lp group moderator permission set voicewarden.alerts true
   ```
5. Optional: set up Discord alerts by adding webhook links in `plugins/VoiceWarden/config.yml`, then check they work with
   `/voicewarden discordtest all`.

**Trying it out safely.** In `config.yml`, set `moderation.dry-run: true`. VoiceWarden will then report problems to
staff but never mute anyone. Switch it back when you're happy with how it behaves.

**Server with no internet?** Set `speech-to-text.auto-download: false`. This disables all automatic downloads, both
the speech model and the native libraries. Once you have put the required files for your platform in place, the console
gives the paths and download links for anything still missing.

## Commands

The main command is `/voicewarden`, or `/vw` for short.

### Commands for staff

| Command | What it does | Permission |
|---|---|---|
| `/vw vcmute <player> <duration> [reason]` | Mutes a player's voice. Durations look like `30m`, `2h`, `1d` or `perm`. Works on offline players. | `voicewarden.vcmute` |
| `/vw unvcmute <player>` | Removes a voice mute. | `voicewarden.vcmute` |
| `/vw pardon <player> [reason]` | Undoes a wrong call in one step: removes the mute, clears the offence count and flags, and records the pardon. | `voicewarden.vcmute` |
| `/vw mutes` | Lists everyone currently muted. | `voicewarden.vcmute` |
| `/vw offenses <player> [reset]` | Shows a player's offence count, or resets it. | `voicewarden.vcmute` |
| `/vw history <player> [page]` | Shows a player's voice record: offences, current mute, consent, and past mutes, unmutes, expiries and pardons, newest first. | `voicewarden.vcmute` |
| `/vw transcript <player> [lines]` | Shows what a player recently said. | `voicewarden.report.review` |
| `/vw alerts` | Turns your own in-game alerts on or off. | `voicewarden.alerts` |
| `/viewreport <id or player> [minutes]` (`/vr`) | Opens a report, or a player's recent speech, as a book. | `voicewarden.report.review` |
| `/reportinbox [review <id>]` (`/rinbox`, `/vreports`) | Lists open reports and marks them as reviewed. | `voicewarden.report.review` |

### Commands for admins

| Command | What it does | Permission |
|---|---|---|
| `/vw reload` | Applies changes you made to the config files, without restarting. | `voicewarden.reload` |
| `/vw stats` | Shows how the plugin is doing: speed, queue, dropped audio and incidents. | `voicewarden.stats` |
| `/vw check` | Checks the whole setup (voice chat and its permissions, speech engine, model files, database, LuckPerms, Discord, config and recordings) and says what is OK, what to look at, and the fix for each problem. | `voicewarden.admin` |
| `/vw test <text>` | Checks some text against your rules, which is handy when editing word lists. | `voicewarden.admin` |
| `/vw discordtest [all, flag, mute or report]` | Sends a test message to your Discord webhooks. | `voicewarden.admin` |
| `/vw export <player or UUID>` | Writes everything VoiceWarden stores about a player to one file, for a player who asks for their data. See [Privacy](docs/privacy.md). | `voicewarden.admin` |
| `/vw forget <player or UUID> [confirm]` | Erases everything VoiceWarden stores about a player and lifts their voice mute. Without `confirm`, it only explains. | `voicewarden.admin` |

### Commands for players

| Command | What it does | Permission |
|---|---|---|
| `/reportvoice <player> [minutes]` (`/rvoice`) | Reports a player's recent voice chat to staff, with a recording. | `voicewarden.report` (everyone by default) |
| `/voiceconsent` | Shows your answer to voice moderation and, in `confirm` mode, opens the menu to change it. | `voicewarden.consent` (everyone by default) |

## Permissions

| Permission | Who has it by default | What it's for |
|---|---|---|
| `voicewarden.admin` | Operators | Everything above except bypass. |
| `voicewarden.bypass` | Nobody | Players with this are never listened to or muted. |
| `voicewarden.alerts` | Operators | Receiving in-game alerts. |
| `voicewarden.reload` | Operators | Using `/vw reload`. |
| `voicewarden.stats` | Operators | Using `/vw stats`. |
| `voicewarden.vcmute` | Operators | Using `/vw vcmute`, `unvcmute`, `pardon`, `mutes`, `offenses` and `history`. |
| `voicewarden.report` | Everyone | Using `/reportvoice`. |
| `voicewarden.consent` | Everyone | Using `/voiceconsent`. |

## Placeholders

With [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi) installed, VoiceWarden adds these:

| Placeholder | Shows |
|---|---|
| `%voicewarden_muted%` | `true` or `false`. |
| `%voicewarden_mute_remaining%` | Time left on the voice mute, such as `9m 47s`, or `permanent`. Empty when not muted. |
| `%voicewarden_mute_reason%` | The mute's reason. Empty when not muted. |
| `%voicewarden_offenses%` | The player's offence count on the mute ladder. |
| `%voicewarden_next_mute%` | How long their next automatic mute would last, such as `15m`. |
| `voicewarden.report.review` | Operators | Seeing reports, `/viewreport`, `/reportinbox` and `/vw transcript`. |

## Troubleshooting

- **Something isn't working.** Run `/vw check`. Each ⚠ or ✘ line names the setting or step that fixes it.
- **Nothing is happening.** Check the console for `Speech engine ready`. Until it appears, the first download is still
  running.
- **Players who are not op can't talk or hear.** Grant them `voicechat.speak` and `voicechat.listen` (setup step 3).
- **Someone isn't being picked up.** Make sure they don't have `voicewarden.bypass`.
- **Staff see no alerts.** Give them `voicewarden.alerts`, and ask them to run `/vw alerts` in case they turned them off.
- **Audio is being dropped, or the server feels slow.** Run `/vw stats` and look at *Speed* and *dropped*. Choose a
  lighter speech model (see [Configuration](docs/configuration.md#speech-models)).

## Documentation

- [Configuration](docs/configuration.md): how mutes work, config files, updating, and choosing a speech model.
- [Privacy](docs/privacy.md): what is stored, for how long, and how to export or erase a player's data.
- [Architecture](docs/architecture.md): detection, threading, performance, and licences and attribution.
- [Contributing](CONTRIBUTING.md): building, testing and opening a pull request.

## Licence

VoiceWarden's code is under the [MIT licence](LICENSE). The speech models and libraries it downloads have their own
licences, listed in [Licences and attribution](docs/architecture.md#licences-and-attribution).

VoiceWarden contains AI-generated code and documentation, alongside human contributions, and AI assistance does not
guarantee correctness or security. Test it on a development server before relying on it for moderation.
