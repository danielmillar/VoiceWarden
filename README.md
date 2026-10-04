# NevusVoice

NevusVoice is a plugin for Minecraft servers that listens to voice chat and helps you deal with players who swear,
harass others or break your rules.

It turns what players say into text, checks that text against a list of rules you control, and then warns or mutes
the player automatically. Your staff get an alert and can listen to a recording.

Everything runs **on your own server**. No audio is sent to any outside service, and you don't need an account or API
key. The only audio that ever leaves your server is a recording you choose to send to your own Discord.

## What it does

- **Understands speech.** It converts voice chat into text using a speech recognition model that runs on your server.
  Like any speech recognition, it can make mistakes, so test it with your own microphones before relying on it.
- **Spots bad language.** It checks for swear words, slurs, threats, harassment, advertising and more. You can add or
  remove words and rules to suit your community.
- **Mutes automatically.** Players are warned first. If they keep breaking the rules, they are muted, and repeat
  offenders get longer mutes each time. A muted player can also be stopped from hearing others if you want.
- **Helps your staff.** Staff see an in-game alert showing what was said, what was said just before, and where the
  player is, with a button to mute them. Alerts can also go to Discord with the recording attached.
- **Lets players report each other.** Players can report someone with `/reportvoice`. Staff can review reports and
  listen to the recording.
- **Stays out of the way.** It runs in the background and should not slow your server down.

## What you need

- A **Paper** Minecraft server, version **26.1.2**, running **Java 25 or newer**.
- Two other plugins installed first:
  - [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) (version 2.6.x), which provides voice chat.
  - [LuckPerms](https://luckperms.net) (version 5.5 or newer), which handles permissions and remembers each
    player's offences.
- A computer running Linux, Windows or macOS.
- About **3 GB of spare memory (RAM)** for the best-quality speech model, plus free disk space for the model
  (between about 400 MB and 1 GB, depending on which you choose).

## Setting it up

1. Download `NevusVoice-<version>.jar` and put it in your server's `plugins` folder, alongside Simple Voice Chat and
   LuckPerms.
2. Start the server. The first time, NevusVoice downloads the speech recognition files it needs. This takes a few
   minutes and your server stays playable meanwhile. Watch the server console for the message
   **`Speech engine ready`**.
3. Let your staff see alerts by running this in the console, replacing `moderator` with your staff group's name:

   ```
   /lp group moderator permission set nevusvoice.alerts true
   ```
4. Optional: set up Discord alerts by adding webhook links in `plugins/NevusVoice/config.yml`, then check they work with
   `/nevusvoice discordtest all`.

**Trying it out safely.** In `config.yml`, set `moderation.dry-run: true`. NevusVoice will then report problems to
staff but never mute anyone. Switch it back when you're happy with how it behaves.

**Server with no internet?** Set `speech-to-text.auto-download: false` and put the files in place yourself. The
console tells you exactly which files and where to get them.

## Using it

The main command is `/nevusvoice`, or `/nv` for short.

### Commands for staff

| Command | What it does | Permission |
|---|---|---|
| `/nv vcmute <player> <duration> [reason]` | Mutes a player's voice. Durations look like `30m`, `2h`, `1d` or `perm`. Works on offline players. | `nevusvoice.vcmute` |
| `/nv unvcmute <player>` | Removes a voice mute. | `nevusvoice.vcmute` |
| `/nv mutes` | Lists everyone currently muted. | `nevusvoice.vcmute` |
| `/nv offenses <player> [reset]` | Shows a player's offence count, or resets it. | `nevusvoice.vcmute` |
| `/nv history <player> [lines]` | Shows what a player recently said. | `nevusvoice.report.review` |
| `/nv alerts` | Turns your own in-game alerts on or off. | `nevusvoice.alerts` |
| `/viewreport <id or player> [minutes]` (`/vr`) | Opens a report, or a player's recent speech, as a book. | `nevusvoice.report.review` |
| `/reportinbox [review <id>]` (`/rinbox`, `/vreports`) | Lists open reports and marks them as reviewed. | `nevusvoice.report.review` |

### Commands for admins

| Command | What it does | Permission |
|---|---|---|
| `/nv reload` | Applies changes you made to the config files, without restarting. | `nevusvoice.reload` |
| `/nv stats` | Shows how the plugin is doing: speed, queue, dropped audio and incidents. | `nevusvoice.stats` |
| `/nv test <text>` | Checks some text against your rules, which is handy when editing word lists. | `nevusvoice.admin` |
| `/nv discordtest [all, flag, mute or report]` | Sends a test message to your Discord webhooks. | `nevusvoice.admin` |

### Command for players

| Command | What it does | Permission |
|---|---|---|
| `/reportvoice <player> [minutes]` (`/rvoice`) | Reports a player's recent voice chat to staff, with a recording. | `nevusvoice.report` (everyone by default) |

### Permissions

| Permission | Who has it by default | What it's for |
|---|---|---|
| `nevusvoice.admin` | Operators | Everything above except bypass. |
| `nevusvoice.bypass` | Nobody | Players with this are never listened to or muted. |
| `nevusvoice.alerts` | Operators | Receiving in-game alerts. |
| `nevusvoice.report` | Everyone | Using `/reportvoice`. |
| `nevusvoice.report.review` | Operators | Seeing reports, `/viewreport`, `/reportinbox` and `/nv history`. |

## How a mute happens

1. A player speaks. When they stop, NevusVoice turns that sentence into text.
2. The text is checked against your rules. Each rule either:
   - **flags** the player, which alerts staff but doesn't count towards a mute, or
   - **mutes**, which adds points towards an automatic mute. Serious rules add more points.
3. If a player collects enough points in a short time, they are muted. The length comes from the **mute ladder**:
   a first offence gets a short mute and repeat offences get longer ones. If they are below the limit, they get a
   warning instead.
4. Staff are alerted either way, and the mute is remembered even if the player leaves or the server restarts.

A few things to know:

- Neutral words are not blocked, and everyday words that merely contain a bad word, such as "cocktail", are allowed.
- Rules look at the words, not the meaning. They can't tell a quote or a joke from a real insult, so staff should
  check the context in alerts.
- Muted players see a message above their hotbar telling them how long the mute lasts. You can change this text in
  `messages.yml`.

## Customising

All files are in `plugins/NevusVoice/`.

| File | What it's for |
|---|---|
| `config.yml` | Every setting, with explanations inside the file. |
| `messages.yml` | All the messages players and staff see. |
| `wordlist.txt` | Your list of swear words and words that cause a mute. Wildcards such as `word*` are allowed. |
| `rules.yml` | More advanced rules, such as phrases and an "always allowed" list. |
| `recordings/` | Saved recordings used as evidence. Old ones are deleted automatically based on age and size. |
| `data/` | Active mutes and reports. |
| `models/` and `natives/` | The downloaded speech recognition files. |

**Updating:** NevusVoice never overwrites your `wordlist.txt` or `rules.yml` when you install a new version, so you
won't get new default rules automatically. Back them up before comparing them with the newest defaults. Use `/nv reload`
after editing files. Restart the server after changing the plugin file itself.

## Troubleshooting

- **Nothing is happening.** Check the console for `Speech engine ready`. Until it appears, the first download is still
  running.
- **Someone isn't being picked up.** Make sure they don't have `nevusvoice.bypass`.
- **Staff see no alerts.** Give them `nevusvoice.alerts`, and ask them to run `/nv alerts` in case they turned them off.
- **Audio is being dropped, or the server feels slow.** Run `/nv stats` and look at *Speed* and *dropped*. Choose a
  lighter speech model, or see the technical section below.

---

## Technical details

This section is for server administrators and developers.

### Speech models

Speech recognition runs through [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) with Silero VAD to ignore
non-speech. Set `speech-to-text.model` to choose:

| Model | Notes | Download |
|---|---|---|
| NVIDIA Parakeet TDT 0.6B (default) | Fast on CPU | about 670 MB |
| `whisper-small-en` | Lowest CPU cost | 376 MB |
| `whisper-turbo` | Highest accuracy, needs the most CPU and RAM | 1.04 GB |

Whisper is configured for English. Model memory is outside the Java heap, so measure server RSS under load. The first
start also downloads the native libraries for your platform (about 9-13 MB, checked against a pinned SHA-256). Models
are downloaded from Hugging Face, verified by SHA-256, and resumed if interrupted. Supported platforms are Linux
(glibc), Windows and macOS on x64 or arm64.

### Detection

- Word lists use `[EN-PROFANITY]` (flag) and `[EN-MUTE]` (mute) sections.
- `rules.yml` adds phrases, wildcards, regex, weights and an allowlist. The English defaults include over 380
  words and phrases and 18 advanced rules. Spelled-out letters ("k y s") and hyphenated compounds are matched.
- Child exploitation requests and offers have weight 3. Sexual coercion, explicit sexual threats and some other
  severe rules have weight 2. Short ambiguous abbreviations are not standalone terms.
- Utterances end when push-to-talk is released or after `audio.silence-timeout`. Long speech is split at its quietest
  point. Quiet speech is boosted with `speech-to-text.input-gain` (target −20 dBFS, maximum +40 dB). The capture
  floor is `audio.min-level-dbfs: -65`.
  `audio.ignore-whispers` refers to the voice chat whisper key, not physical whispering.
- Mute enforcement is instant in-process and mirrored as temporary LuckPerms `voicechat.speak` deny nodes. Offence
  history is stored in LuckPerms meta.
- Muted-player messages use `player.muted-actionbar`, `player.muted-permanent-actionbar` and
  `player.no-speak-permission-actionbar` in `messages.yml`; `mute.notify-player` toggles them. Simple Voice Chat
  checks `voicechat.speak` before firing `MicrophonePacketEvent`, so NevusVoice replaces only its no-speak status
  message through an adapter tested against SVC 2.6.24. If that interface changes, the plugin logs it and keeps the
  original message and mute enforcement.

### Performance and threading

```
SVC packet thread ── O(1) ──▶ per-player lock-free inbox ──▶ decode pool: Opus → 48 kHz → 16 kHz (FIR), segmentation
   └ mute check (map lookup) → cancel packet                         │ bounded, per-player-fair queue (drop-oldest)
                                                                     ▼
                     STT workers: gain → Silero VAD gate → batched recognition on ONE shared model
                                                                     ▼
                     rules → flags/ladder → mute map (+ async LuckPerms) → alerts / Discord / evidence (IO threads)
```

- The server thread only handles join/quit bookkeeping, command parsing and any console commands you configure.
- Simple Voice Chat's packet thread does a map lookup and a lock-free enqueue, nothing more.
- Per-player backlog, the transcription queue, the Discord queue and the audio memory budget are all bounded. Under
  overload, audio is skipped and counted in `/nv stats`.
- Extra workers share one model but need additional working memory. Start with one worker and measure.
- Measured on an Apple M-series CPU with `cpu-threads: 2`, Parakeet transcribes a 3.6 s sentence in about 190 ms after
  the speaker stops, and Whisper Small a 2.86 s clip in about 500 ms. One Parakeet worker with 2 threads covers roughly
  6-8 people talking at the same moment. In a local Turbo comparison, median decoding was about 2.1 s with a peak of
  2.17 GiB, passing 35/37 checks. That is a small regression sample, not an accuracy guarantee. See
  [the comparison report](backups/advanced-model-comparison-20261003/comparison.md).

### Staging Turbo with a code update

Cache Turbo under `plugins/NevusVoice/models/whisper-turbo/` and stage the new JAR in Paper's update folder. To switch
to Turbo only when the new JAR starts, create `plugins/NevusVoice/activate-whisper-turbo` containing just
`whisper-turbo`. On the next startup the plugin selects Turbo, enables input gain and sets
`audio.min-level-dbfs: -65`, keeps a byte-for-byte backup under `plugins/NevusVoice/backups/`, and renames the request
to `activate-whisper-turbo.applied` so it runs once. Remove an unconsumed request to cancel it.

### Building

The repository tracks source and the Gradle wrapper. Build output, the local `run/` server directory, private backups
and secrets are ignored.

```bash
./gradlew build                 # → build/libs/NevusVoice-<version>.jar
./gradlew runServer             # local Paper 26.1.2 test server
./gradlew test -PsherpaIntegration=/path/to/models   # optional end-to-end test with the real engine
```

To replay recorded speech through an installed model:

```bash
./gradlew test \
  -PsherpaIntegration=run/plugins/NevusVoice/models \
  -PsherpaNatives=run/plugins/NevusVoice/natives/sherpa-onnx-1.13.8/osx-aarch64 \
  -PsherpaModel=whisper-turbo \
  -PsherpaRecordings=run/plugins/NevusVoice/recordings
```

Use your platform's native-library directory in place of `osx-aarch64`, and 16 kHz mono PCM WAVs. Use
`-PsherpaModel=whisper-small-en` for Small, or omit it for Parakeet; the WhisperTest bypass test is expected to fail
with Small. Optional fixtures (`WhisperTest.wav`, `WhisperTest.48k.wav`, `clean-whisper.wav`, `test.wav`) are described
in `SherpaOnnxEngineIT`.

## Licences and attribution

- NevusVoice code: [MIT](LICENSE), © 2026 Daniel Millar.
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx): Apache-2.0. ONNX Runtime: MIT.
- NVIDIA Parakeet TDT 0.6B v2/v3 model weights: **CC-BY-4.0**. © NVIDIA, used unmodified through the
  [sherpa-onnx export](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8).
- OpenAI Whisper model weights: **MIT**, used through the
  [sherpa-onnx Whisper Small English export](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small.en) and
  [Whisper Turbo export](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo).
- Silero VAD: MIT.
