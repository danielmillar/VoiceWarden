# Repository instructions

This repository contains VoiceWarden, a Paper plugin for fully local voice chat moderation using Simple Voice Chat
and LuckPerms. The Java package is `dev.danielmillar.voicewarden`.
Read these instructions before changing code or publishing Git changes.

## GitHub rules

`main` is protected. These rules apply to administrators as well as other contributors:

- Make changes on a feature branch and submit a pull request targeting `main`.
- Never push directly to `main`, force-push it, or delete it.
- Do not bypass, disable or weaken repository rules to complete a task.
- The required GitHub Actions check is **Build and test**. It must pass before merging.
- The pull request branch must be up to date with `main` before merging.
- Every review conversation must be resolved before merging.
- Commits entering `main` must have verified signatures. Keep commit signing enabled.
- Only **danielmillar** may merge, through a pull request. Agents should prepare the PR and leave it unmerged
  unless danielmillar explicitly authorises merging in the current task.

Treat these rules as repository policy. Check GitHub's live rules before a merge; this file
does not override GitHub's current requirements. Do not treat a zero required approval count as permission to
merge without the user's instruction.

## Documentation and repository communication

- Use **Sonnet 5.5** with effort set to **Low** to write or update any user-facing documentation or explanatory copy,
  including README content, setup and upgrade guides, feature descriptions, command and permission references,
  and release notes.
- Also use **Sonnet 5.5** with **Low** effort for commit messages, pull request titles and descriptions,
  issue titles and descriptions, and review comments or other repository communication.
- Do not add AI attribution, generated-by footers, session links or AI `Co-Authored-By` trailers to commit messages
  or pull requests, including text returned by delegated agents. Preserve attribution to human contributors.
- Review the resulting text against the implementation and validation results for accuracy before publishing it.

## Working principles

These are defaults, not hard rules. The developer's stated preference overrides this file. If a rule fights the task,
say so and get the developer's sign-off before breaking it.

I like ambitious ideas, simple systems, and software that feels obvious. Do not preserve complexity just because it
already exists. Do not introduce machinery because it looks architecturally impressive. Understand the real
constraint, then find the smallest model that makes the correct behaviour unsurprising. Measure twice, cut once,
and do not build what is not needed yet.

### Glossary

- **You / agent**: the AI coding agent working in this repository.
- **User / developer**: the human directing you. In plugin code, "player" means a Minecraft player.
- **Operator**: the person running the Minecraft server that hosts the plugin.

Keep these terms distinct in code, comments, docs and messages.

### Comments and docs

- Comments describe how a function is used and move with that function. Do not narrate each line of behaviour.
- Do not write docs the code already answers. Internal docs are for constraints and traps that the source does not
  reveal. Do not narrate control flow, catalogue files or append PR summaries. Agents can read the code.
- Do not commit agent scratch. Plans, research notes and working files stay outside the worktree. The merged PR is
  the record.

### Process

- Prove the change with the smallest check that covers it, not the whole suite. CI owns repo-wide checks. A test
  that needs a sleep to pass is wrong.
- Never open a PR unless asked. One request is one PR. Use a conventional-commit title in plain language, for example
  `fix(voice): muted players no longer reach the queue`. The body states the problem, then the fix.
- Cover every surface before calling work done: commands, listeners, config, persistence, and reverse states
  (a mute needs an unmute). A one-way door is a bug.
- Never kill processes by name pattern, never touch live server data or a real player database, and never hard-code
  local paths or hosts into code that must run on other servers.

### Taste

- Keep complexity at the adapter boundary. Orchestration stays pure. Command and event handlers decode input, call
  one service and map errors.
- Prefer inferred types over annotations where the language allows. Avoid raw or unchecked casts.

## Validation and scope

- Run `./gradlew build` before submitting implementation changes; it builds the plugin JAR and runs its tests.
- Fix failing checks and report any remaining validation limits accurately.
- Keep voice permission enforcement, audio queue ordering and asynchronous processing unchanged unless the task
  explicitly changes them. Do not block the Minecraft server or voice packet threads with transcription or I/O.
- Preserve VoiceWarden's own configuration, models, recordings, persistent mutes, reports and LuckPerms offence history
  across its updates.
- Keep local server runtime files (`run/`), build output, private backups and secrets out of Git.
- Keep README commands, permissions, plugin names and setup instructions aligned with the implementation.
- Deployment is separate from a commit or PR. Run deployment tasks only when explicitly requested.
