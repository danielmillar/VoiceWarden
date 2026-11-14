# Architecture

For developers and curious readers. To build and test, see [CONTRIBUTING.md](../CONTRIBUTING.md). Operator settings are
in [configuration.md](configuration.md).

## Detection

- Word lists use `[EN-PROFANITY]` (flag) and `[EN-MUTE]` (mute) sections.
- `rules.yml` adds phrases, wildcards, regex, weights and an allowlist. The English defaults include over 380
  words and phrases and 18 advanced rules. Spelled-out letters ("k y s") and hyphenated compounds are matched.
- Child exploitation requests and offers have weight 3. Sexual coercion, explicit sexual threats and some other
  severe rules have weight 2. Short ambiguous abbreviations are not standalone terms.
- Utterances end when push-to-talk is released or after `audio.silence-timeout`. Long speech is split at its quietest
  point. Quiet speech is boosted with `speech-to-text.input-gain` (target −20 dBFS, maximum +40 dB). The capture
  floor is `audio.min-level-dbfs: -65`.
  `audio.ignore-whispers` refers to the voice chat whisper key, not physical whispering.
- Mute enforcement is instant in-process and does not need LuckPerms. When LuckPerms is present, mutes are also
  mirrored as temporary `voicechat.speak` deny nodes. Mutes, offence counts and reports are stored in a SQLite
  database (`data/voicewarden.db`).

## Simple Voice Chat adapter

Muted-player messages use `player.muted-actionbar`, `player.muted-permanent-actionbar` and
`player.no-speak-permission-actionbar` in `messages.yml`; `mute.notify-player` toggles them. Simple Voice Chat
checks `voicechat.speak` before firing `MicrophonePacketEvent`, so VoiceWarden replaces only its no-speak status
message through an adapter tested against SVC 2.6.24. If that interface changes, the plugin logs it and keeps the
original message and mute enforcement.

## Performance and threading

```
SVC packet thread ── O(1) ──▶ per-player lock-free inbox ──▶ decode pool: Opus → 48 kHz → 16 kHz (FIR), segmentation
   └ mute check (map lookup) → cancel packet                         │ bounded, per-player-fair queue (drop-oldest)
                                                                     ▼
                     STT workers: gain → Silero VAD gate → batched recognition on ONE shared model
                                                                     ▼
                     rules → flags/ladder → mute map (+ async LuckPerms mirror, if installed) → alerts / Discord / evidence (IO threads)
```

- The server thread only handles join/quit bookkeeping, command parsing and any console commands you configure.
- Simple Voice Chat's packet thread does a map lookup and a lock-free enqueue, nothing more.
- Per-player backlog, the transcription queue, the Discord queue and the audio memory budget are all bounded. Under
  overload, audio is skipped and counted in `/vw stats`.
- Extra workers share one model but need additional working memory. Start with one worker and measure.
- `speech-to-text.double-check` adds a second model with one thread and its own bounded queue, fed only MUTE-rule
  hits, so it never delays or reorders normal transcription. Those hits are decided on that thread once it answers;
  no lock is held while it listens. A hit it can't check (queue full, an error) is unconfirmed, never punished
  unchecked: otherwise flooding the queue would turn the check off. Moonshine v2 returns no text for clips of 10 s or
  more, so longer utterances are heard in overlapping 9 s pieces.
- Measured on an Apple M-series CPU with `cpu-threads: 2`, Parakeet transcribes a 3.6 s sentence in about 190 ms after
  the speaker stops, and Whisper Small a 2.86 s clip in about 500 ms. One Parakeet worker with 2 threads covers roughly
  6-8 people talking at the same moment. These are single-machine measurements, not an accuracy guarantee.

## Licences and attribution

- VoiceWarden code: [MIT](../LICENSE), © 2026 Daniel Millar.
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx): Apache-2.0. ONNX Runtime: MIT.
- NVIDIA Parakeet TDT 0.6B v2/v3 model weights: **CC-BY-4.0**. © NVIDIA, used unmodified through the
  [sherpa-onnx export](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8).
- OpenAI Whisper model weights: **MIT**, used through the
  [sherpa-onnx Whisper Small English export](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small.en) and
  [Whisper Turbo export](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-turbo).
- Silero VAD: MIT.
