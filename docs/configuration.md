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
   VoiceWarden always enforces the mute itself. If LuckPerms is installed and `mute.luckperms.enabled` is true, the
   mute is also mirrored as a temporary LuckPerms deny node, so it applies on other servers sharing your LuckPerms
   storage. Without LuckPerms that setting is ignored and one line is logged at startup.
   Mutes store the identity of their LuckPerms mirror, so they can still be cleaned up after a config change.
   An unmute completes only after that cleanup succeeds. An expired mute is no longer enforced, but its record is kept
   until cleanup succeeds; a failed cleanup is retried on the expiry sweep and at startup, or by unmuting.

Without LuckPerms, two limits apply. Commands can only target players who have joined this server before. And an
offline player's `voicewarden.bypass` permission can't be checked, so staff can voice-mute an offline player who has
bypass.

You choose how many mute-rule hits within a time window trigger a mute (`moderation.flag-threshold` and
`moderation.flag-window`). The default threshold is 1, so the first hit mutes.

By default a mute blocks all of a player's voice. Under `mute.blocks` you can instead let muted players keep talking
nearby, whisper, or talk in some voice chat groups, and `mute.allowed-groups` names groups they can always talk in
(for example a `staff-appeal` group). A muted player can also be stopped from hearing others.

A few things to know:

- Neutral words are not blocked, and everyday words that merely contain a bad word, such as "cocktail", are allowed.
- Rules look at the words, not the meaning. They can't tell a quote or a joke from a real insult, so staff should
  check the context in alerts.
- Muted players see how long their mute lasts above their hotbar for the whole mute, and a chat
  reminder at most every 30 seconds when they talk and nobody hears them. Choose this with `mute.feedback` and
  change the text in `messages.yml`.
- If a mute lets the player talk anywhere, the LuckPerms mirror no longer denies `voicechat.speak`, because that
  permission blocks all speech. The mute still applies on this server, but not on other servers.
- Players name their own voice chat groups, so a muted player can create a group with an allowed name. Only list
  groups that staff watch.

## Consent

`consent.mode: notice`, the default, tells players on join that voice chat is moderated (`privacy.join-message`), and
joining counts as consent.

With `consent.mode: confirm`, each player is asked to accept or deny in a menu when they join (or when they respawn,
if they joined dead). Until they answer, the menu can't be closed with Escape. Until a player accepts, VoiceWarden never decodes, transcribes, buffers or records their voice.
Players see their answer and change it with `/voiceconsent`, and staff see it in `/vw history <player>`.

- **Denied or not answered yet.** By default (`consent.speak-without-consent: false`) the player can listen but
  nobody hears them, so denying is not a way to speak unmoderated. Set it to `true` to let them be heard while
  VoiceWarden ignores their voice. A voice mute still applies either way. A blocked player sees an action bar
  pointing to `/voiceconsent`, even when `mute.notify-player` is false.
- **Reminder.** Under `stay`, a player who denied gets a chat reminder each time they join.
- **`consent.on-deny`.** With `stay` the player keeps playing and isn't asked again on join. With `kick` they are
  disconnected and asked again next time they join.
- **`consent.rules-version`.** Raise it when your rules change. Players are asked again on their next join, and
  online players are asked straight away after `/vw reload`. Until they accept the new version, their voice is
  treated as not consented.
- Everyone is asked, including players with `voicewarden.bypass`. Until they accept, they are blocked like anyone
  else.
- Withdrawing consent stops processing from that moment. What was transcribed or recorded before stays until it ages
  out as usual.

## Placeholders

With PlaceholderAPI installed, `%voicewarden_muted%`, `%voicewarden_mute_remaining%`, `%voicewarden_mute_reason%`,
`%voicewarden_offenses%` and `%voicewarden_next_mute%` show a player's voice mute status (the README describes each).
The offence count is the one the mute ladder uses, so it drops to 0 once `moderation.mute-ladder.reset-after-days`
have passed since the last automatic mute. They work for offline players too, and are empty where there is no player,
such as in the console.

## Customising

All files are in `plugins/VoiceWarden/`.

| File | What it's for |
|---|---|
| `config.yml` | Every setting, with explanations inside the file. |
| `messages.yml` | All the messages players and staff see. |
| `wordlist.txt` | Your list of swear words and words that cause a mute. Wildcards such as `word*` are allowed. |
| `rules.yml` | More advanced rules, such as phrases and an "always allowed" list. |
| `recordings/` | Saved recordings used as evidence. Old ones are deleted automatically based on age and size. |
| `data/voicewarden.db` | A SQLite database holding voice mutes (and their LuckPerms mirror records), offence counts for the mute ladder, voice reports, and each player's consent answer. Nothing extra needs installing. Offence counts are per server. If the file is unreadable, it is moved aside as `voicewarden.db.corrupt-<UUID>` and a fresh one is created. |
| `models/` and `natives/` | The downloaded speech recognition files. |

## Updating

VoiceWarden never overwrites your `wordlist.txt` or `rules.yml` when you install a new version, so you won't get new
default rules automatically. Back them up before comparing them with the newest defaults. Use `/vw reload` after
editing files. Restart the server after changing the plugin file itself. Changing `audio.decode-threads` needs a
server restart to apply, and `/vw reload` tells you so.

### Upgrading from the JSON storage version

- Existing voice mutes and reports are not carried over: VoiceWarden starts with no mutes and an empty report inbox.
  The old JSON files in `data/` are ignored and can be deleted.
- If you mirrored mutes to LuckPerms, the old deny entries stay in LuckPerms and still silence those players.
  While the mirror is on with the same permission and context settings, `/vw unvcmute <player>` removes them, even
  though it replies that the player is not muted. Otherwise remove them in LuckPerms.
- Offence counts are no longer kept in LuckPerms meta (`voicewarden-offenses`, `voicewarden-last-offense`), and old
  counts are not imported. The mute ladder starts from zero after upgrading, and counts are now per server rather than
  shared across a network.
- Evidence recordings and transcript logs are unchanged.
- `/vw history <player>` now shows a player's voice record (offences, current mute and past mutes) and needs
  `voicewarden.vcmute`. What a player recently said moved to `/vw transcript <player>`, still with
  `voicewarden.report.review`.
- Mute history starts at the upgrade, so mutes lifted before it do not appear in `/vw history`.

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

### Double check

A speech model sometimes "hears" a word in noise or mumbling. If you turn on `speech-to-text.double-check`, a second,
different model (Moonshine v2 Base, about 140 MB download, about 130 MB more RAM) listens again to every clip that hit
a mute rule. A hit is acted on only if both models hear the same rule. Otherwise nobody is warned or muted, the hit
does not count towards `flag-threshold`, and staff get an alert marked unconfirmed. `/vw stats` counts these, and the
Discord message and evidence file record what the second model heard. If the second model can't keep up (more than 32
hits waiting), hits are treated as unconfirmed rather than punished unchecked. Expect about 0.1-0.3 s more before a
mute on a typical clip, and around 1 s for an utterance over 15 s. The second model is English only, so the double check
stays off unless `moderation.languages` is exactly `[en]`. Offline servers need the files under
`plugins/VoiceWarden/models/moonshine-base-en/`; the console lists the links.

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
