# NevusVoice

Real-time voice chat moderation for **Paper 26.1.2** with **Simple Voice Chat** and **LuckPerms**, running **entirely on
your server**. There's no external processor, no API keys, and no audio leaves the machine (except evidence you choose
to send to Discord).

- **Local speech recognition.** NVIDIA **Parakeet TDT 0.6B**, **Whisper Small** and **Whisper Large-v3 Turbo** run through
  [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (ONNX Runtime, embedded via JNI). Parakeet is the default for fast
  CPU transcription. Use `speech-to-text.model: whisper-turbo` for higher accuracy with a larger CPU/RAM budget,
  or `whisper-small-en` for lower CPU cost. Both catch the reported WhisperTest bypass in recorded-audio tests,
  including quieter copies. Whisper is configured for English transcription. Silero VAD rejects non-speech before decoding;
  recognition can still make mistakes, so test your microphones and review incidents when tuning moderation.
- **Detection.** Word lists (`wordlist.txt`, compatible with the original plugin's `[EN-PROFANITY]`/`[EN-MUTE]` format)
  plus `rules.yml`, which adds phrases, wildcards, regex and an allowlist. The English defaults include over 380
  words/phrases and 18 advanced rules for harassment, threats, hate incitement, obfuscated slurs, pornography,
  sexual violence, child exploitation, coercion, advertising and doxxing. Spelled-out letters ("k y s") and hyphenated
  compounds are matched too.
- **Automatic mutes.** A flag threshold and window, warnings, and an escalating **mute ladder** whose offense history is
  stored in LuckPerms meta, so it follows players across restarts and servers. Mutes are enforced instantly in-process
  and mirrored as temporary LuckPerms `voicechat.speak` deny nodes, which persist and apply network-wide. Muted players
  can optionally be blocked from hearing too.
- **Staff tooling.** In-game alerts with highlighted words, earlier context, location and click-to-mute. Three Discord
  webhooks (flags, mutes, reports) can attach the flagged audio as a WAV, and there are custom console commands, an
  evidence archive with retention, `/reportvoice` voice reports, and transcript history.
- **Performance first.** Nothing runs on, or waits for, the server thread. Work is batched, queues are bounded and
  fair, and there's built-in health monitoring and automatic cleanup.

## Requirements

| | |
|---|---|
| Server | Paper **26.1.2**, Java **25+** |
| Plugins | [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) 2.6.x, [LuckPerms](https://luckperms.net) 5.5+ |
| OS / CPU | Linux (glibc), Windows or macOS on x64 or arm64 |
| Memory | Model memory is outside the Java heap. Allow at least **3 GB of headroom** for Turbo and measure server RSS under load. |
| Disk | about 700 MB for Parakeet, 376 MB for Whisper Small or 1.04 GB for Turbo, downloaded once |

## Installation

1. Put `NevusVoice-<version>.jar` in `plugins/` next to Simple Voice Chat and LuckPerms.
2. Start the server. On first start NevusVoice downloads, in the background:
   - the speech engine native libraries for your platform (~9-13 MB, from the official sherpa-onnx GitHub release,
     verified against a pinned SHA-256);
   - the selected model (~670 MB for Parakeet, 376 MB for Whisper Small or 1.04 GB for Turbo, from Hugging Face, SHA-256 verified,
     and resumed if interrupted).

   The server stays fully playable while this happens. Watch the console for `Speech engine ready`.
3. Grant staff alerts: `/lp group moderator permission set voicesentinel.alerts true`
4. Optionally set up Discord webhooks in `config.yml` and test them with `/nevusvoice discordtest all`.

**Offline servers.** Set `speech-to-text.auto-download: false` and place the files manually. The exact URLs are printed in
the error message.

**Migrating from VoiceSentinel.** Replace the old plugin jar with `NevusVoice-<version>.jar` and restart the server.
On first start, NevusVoice moves `plugins/VoiceSentinel/` to `plugins/NevusVoice/`
when the new directory is absent or empty. Configuration, models, recordings and persistent mutes are carried over.
Existing `voicesentinel.*` permission nodes and LuckPerms offense history remain compatible. The main command is now
`/nevusvoice`; reporting commands keep their existing names. No processor or licence key is needed.

**Staging Turbo with a code update.** Cache Turbo under `plugins/NevusVoice/models/whisper-turbo/` and stage the new JAR
in Paper's configured update folder. To select Turbo only when the supporting JAR starts, create
`plugins/NevusVoice/activate-whisper-turbo` containing just `whisper-turbo`. The running plugin's config remains compatible.
On the next startup, the new plugin selects Turbo, enables input gain (−20 dBFS target, +40 dB maximum) and sets
`audio.min-level-dbfs: -65`. It merges those five settings into the latest config, keeps a private, byte-for-byte backup
under `plugins/NevusVoice/backups/`, and renames the request to `activate-whisper-turbo.applied` so it runs only once.
Other settings and comments are preserved. Remove an unconsumed request to cancel the staged selection.

## Commands

Main command: `/nevusvoice`, with alias `/nv`.

| Command | Permission | Description |
|---|---|---|
| `/nv reload` | `voicesentinel.reload` | Reloads all files without blocking the server. A changed model is loaded in the background and swapped in with no gap. |
| `/nv stats` | `voicesentinel.stats` | Engine state, active speakers, queue, latency (avg/p95), speed (× real time), drops, incidents, Discord. |
| `/nv vcmute <player> <duration> [reason]` | `voicesentinel.vcmute` | Voice-mutes a player. Durations: `30m`, `2h`, `1d`, `perm`. Works for offline players. |
| `/nv unvcmute <player>` | `voicesentinel.vcmute` | Removes a voice mute, both the local one and the LuckPerms nodes. |
| `/nv mutes` | `voicesentinel.vcmute` | Lists active voice mutes. |
| `/nv offenses <player> [reset]` | `voicesentinel.vcmute` | Shows or resets a player's mute-ladder offense count. |
| `/nv history <player> [lines]` | `voicesentinel.report.review` | Shows a player's recent transcribed speech. |
| `/nv alerts` | `voicesentinel.alerts` | Toggles your in-game alerts. |
| `/nv test <text>` | `voicesentinel.admin` | Runs the rules against some text, for tuning word lists. |
| `/nv discordtest [all\|flag\|mute\|report]` | `voicesentinel.admin` | Sends test payloads to the webhooks. |
| `/reportvoice <player> [minutes]` (`/rvoice`) | `voicesentinel.report` (default: everyone) | Reports a player's recent voice chat to staff, including a recording. |
| `/viewreport <id\|player> [minutes]` (`/vr`) | `voicesentinel.report.review` | Opens a report, or a player's recent speech, as a book. |
| `/reportinbox [review <id>]` (`/rinbox`, `/vreports`) | `voicesentinel.report.review` | Lists open reports and marks them as reviewed. |

| Permission | Default | |
|---|---|---|
| `voicesentinel.admin` | op | All of the below except bypass. |
| `voicesentinel.bypass` | false | Never transcribed, never muted. |
| `voicesentinel.alerts` | op | Receive in-game alerts. |
| `voicesentinel.report` | true | Use `/reportvoice`. |
| `voicesentinel.report.review` | op | Report alerts, `/viewreport`, `/reportinbox`, `/nv history`. |

## How moderation decides

1. Each utterance is transcribed. It ends when the speaker releases push-to-talk or goes quiet for `audio.silence-timeout`,
   and long speech is split at the quietest point.
   VAD rejects audio without speech and preserves the full captured utterance by default. Silence trimming can omit
   quiet first/last words, so `speech-to-text.vad.trim-silence` is an opt-in performance setting. Short-word defaults
   are `audio.min-utterance: 160ms` and `speech-to-text.vad.min-speech: 100ms`.
   Quiet speech is boosted before VAD and transcription using `speech-to-text.input-gain` (enabled by default,
   target −20 dBFS, maximum +40 dB). Gain preserves the recorded evidence and leaves loud speech unchanged.
   The capture silence floor is `audio.min-level-dbfs: -65`; existing configurations with `-50` need that key
   updated to admit quiet whispers. `audio.ignore-whispers` controls the voice chat whisper key, not physical whispers.
2. The transcript is matched against your rules.
   - **FLAG** rules (`[XX-PROFANITY]`, `action: FLAG`) alert staff and run `custom-commands.profanity`.
   - **MUTE** rules (`[XX-MUTE]`, `action: MUTE`) add *flags* equal to the rule's `weight`.
3. When the flags inside `moderation.flag-window` reach `moderation.flag-threshold`, the player is auto-muted. The
   duration comes from `mute-ladder.tiers` for their offense number, or `mute-duration` when the ladder is off. Below
   the threshold the player is warned (if `warn-below-threshold` is on).
4. Use `moderation.dry-run: true` while tuning. Everything is reported, but nobody is punished.

Ambiguous terms and possible speech recognition variants alert staff. Explicit slurs, directed self-harm instructions,
sexual threats and doxxing threats contribute to auto-muting. Neutral identity words are not blocked. In-game combat
threats alert staff; explicit real-world threats can trigger a mute. Some severe rules have weight 2.
Pornography, sexual violence and child-safety terminology alert staff for contextual review. Requests/offers involving
child exploitation material have weight 3; sexual coercion and explicit sexual threats have weight 2. The solicitation
and coercion rules exclude common reporting/prohibition/condemnation wording so safeguarding reports stay review
alerts. Short ambiguous abbreviations such as "CP", "BBC" and "DP" are not standalone terms.
Whole-word matching and allowlist entries protect ordinary words such as "Pakistani", "cocktail", "sniggering" and
"Schwarzenegger". Most phrase rules do not interpret quotation, negation or speaker intent; staff should review context.

Existing installations retain their own `wordlist.txt` and `rules.yml`; rebuilding the jar does not overwrite those
files. Back them up before installing the updated defaults. `/nv reload` applies rule/config edits; restart the server
after updating plugin code.

When a player attempts blocked speech, NevusVoice shows a custom action bar: active mutes include the remaining time,
permanent mutes have their own message, and other `voicechat.speak` denials show a permission notice. Text is configurable
with `player.muted-actionbar`, `player.muted-permanent-actionbar` and `player.no-speak-permission-actionbar` in
`messages.yml`. `mute.notify-player` controls these notices. They run on the player's scheduler, are throttled to once
per second and stop after unmuting, permission restoration, disconnect or shutdown.

Simple Voice Chat checks `voicechat.speak` before firing `MicrophonePacketEvent`. NevusVoice replaces only its
no-speak status message through a narrow adapter for the SVC 2.6.x compatibility interface, tested against the actual
2.6.24 jar. Other voice-chat messages pass through, and shutdown restores the original adapter. If that internal
interface changes, the server logs the incompatibility and retains the original permission message and mute enforcement.

## Performance and threading

```
SVC packet thread ── O(1) ──▶ per-player lock-free inbox ──▶ decode pool: Opus → 48 kHz → 16 kHz (FIR), segmentation
   └ mute check (map lookup) → cancel packet                         │ bounded, per-player-fair queue (drop-oldest)
                                                                     ▼
                     STT workers: gain → Silero VAD gate → batched recognition on ONE shared model
                                                                     ▼
                     rules → flags/ladder → mute map (+ async LuckPerms) → alerts / Discord / evidence (IO threads)
```

- The **server thread** only handles join/quit bookkeeping (O(1)), command parsing, and console commands you configure
  yourself (Bukkit requires those on the main thread).
- **Simple Voice Chat's packet thread** carries all voice traffic, so NevusVoice's handler does a map lookup and a
  lock-free enqueue, nothing more.
- **Bounded everywhere.** Per-player frame backlog, the transcription queue (with a per-player share and a maximum age),
  the Discord queue, and the in-memory audio budget all have limits. Under overload, audio is skipped and counted in
  `/nv stats`; it is never queued without limit.
- **One shared model.** Extra workers do not load another copy of the model, but concurrent inference needs additional
  working memory. Whisper decodes each item in a submitted batch sequentially; start with one worker and measure capacity.

**Measured** on an Apple M-series CPU with `cpu-threads: 2`, Parakeet transcribes a 3.6 s sentence in about 190 ms
after the speaker stops. Whisper Small transcribes the 2.86 s WhisperTest clip in about 500 ms. Measure throughput
on your server when switching models.

In a local Turbo comparison using two CPU threads, median decoding time was about **2.1 s** and peak standalone process
memory was **2.17 GiB**. It passed 35/37 moderation checks, including the actual WhisperTest clip and quiet copies down to
−65 dBFS. The cases derive from only four source utterances, plus noise/silence controls; this is a regression sample,
not a general accuracy or Exchanger capacity guarantee. See [the comparison report](backups/advanced-model-comparison-20261003/comparison.md).

**Tuning.**
- Watch `/nv stats` → *Speed* and *dropped*.
- If audio is being dropped, adjust `cpu-threads` and measure again. Test increased worker concurrency separately,
  including memory usage, before relying on it. Leave CPU and RAM headroom for Minecraft.
- Rough Parakeet capacity: 1 worker with 2 threads covers about 6-8 people talking at the same moment. That is typically
  60-100+ voice users, since people rarely all talk at once.

## Files

| File | Purpose |
|---|---|
| `config.yml` | All settings, documented inline. |
| `messages.yml` | Every message, in MiniMessage. Player text is always inserted as plain text. |
| `wordlist.txt` | `[LANG-PROFANITY]` and `[LANG-MUTE]` word lists. Wildcards are supported: `word*`, `*word`, `*word*`. |
| `rules.yml` | Advanced rules: phrases, regex, weights, allowlist. |
| `data/mutes.json`, `data/reports.json` | Persistent state. |
| `recordings/` | Evidence: JSON metadata plus WAV, with retention by age and size. |
| `models/`, `natives/` | Downloaded speech model and native libraries. |
| `activate-whisper-turbo`, `activate-whisper-turbo.applied` | Optional one-time Turbo startup request and consumed receipt. |

## Building

```bash
./gradlew build                 # → build/libs/NevusVoice-<version>.jar
./gradlew test -PsherpaIntegration=/path/to/models   # optional end-to-end test with the real engine
./gradlew runServer             # local Paper 26.1.2 test server
```

Replay recorded speech through the installed model without downloading another copy:

```bash
./gradlew test \
  -PsherpaIntegration=run/plugins/NevusVoice/models \
  -PsherpaNatives=run/plugins/NevusVoice/natives/sherpa-onnx-1.13.8/osx-aarch64 \
  -PsherpaModel=whisper-turbo \
  -PsherpaRecordings=run/plugins/NevusVoice/recordings
```

Use your platform's native-library directory in place of `osx-aarch64`. The replay check requires speech WAVs in
16 kHz mono PCM; it prints fresh transcripts and verifies that VAD passes every captured sample to recognition.
Silence/noise rejection is tested separately, along with quieter copies and their moderation results. An optional
`WhisperTest.wav` checks the reported bypass; `WhisperTest.48k.wav` supplies its original 48 kHz capture for resampling
and concurrent batch tests. `clean-whisper.wav` checks harmless whispering. Use `-PsherpaModel=whisper-small-en` to
test Small or omit `-PsherpaModel` to test Parakeet;
the reported WhisperTest bypass test is expected to fail with that model. Optional reference tests require the
reference `test.wav` described in `SherpaOnnxEngineIT`.

## Licences and attribution

- NevusVoice code: choose a licence before publishing (none is included yet).
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx): Apache-2.0. ONNX Runtime: MIT.
- NVIDIA Parakeet TDT 0.6B v2/v3 model weights: **CC-BY-4.0**. © NVIDIA, used unmodified through the
  [sherpa-onnx export](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8).
- OpenAI Whisper model weights: **MIT**, used through the
  [sherpa-onnx Whisper Small English export](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small.en) and
  [Whisper Turbo export](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo).
- Silero VAD: MIT.
