---
name: style-review
description: Review the text a branch adds or changes (src/main/resources/messages.yml, Discord payload text in notify/discord/, and text built in code such as /vw check findings, transcript highlights and the report book) against docs/internals/chat-style.md, and report each violation with its fix. Use before opening a pull request that changes text a player, staff member or operator sees, or when asked to check a change's style.
---

# Style review

This is a guard for the text people read, not a general code review. Review only the lines the branch changed and
answer one question: does each changed line of text follow the style note? If the diff does not make a violation
clear, there is no finding.

## Steps

1. List the changed lines, uncommitted work included:
   `git diff --merge-base origin/main -U0 -- '*.yml' '*.java'`, plus `git ls-files --others --exclude-standard` for new
   files. Keep the lines that carry text someone reads:
   - entries of `src/main/resources/messages.yml`;
   - Discord titles, field names and values in `src/main/java/dev/danielmillar/voicewarden/notify/discord/`;
   - text built in code: `/vw check` findings and fixes in `health/SetupCheck.java`, reasons passed into messages,
     `Component` text, dialog, kick and book text.

   Skip code, keys, comments about code, log lines and test files.
2. Read [Chat style](../../../docs/internals/chat-style.md) in full, including its checklist.
3. Check each kept line against the note, reading the surrounding entries where a rule depends on them (the theme, a
   prefix, the message a placeholder is inserted into). A line copied from an existing entry that already follows the
   note is fine.
4. Check that no message key or placeholder was renamed or removed: operators' own `messages.yml` files must keep
   working.
5. Where the changed text is on a screen, render it (the `minecraft-verify` skill: `mc-render` or
   `paper_render_player_screen`) and look at the PNGs for what the diff hides: a tag that did not resolve, a chat line
   that wraps badly, a hover that covers too much. If nothing can show the screen, report `no render: <screen>` instead
   of guessing.

## Reporting

- Report each violation on one line: `path:line`, the rule it breaks in a few words, and the fix, such as the tag,
  prefix or wording the note asks for.
- Report only lines the branch changed. Untouched text that predates a rule is not a finding, and neither is a
  preference the note does not state.
- A change that needs a pattern the note does not cover is reported as `needs a rule: <what>`. Propose it to the
  developer and add it to the note in the same change.
- With no findings, the whole reply is exactly `All clear`.
