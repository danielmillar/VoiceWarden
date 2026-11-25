# VoiceWarden

VoiceWarden is a local voice chat moderation plugin for Paper and Folia 1.21+ servers running Simple Voice Chat or PlasmoVoice. All audio decoding, voice activity detection, and speech recognition run on the Minecraft host inside an isolated engine subprocess, so player voice never leaves your server or touches a third-party API.

Install the plugin, start the server, and VoiceWarden extracts its bundled engine binary, verifies the speech models against pinned SHA-256 hashes, and starts moderating proximity and group voice chat.

## How the pipeline runs

Incoming voice packets are decoded to 16 kHz mono PCM on dedicated worker threads rather than the server tick loop. Before speech recognition touches a frame, a pure-DSP acoustic detector scans the audio for sustained clipping and ear-splitting loudness, catching mic spam and soundboard blasts even when nobody is speaking words.

Utterances that pass the loudness gate cross a local socket into `voicewarden-engine`, a separate low-priority JVM process scheduled at `nice_priority: 10` so speech inference cannot stall gameplay ticks or take down the server. Inside the engine, Silero VAD filters out fan hum, keyboard noise, and mic bumps, sending only utterances that contain human speech to sherpa-onnx.

Single-model speech recognition makes mistakes on noisy microphones, which is why English profiles pair two distinct models. Once the primary recognizer matches a word or rule, VoiceWarden re-checks that exact clip with `whisper-distil-small.en` before warning, muting, or recording a strike. Hits the verifier rejects still alert staff as unconfirmed, leaving innocent players unpunished.

## Capabilities

- **Wordlists and custom rules:** Matches `config/wordlist.txt` with an Aho-Corasick engine and `config/rules.yml` with budgeted regular expressions across mild, moderate, and severe tiers, escalating through configurable strike windows and a growing mute ladder (`5m` to `6h`, capped at `24h`).
- **Acoustic mic-spam protection:** Detects sustained clipped or overdriven audio on the 16 kHz stream and escalates `[warn, mute]` over a rolling 5-minute window, posting the captured clip to Discord.
- **Voice reports with audio clips:** Players run `/reportvoice` to send staff an interactive transcript book (`/viewreport`, `/reportinbox`) and a Discord report card with a playable `.wav` attachment decoded on demand from an ephemeral 45-second rolling Opus buffer.
- **Multi-speaker conversation context:** Tracks nearby speakers within 48 blocks (and anyone sharing a Simple Voice Chat group) using Folia-safe entity tasks, attaching recent surrounding speech to Discord cards and `/viewreport` books so moderators see what led up to a flag.
- **Consent and enforcement:** Runs in `NOTICE` mode (notifies players on join and lets them opt out with `/voiceconsent deny`) or `GUI` mode (opens a chest menu on Java and a Floodgate form on Bedrock). Players who deny consent have both voice transmit and receive blocked until they accept, and raising `consent.rules_version` prompts everyone again.
- **Discord webhooks and shared storage:** Posts Discord Components V2 cards across separate flag, mute, and report webhooks with inline playable `.wav` clips. Stores strikes, mutes, and reports in local SQLite or shared MySQL/MariaDB across a network, with optional LuckPerms mute mirroring and PlaceholderAPI support.

## Speech profiles

Select a profile under `engine.profile` in `config.yml`. Missing models and `silero_vad.onnx` (~0.6 MB) download automatically when `engine.auto_download` is enabled.

| Profile | Primary model | Verifier (Double Check) | Typical RAM |
|---|---|---|---|
| `ENGLISH_ACCURATE` (default) | NeMo Parakeet TDT 0.6B v2 (INT8) | Whisper Distil-Small EN | 1.3–1.6 GB |
| `ENGLISH` | Streaming Zipformer Kroko EN | Whisper Distil-Small EN | 700–950 MB |
| `MULTILANG` | NeMo Parakeet TDT 0.6B v3 (INT8, 25 European languages) | None | 1.2–1.5 GB |
| `MULTILANG_GLOBAL` | Whisper Turbo (99 languages) | None | 1.8–2.2 GB |

## Requirements and installation

VoiceWarden requires Java 21, Paper or Folia 1.21+, and either Simple Voice Chat or PlasmoVoice (`voice_backend: AUTO` picks whichever is installed). Floodgate, LuckPerms, and PlaceholderAPI are optional soft dependencies.

1. Place `VoiceWarden.jar` in your server's `plugins/` folder and start the server once to generate `plugins/VoiceWarden/config.yml`, `config/messages.yml`, `config/wordlist.txt`, and `config/rules.yml`.
2. Review `engine.profile`, `engine.threads`, `consent.mode`, and your Discord webhook URLs in `config.yml`.
3. Run `/vw reload` to apply config or wordlist edits live (changes to engine profile, threads, memory, or Silero VAD restart the engine process automatically).

## Commands and permissions

| Command | Aliases | Purpose | Permission |
|---|---|---|---|
| `/voicewarden reload` | `/vw` | Reload config, messages, wordlists, and rules | `voicewarden.reload` |
| `/voicewarden stats` | `/vw` | Show engine health, latency, and pipeline counters | `voicewarden.stats` |
| `/voicewarden vcmute <player> [duration] [reason]` | `/vw` | Apply a voice mute | `voicewarden.vcmute` |
| `/voicewarden unvcmute <player>` | `/vw` | Remove an active voice mute | `voicewarden.vcmute` |
| `/voicewarden pardon <player>` | `/vw` | Clear a player's strike history | `voicewarden.pardon` |
| `/voicewarden history <player>` | `/vw` | Inspect strikes and past mutes | `voicewarden.history` |
| `/voicewarden mutes` | `/vw` | List active voice mutes | `voicewarden.mutes` |
| `/voicewarden consent <player> [reset]` | `/vw` | Check or reset a player's consent record | `voicewarden.admin` |
| `/voicewarden debug <player>` | `/vw` | Stream live transcripts for a player to your chat | `voicewarden.admin` |
| `/voicewarden discordtest [all\|flag\|mute]` | `/vw` | Send test cards to configured webhooks | `voicewarden.admin` |
| `/voiceconsent [accept\|deny]` | `/vconsent` | Review or update personal voice consent | Everyone |
| `/reportvoice [player] [minutes]` | `/rvoice` | Report a player's recent speech and audio clip | `voicewarden.report` (default true) |
| `/reportinbox [review <id>]` | `/rinbox`, `/vreports` | Browse open reports and mark them reviewed | `voicewarden.report.review` |
| `/viewreport <player> [minutes] \| #<id>` | `/vr` | Open a recent transcript or saved report book | `voicewarden.report.review` |

Grant `voicewarden.alerts` for in-game flag, mic-spam, mute, and report alerts. `voicewarden.admin` includes every staff permission except `voicewarden.bypass`, which disables transcription and moderation for that player and must be granted explicitly.

## Building from source

Build the shaded plugin JAR (which bundles `voicewarden-engine` inside `META-INF/voicewarden-engine.jar.bin`) and run the full check suite with Java 21:

```bash
./gradlew check --continue
./gradlew :voicewarden-plugin:shadowJar
```

The built plugin lands at `voicewarden-plugin/build/libs/VoiceWarden-1.0.0-SNAPSHOT.jar`.

## Repository layout

- `voicewarden-api` — Public events, records, and service interfaces for third-party plugins.
- `voicewarden-core` — Audio decoding, acoustic mic-spam detection, rolling Opus buffers, wordlists, moderation ladders, Discord webhooks, IPC framing, and storage.
- `voicewarden-engine` — Standalone sherpa-onnx + Silero VAD speech engine subprocess and verified model downloader.
- `voicewarden-plugin` — Paper/Folia plugin, Simple Voice Chat and PlasmoVoice adapters, Folia-safe proximity tracking, commands, and consent GUIs.

## License

VoiceWarden is released under the [MIT License](LICENSE).
