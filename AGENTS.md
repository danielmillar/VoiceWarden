# VoiceWarden

VoiceWarden is a Paper plugin (26.1 and newer) that moderates Simple Voice Chat on the server itself. It transcribes
speech locally with sherpa-onnx, matches it against the operator's word lists and rules, then warns, mutes and alerts
staff. It ships as one jar; Simple Voice Chat is required and LuckPerms is optional. The Java package is
`dev.danielmillar.voicewarden`.

## What we never compromise on

A change that weakens one of these needs the developer's sign-off first.

1. **Fully local.** Audio and transcripts never leave the server, except what the operator sends to their own Discord
   webhooks. No cloud speech service.
2. **Quiet hot threads.** Simple Voice Chat's packet thread does O(1) work per packet: a map lookup, maybe a cancel,
   an enqueue. Decoding, transcription, database writes and HTTP run on VoiceWarden's own threads, never on the
   packet thread or the server thread.
3. **A mute means silence.** A muted player is never heard, with or without LuckPerms. Keep mute enforcement, audio
   queue ordering and the asynchronous pipeline as they are unless the task changes them.
4. **Operator data survives updates.** Configuration, downloaded models, recordings, mutes, offence counts and reports
   carry across every update.

## A note from Daniel

I like ambitious ideas, simple systems, and software that feels obvious. Do not keep complexity because it exists or
add machinery because it looks impressive. Understand the real constraint, then build the smallest model that makes
the correct behaviour unsurprising. Build what the request needs and list follow-ups instead of building them.

These are good defaults, not hard rules. My stated preference overrides anything here. If a rule fights the task, say
so and get my sign-off before breaking it.

## A small glossary

- **you** means the agent reading this file and changing VoiceWarden.
- **developer** means Daniel, who you are talking to.
- **player** means someone playing on the server.
- **operator** means the person running a server that hosts the plugin: they edit the config files and run the admin
  commands.
- **staff** means players with VoiceWarden's moderation permissions, who get alerts and mute players.
- **utterance** means one stretch of a player's speech, ended by push-to-talk release or silence, transcribed as one.
- **incident** means one flagged utterance and what VoiceWarden did about it.
- **mute ladder** means the escalating auto-mute durations a player's offence count picks from.
- **mirror** means the optional copy of a mute as LuckPerms deny nodes, so it also applies on other servers.

## The three ways to hurt yourself

1. **Killing by name.** Stop only a process you started, by the PID you captured when you started it. Never `pkill`,
   `killall`, or kill a PID found by matching a name or path: other sessions and servers run on this machine.
2. **Touching live data.** Never point a server, test or script at a live server, its `plugins/VoiceWarden` folder or a
   real LuckPerms database, and never clean one up. Test on paper-mcp's throwaway server (see Test server).
3. **Baking in local paths.** Code that runs on other servers reads paths and hosts from config. Never hard-code one
   from this machine.

## Hit every surface

The most common defect is a change that works on the path you tested and is missing everywhere else. Before calling
work done, walk this list and say which entries applied:

- **Entry points.** A staff action is usually a `/vw` subcommand, and sometimes also the click action on an in-game
  staff alert. Players report with `/reportvoice`; staff review with `/viewreport` and `/reportinbox`.
- **Permissions.** A new command has its permission node and default in `paper-plugin.yml`, and
  `voicewarden.admin` includes it where it should.
- **Text.** New chat, action bar and dialog text lives in `messages.yml` as MiniMessage, with every placeholder
  documented in its comment, and follows the chat style guide.
- **Config.** A new setting has its comment in `config.yml`, its validation in `ConfigLoader`, and works after
  `/vw reload` (or says it needs a restart).
- **Persistence.** New saved state lives in `data/voicewarden.db`, survives a restart and a crash, and is written from
  the same atomic update that changes it in memory.
- **Reverse states.** A way in needs the way out and a way to see it: a mute needs an unmute, an expiry and
  `/vw mutes`. A one-way door is a bug.
- **Other plugins.** Works without LuckPerms and with it (the mirror, offline name lookups and bypass checks). Discord
  output goes through `DiscordWebhookService`.
- **Docs.** Check whether the change makes existing guidance wrong. Apply the [documentation rules](#documentation)
  before adding anything.

## Test server

- The only test server is paper-mcp's: `paper_start_server` builds the plugin and runs it on a fresh Paper 26.3
  server in `.paper-mcp/server`, with Simple Voice Chat from `paper-mcp.json`. Add LuckPerms there
  (`"luckperms:v5.5.71-bukkit"` under `modrinth`) to test the mirror. Load the `minecraft-verify` skill first.
- The first start downloads the speech model (about 600 MB). Set `enabled: false` in the test server's
  `plugins/VoiceWarden/config.yml` when the test does not need speech; mutes are still enforced.
- Stop what you start: `paper_stop_server`, `paper_despawn_bot`, `paper_stop_game`.

## Verifying

- Run `./gradlew build` before you push. It builds the jar and runs every test. CI runs the tests on each supported
  Paper version (`.github/workflows/build.yml`) and `./gradlew build` on the oldest; its **Build and test** check is
  required.
- While you iterate, run `./gradlew test --tests <class>`.
- The real speech engine is tested only on request: `./gradlew test -PsherpaIntegration=<models>` (see
  `CONTRIBUTING.md`).
- Proof depends on the bug. A logic, state or persistence bug gets the fewest tests that fail on `main` for that
  reason, in the existing test class where one fits, then the fix. A purely visual chat or action bar change gets
  before and after renders, not a test. Behaviour the unit tests cannot show gets a run on the test server.
- A test proves behaviour a player, operator or another system relies on:
  - Use the fakes in `testutil/Fakes` (`Fakes.config`, `Fakes.database` for a real SQLite file, `Fakes.Executor` for
    queued work) rather than new mocking machinery.
  - Assert the effect (mute state, stored rows, offence count, service result), never layout or wording. A refusal is
    checked by the state it left unchanged.
  - Do not test what the compiler or records guarantee, or mirror the calls made on a fake.
  - No real time or sleeps: latches, futures and `Fakes.Executor`.
  - One test per path. Cover every rejection and reverse state.
  - Extend an existing test before adding one, and test at the lowest layer that fails on `main`, once.
  - In review, ask for a missing test only when it would catch a real regression.
- A merged change is not evidence it ran on Paper. Say in the PR what still needs a Paper or client check.
- Release only when asked (`CONTRIBUTING.md`, Releasing).

## Pull requests

- Never make a PR unless the developer explicitly asks you to.
- `main` is protected: the **Build and test** check must pass, the branch must be up to date, review conversations
  must be resolved and commits must be signed. Only the developer merges. Never bypass or weaken these rules.
- Conventional commit titles, plain language: `fix(voice): muted players no longer reach the queue`.
- Body: the problem in a sentence or two, then how you fixed it, then how you verified it. End with the model and
  harness that did the work.
- Chat, action bar and Discord message changes need before and after renders. Motion or timing needs a short video.
- Upload PR evidence to the image host (`pr-evidence`). Never commit PR-only screenshots or assets.
- One request is one PR. Split it only when the developer asks.
- When babysitting: call `watch_pull_request` and end your turn. Do not poll or sleep. Verify each review finding
  against the source, fix real ones, dismiss false positives with a written reason.

## Documentation

Most code changes do not need a documentation change. Agents can read the code.

- `docs/architecture.md` is for decisions and their reasons, the threading model, and traps that are hard to find in
  the source. `docs/internals/` holds the chat style guide. Before adding a paragraph, ask what a maintainer would get
  wrong without it. If reading the code answers the question, leave it out.
- Do not document every feature, list config keys and their defaults (every key carries a comment in `config.yml`),
  enumerate fields or methods, narrate control flow, keep file catalogs, or append PR summaries.
- A local reason the code cannot show goes in a short comment beside it. Use `docs/architecture.md` when the reasoning
  spans several parts of the plugin.
- When a documented decision or constraint changes, rewrite or remove the affected text. Do not append another
  account of the new behaviour.
- `README.md` and `docs/configuration.md` help operators run the plugin: requirements, setup, commands and
  permissions, what a major feature does for them, upgrade notes and anything unintuitive. Keep commands, permissions
  and plugin names in line with the code.
- `CONTRIBUTING.md` holds the developer's build, CI and release steps.

## Plans and work artifacts

- Do not commit implementation plans, research or benchmark notes, TODO lists, status trackers or agent scratch
  files. Keep temporary material outside the worktree.
- A merged PR is the record of the work. Do not keep a second checklist in the repository.

## How it works

Simple Voice Chat hands every microphone packet to `VoiceChatBridge`, which drops packets from muted players and
queues the rest. `AudioIngestService` decodes Opus per player and cuts utterances. `TranscriptionService` runs them
through one shared sherpa-onnx model on its worker threads; the model and native libraries download on first start.
`ModerationService` matches each transcript against the rules and drives the flag window, the mute ladder and
`MuteService`. Mutes, offence counts and reports live in memory for the hot paths and are written to SQLite
(`data/voicewarden.db`, through `Database`'s single thread). LuckPerms, when installed, only mirrors mutes, checks
offline permissions and resolves names. Alerts, Discord webhooks and evidence recordings run on IO threads.

## Where code lives

All under `src/main/java/dev/danielmillar/voicewarden/`:

- `voice/` - the Simple Voice Chat adapter, per-player sessions and the muted-player notices.
- `audio/` - PCM buffers, levels, resampling and WAV encoding.
- `stt/` - sherpa-onnx engine, model and native downloads, the transcription queue.
- `moderation/` - rules (`rules/`), flags, offences, incidents, staff actions, transcript buffer and log.
- `mute/` - mute state, enforcement and the mirror interface. `luckperms/` - the only code that names LuckPerms.
- `placeholderapi/` - the optional PlaceholderAPI expansion, the only code that names PlaceholderAPI.
- `report/` - `/reportvoice` and the report inbox. `evidence/` - recordings on disk.
- `notify/` - in-game staff alerts and Discord webhooks. `command/` - Brigadier commands.
- `storage/` - the SQLite database. `config/` - config, messages and durations. `concurrent/`, `health/`, `player/`,
  `util/` - thread pools, metrics, online players and JSON helpers.
- `src/main/resources/` - `paper-plugin.yml`, `config.yml`, `messages.yml`, `rules.yml`, `wordlist.txt`.

## Taste

- Extend the existing owner of a feature's rules and state before adding a service, class or abstraction.
- Keep adapters thin. Command and event handlers decode input, call one service and map errors.
- For a hot path, name the unit of work and what bounds it: packets, utterances, queued segments, database writes or
  webhook messages. Reuse the existing queues, executors and caches.
- Inferred types over annotations. No raw or unchecked casts.
- Default to no comment. Write one only for an external constraint or an invariant the code cannot show.
- Storage goes through `Database`: services keep state in memory, load it at startup, and submit writes from inside
  the map update that changes it. No SQL on the packet or server thread, no connection pool, no ORM.
- Only `luckperms/` may import `net.luckperms`, and only after LuckPerms is known to be installed, so the plugin still
  starts without it.
- Text a player or operator sees follows the [chat style](docs/internals/chat-style.md). Run the `style-review` skill
  before opening a PR that changes text.

## Additional tips

- Read a library's source instead of guessing: Paper, Adventure, Simple Voice Chat, LuckPerms and sherpa-onnx jars
  are in the Gradle cache at the versions the build uses. Never edit or import from a copy.
- Paper bundles the SQLite driver, so it is `compileOnly`. Do not shade it.
