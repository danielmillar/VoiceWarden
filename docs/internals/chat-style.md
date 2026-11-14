# Chat style

Everything a player, staff member or operator reads from VoiceWarden follows this note: chat, the action bar, the
consent dialog, hovers, the kick screen, console replies and Discord posts. The bundled
`src/main/resources/messages.yml` is the reference; the mute notice below is the example to copy.

## Theme

`messages.yml` defines the colours as tags under `theme`. Values are plain hex.

| Tag | Value | Use |
| --- | --- | --- |
| `primary` | teal `#3DD6C6` | VoiceWarden's colour: labels, lead-in words, card titles, actions |
| `body`, `muted`, `dim`, `accent` | `#E4E4EC`, `#7E7E92`, `#4E4E60`, `#FFFFFF` | text, side notes, separators and times, the value that matters |
| `success`, `warning`, `error` | `#4BE38B`, `#FFB454`, `#FF5C5C` | the three states |

A colour means one thing: the label wears `primary`, the value is `accent`, and the states are never decoration.
Never use raw colours, named colours or `dark_gray` in a message; `dim` replaces `dark_gray`. A placeholder shadows a
theme tag of the same name, so `command.reload-warning`, whose placeholder is `<warning>`, cannot use that colour.

## Replies

- **Prefix:** `<prefix>` is `<primary><b>ᴠᴏɪᴄᴇ</b></primary> <dim>|</dim> <body>`: a bold small-caps label, a dim bar,
  the text. No square brackets, no `»`. `ᴠᴏɪᴄᴇ | Reloaded in 84ms.`
- **One-line replies:** a prefix and one `body` sentence ending in a full stop; the value that matters is `accent`. A
  plain status or switch keeps the prefix (`Voice alerts are on.`).
- **Lead-in words:** what happens to a player, or what staff need to notice, starts with a bold capitals word ending
  in `!` instead of the prefix. Regular capitals, not small capitals. Only the word is coloured; the sentence is
  `body`.
  - Events in `primary`: `MUTED!`, `UNMUTED!`, `FLAGGED!`, `WARNED!`, `PARDONED!`, `REPORT!`, `THANKS!`.
  - `WARNING!` in `warning`: a player told to change what they say, or a setting that needs a look.
  - `HOLD UP!` in `error` (`<hold-up>`): a normal refusal, such as no permission, an unknown player, a cooldown or a bad
    duration. It may add the fix as a `muted` second line.
  - `OOPS!` in `error` (`<oops>`): something went wrong, such as a failed reload, a failed save or a check that could
    not finish.

## Lines and cards

- **Lines:** a reply or event is one or two lines. A second line is indented three spaces and `muted`: a reason, a fix
  or how to change an answer. Detail goes in a hover.
- **Staff alerts** are frequent, so they stay one line (two when it wraps). The whole line is the click: it suggests the
  command, and the hover's last line says what the click does. No `▸` on an alert. A report alert is rarer and is an
  event line with its `▸ ᴠɪᴇᴡ` action on the second line.
- **Cards** are for staff and console output: `/vw history`, `/vw check`, `/vw mutes`, `/vw stats`, `/vw transcript`
  and `/reportinbox`. A card has no prefix and is laid out like a tooltip:
  - it starts with `<newline>` and the plugin sends an empty line after its last message; every line starts with one
    space;
  - title ` <primary><b>Title</b></primary>` (the subject, such as the player's name), then a type line
    ` <dim>ᴛʏᴘᴇ</dim>` in small caps with no numbers, then an empty line;
  - data lines ` <primary>|</primary> <body>Label: <accent>value</accent></body>` and a `muted` note after a dim `·`;
  - a list entry starts with a mark in its state's colour or a `dim` time, one entry per line;
  - an action line ` <primary>▸ ɴᴇxᴛ</primary>` under an empty line; a second action after four spaces;
  - an entry with its own actions (an inbox report) puts them on the entry's second line, as in a line.

## Actions and hovers

- Something to click is a `▸` word in small capitals in `primary`, never `[View]`-style buttons or underlined links.
- In a line, actions go on their own second line, `<newline>` plus three spaces, a second action two spaces later, so
  chat wrapping never strands the `▸`. The main action is `primary`; a second one is `muted`.
- A hover is a `body` sentence ending in a full stop: `<hover:show_text:'<body>Open the report.'>`.
- A hover with detail (the incident hover) is a small tooltip: the title in bold `primary`, `muted` labels with `body`
  values, and the click's sentence last after an empty line.

## Colours

- `error` is only for something that went wrong. A normal refusal is a red `HOLD UP!` and a `body` sentence. A mute is
  not an error: it is `MUTED!` in `primary`.
- On is `success`, off is `muted`. Accepted is `success`, denied is `error` only where it blocks the player.
- Separators `<dim>:</dim>`, `<dim> · </dim>` and `<dim>, </dim>` are dim, and so are times at the start of a line.
- A word a rule matched is underlined in `warning` (`alert.match`), so staff see why without reading red as a failure.

## Lists

- Setup check: ` <success>✔</success>` OK, ` <warning>⚠</warning>` worth a look, ` <error>✘</error>` broken, then the
  area in `body`, a dim colon and the finding: `muted` when OK, `body` when it needs a look, so problems stand out. A
  fix goes on a second line, indented and `muted`.
- History and transcripts: the time in `dim`, then the event in its state's colour and the rest in `body` or `muted`.

## Other surfaces

- **Action bar:** the same label and bar as a chat prefix, with the label naming the state: `ᴍᴜᴛᴇᴅ | 9m 47s left`.
  Refusals start with `HOLD UP!`.
- **Dialogs:** no bars and no prefix; the game centres body text. The title is the subject in `primary`. The body is
  short paragraphs of `body` with an empty line between them; a command or setting in it is `accent`. The main button
  is `accent`; the other is plain. No red buttons.
- **Kick screen:** the reason in `error` (it stopped the player), an empty line, then what to do in `body`.
- **Console:** cards and replies sent to the console use the same messages. Log lines are plain sentences.
- **Discord:** one Components V2 container per event, its accent the event's colour (mute red, warn amber, flag
  yellow, unmute green, report and pardon blue). A `##` title `Voice <event>: <player>` over one line saying what
  happened and when (`<t:…:f>`), then parts between dividers, each a `###` heading with an emoji (`🗣️ Transcript`,
  `📋 Details`, `🕘 Said before`). Details are `**Label** value` rows; earlier speech is `-#` lines. The recording is a
  file under the parts, and the `-#` footer comes last. Separators are ` · `. Operators can override the title, footer
  and colour per webhook.

## Small capitals

Only for labels, type lines and the click on an action, never beside numbers. Use Unicode small capitals; S is `ѕ`
(U+0455), F is `ꜰ` (U+A730) and Q is `ǫ` (U+01EB). Every letter must be in the game's default font; copy letters from
an existing message.

## Existing servers

The plugin never replaces a message already in an operator's `messages.yml`; it only adds missing keys. Never rename
or remove a key or a placeholder: an old file must keep working with new code, so text built in code must also read
well under the old templates.

## Example

The mute notice from `messages.yml`:

```yaml
player:
  muted: "<primary><b>MUTED!</b></primary> <body>You are muted in voice chat for <accent><duration></accent>.<newline>   <muted>Reason: <reason>"
```

```text
MUTED! You are muted in voice chat for 10 minutes.
   Reason: insult
```

## Checklist

- [ ] Text lives in `messages.yml` and uses theme tags, never raw colours or `dark_gray`.
- [ ] `primary` on the label or lead-in word, `accent` on the value, states in their own colours.
- [ ] A line, not a card, unless it is staff or console output.
- [ ] Refusals start with `HOLD UP!`, failures with `OOPS!`; red only for real errors.
- [ ] Actions are `▸` small-caps words on their own line; hovers are `body` sentences with a full stop.
- [ ] Action bar, dialogs, kick screen and Discord follow their own rules above.
- [ ] Keys and placeholders unchanged.
- [ ] Before and after renders in the PR (the `minecraft-verify` skill).
