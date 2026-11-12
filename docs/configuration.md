# Configuration

For operators. Setup, commands and permissions are in the [README](../README.md).

## How a mute happens

1. A player speaks. When they stop, VoiceWarden turns that sentence into text.
2. The text is checked against your rules. Each rule either:
   - **flags** the player, which alerts staff but doesn't count towards a mute, or
   - **mutes**, which adds points towards an automatic mute. Serious rules add more points.
3. If a player collects enough points in a short time, they are muted. The length comes from the **mute ladder**:
   a first offence gets a short mute and repeat offences get longer ones. If they are below the limit, they get a
   warning instead when `moderation.warn-below-threshold` is on.
4. Staff are alerted either way, and the mute is remembered even if the player leaves or the server restarts.
   New mutes store the identity of their LuckPerms mirror, so they can still be cleaned up after a config change.
   Mutes saved by an older version have no stored identity, so cleanup assumes your current config. If you changed the
   mute permission or context before upgrading, remove the old LuckPerms entry by hand.
   An unmute completes only after that cleanup succeeds. An expired mute is no longer enforced, but its record is kept
   until cleanup succeeds; a failed cleanup is retried on the expiry sweep and at startup, or by unmuting.

You choose how many mute-rule hits within a time window trigger a mute (`moderation.flag-threshold` and
`moderation.flag-window`). The default threshold is 1, so the first hit mutes. A muted player can also be stopped from
hearing others if you want.

A few things to know:

- Neutral words are not blocked, and everyday words that merely contain a bad word, such as "cocktail", are allowed.
- Rules look at the words, not the meaning. They can't tell a quote or a joke from a real insult, so staff should
  check the context in alerts.
- Muted players see a message above their hotbar telling them how long the mute lasts. You can change this text in
  `messages.yml`.

## Customising

All files are in `plugins/VoiceWarden/`.

| File | What it's for |
|---|---|
| `config.yml` | Every setting, with explanations inside the file. |
| `messages.yml` | All the messages players and staff see. |
| `wordlist.txt` | Your list of swear words and words that cause a mute. Wildcards such as `word*` are allowed. |
| `rules.yml` | More advanced rules, such as phrases and an "always allowed" list. |
| `recordings/` | Saved recordings used as evidence. Old ones are deleted automatically based on age and size. |
| `data/` | Active mutes and reports. If a mutes or reports file is corrupt, it is moved next to the original with a `.corrupt-<UUID>` suffix before fresh state is started. If it can't be preserved, writes are suspended. |
| `models/` and `natives/` | The downloaded speech recognition files. |

## Updating

VoiceWarden never overwrites your `wordlist.txt` or `rules.yml` when you install a new version, so you won't get new
default rules automatically. Back them up before comparing them with the newest defaults. Use `/vw reload` after
editing files. Restart the server after changing the plugin file itself. Changing `audio.decode-threads` needs a
server restart to apply, and `/vw reload` tells you so.

## Disabling and shutdown

Setting `enabled: false` stops voice capture, drops queued utterances and discards results from transcription already
running (a native call may still finish). Existing mutes stay enforced. `discord.max-queue-size` applies on
`/vw reload`.

On shutdown, VoiceWarden finishes running transcription and saves its data in the background, so the Minecraft thread
is not blocked. Native or disk work can delay the process exiting.

## Speech models

Speech recognition runs through [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) with Silero VAD to ignore
non-speech. Set `speech-to-text.model` to choose:

| Model | Notes | Download |
|---|---|---|
| NVIDIA Parakeet TDT 0.6B (default) | Fast on CPU | about 670 MB |
| `whisper-small-en` | Lower CPU cost than Turbo; accuracy depends on the microphone | 376 MB |
| `whisper-turbo` | Highest accuracy, needs the most CPU and RAM | 1.04 GB |

Whisper is configured for English. Model memory is outside the Java heap, so measure server RSS under load. The first
start also downloads the native libraries for your platform (about 9-13 MB, checked against a pinned SHA-256). Models
are downloaded from Hugging Face, verified by SHA-256, and resumed if interrupted. Supported platforms are Linux
(glibc), Windows and macOS on x64 or arm64.

### Offline servers

Set `speech-to-text.auto-download: false` to disable all automatic downloads, both the speech model and the native
libraries. Once you have put the required files for your platform in place, the console gives the paths and download
links for anything still missing.

### Staging Turbo with a code update

Cache Turbo under `plugins/VoiceWarden/models/whisper-turbo/` and stage the new JAR in Paper's update folder. To switch
to Turbo only when the new JAR starts, create `plugins/VoiceWarden/activate-whisper-turbo` containing just
`whisper-turbo`. On the next startup the plugin selects Turbo, enables input gain and sets
`audio.min-level-dbfs: -65`, keeps a byte-for-byte backup under `plugins/VoiceWarden/backups/`, and renames the request
to `activate-whisper-turbo.applied` so it runs once. Remove an unconsumed request to cancel it.
