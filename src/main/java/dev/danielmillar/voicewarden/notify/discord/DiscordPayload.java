package dev.danielmillar.voicewarden.notify.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.danielmillar.voicewarden.audio.WavEncoder;
import dev.danielmillar.voicewarden.moderation.*;
import dev.danielmillar.voicewarden.moderation.rules.RuleMatch;
import dev.danielmillar.voicewarden.report.VoiceReport;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.IntFunction;

/**
 * Consumer-thread-only payload construction. Each event is one Components V2 message: a container in the event's
 * colour holding a {@code ##} title, parts between dividers and a {@code -#} footer. The recording follows as its own
 * plain message.
 */
final class DiscordPayload {
    record Body(byte[] bytes, String contentType) {}
    static final int MAX_ATTACHMENT_BYTES = 9 * 1024 * 1024;
    /** Discord's limit on the text of all text displays in one message. */
    static final int TEXT_LIMIT = 4000;
    static final int COMPONENTS_V2 = 1 << 15;
    private static final int TEXT = 10, SEPARATOR = 14, CONTAINER = 17;
    private static final int TITLE_LIMIT = 300, FOOTER_LIMIT = 300, DETAILS_LIMIT = 1500, CONTEXT_SHARE = 3;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

    /**
     * The messages to post, in order: the Components V2 card, then the recording as a plain message when audio is on.
     * Discord shows an audio player only for an attachment on a plain message; inside a Components V2 card a WAV is a
     * download, so the clip cannot be part of the card.
     */
    static List<Body> build(Object event, DiscordSettings settings, WebhookSettings webhook, String validRole) {
        Message message;
        short[] audio;
        int sampleRate;
        String shortId;
        if (event instanceof Incident incident) {
            message = incident(incident, settings, webhook);
            audio = incident.audio();
            sampleRate = incident.sampleRate();
            shortId = incident.shortId();
        } else if (event instanceof StaffAction action) {
            message = staff(action, settings, webhook);
            audio = new short[0];
            sampleRate = 16_000;
            shortId = action.targetId().toString().substring(0, 8);
        } else if (event instanceof VoiceReport report) {
            message = report(report, settings, webhook);
            audio = report.audio();
            sampleRate = report.sampleRate();
            shortId = report.shortId();
        } else throw new IllegalArgumentException("Unsupported webhook event");

        boolean attach = webhook.includeAudio() && audio.length > 0;
        String filename = "voice-" + shortId + ".wav";
        JsonObject payload = new JsonObject();
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        String ping = "";
        if (!validRole.isEmpty()) {
            JsonArray roles = new JsonArray();
            roles.add(validRole);
            mentions.add("roles", roles);
            ping = "<@&" + validRole + ">";
        }
        payload.add("allowed_mentions", mentions);
        if (!webhook.username().isBlank()) payload.addProperty("username", webhook.username());
        if (!webhook.avatarUrl().isBlank()) payload.addProperty("avatar_url", webhook.avatarUrl());
        JsonObject card = payload.deepCopy();
        card.addProperty("flags", COMPONENTS_V2);
        card.add("components", message.json(ping));
        Body cardBody = new Body(card.toString().getBytes(StandardCharsets.UTF_8), "application/json");
        if (!attach) {
            return List.of(cardBody);
        }
        // The recording carries no mentions, so the role is pinged once, by the card.
        payload.add("allowed_mentions", noMentions());
        payload.addProperty("content", "-# Recording · " + shortId);
        return List.of(cardBody, recording(payload, filename, audio, sampleRate));
    }

    private static JsonObject noMentions() {
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        return mentions;
    }

    private static Body recording(JsonObject payload, String filename, short[] audio, int sampleRate) {
        JsonObject attachment = new JsonObject();
        attachment.addProperty("id", 0);
        attachment.addProperty("filename", filename);
        JsonArray attachments = new JsonArray();
        attachments.add(attachment);
        payload.add("attachments", attachments);
        String boundary = "VoiceWarden-" + UUID.randomUUID();
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\n"
                + "Content-Type: application/json\r\n\r\n" + payload + "\r\n--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"" + filename
                + "\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        // Discord rejects attachments over 10 MB on unboosted servers: keep only the most recent audio.
        int maxSamples = (MAX_ATTACHMENT_BYTES - 44) / 2;
        short[] clip = audio.length <= maxSamples ? audio : java.util.Arrays.copyOfRange(audio, audio.length - maxSamples, audio.length);
        byte[] wav = WavEncoder.encode(clip, sampleRate);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[Math.addExact(Math.addExact(prefix.length, wav.length), suffix.length)];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(wav, 0, body, prefix.length, wav.length);
        System.arraycopy(suffix, 0, body, prefix.length + wav.length, suffix.length);
        return new Body(body, "multipart/form-data; boundary=" + boundary);
    }

    private static Message incident(Incident incident, DiscordSettings settings, WebhookSettings webhook) {
        ActionType type = incident.action().type();
        Incident.DoubleCheck check = incident.doubleCheck();
        boolean unconfirmed = check != null && !check.confirmed();
        String action = unconfirmed ? "Not confirmed by the double check, so nothing was done" : switch (type) {
            case NONE -> "Flagged";
            case WARN -> "Warned";
            case MUTE -> "Muted for " + DiscordText.duration(incident.action().duration()) + " (offense " + incident.offense() + ")";
        };
        String title = unconfirmed ? "Voice flag (unconfirmed): {player}" : switch (type) {
            case NONE -> "Voice flag: {player}";
            case WARN -> "Voice warning: {player}";
            case MUTE -> "Voice auto-mute: {player}";
        };
        int color = unconfirmed ? 0x95A5A6 : switch (type) { case NONE -> 0xF1C40F; case WARN -> 0xE67E22; case MUTE -> 0xE74C3C; };
        Message message = base(settings, webhook, title, color, incident.playerName(),
                action + (incident.dryRun() ? " (dry run, not applied)" : ""), incident.action().duration(),
                incident.shortId(), incident.detectedAt());
        if (webhook.includeTranscript()) {
            DiscordText transcript = DiscordText.highlighted(incident.transcript().replace('\n', ' '),
                    incident.matches(), webhook.spoilerFlaggedWords());
            message.flexible("### 🗣️ Transcript\n> ", transcript::render);
        }
        Map<String, Set<String>> matched = new LinkedHashMap<>();
        for (RuleMatch match : incident.matches()) {
            matched.computeIfAbsent(match.category(), ignored -> new LinkedHashSet<>()).add(match.matchedText());
        }
        DiscordText details = new DiscordText();
        row(details, "Player", player(incident.playerName(), incident.playerId()));
        StringJoiner matches = new StringJoiner("; ");
        matched.forEach((category, words) -> matches.add(category + ": " + String.join(", ", words)));
        row(details, "Matched", DiscordText.plain(matches.length() == 0 ? "nothing" : matches.toString()));
        row(details, "Flags", DiscordText.plain(incident.flagsInWindow() + " of " + incident.flagThreshold() + " in the window"));
        if (check != null) {
            row(details, "Double check", DiscordText.plain((check.confirmed() ? "Confirmed" : "Not confirmed")
                    + ", the second model heard " + check.describe()));
        }
        if (webhook.includeServerInfo()) {
            StringJoiner server = new StringJoiner(" · ");
            if (!settings.serverName().isEmpty()) server.add(settings.serverName());
            if (incident.location() != null) server.add(incident.location().formatBlock());
            if (server.length() > 0) row(details, "Server", DiscordText.plain(server.toString()));
        }
        row(details, "Incident", new DiscordText().code(incident.id().toString()));
        message.fixed("### 📋 Details\n", details, DETAILS_LIMIT);
        if (webhook.includeContext() && !incident.context().isEmpty()) {
            List<String> context = new ArrayList<>();
            for (TranscriptLine line : incident.context()) {
                long relative = Duration.between(incident.spokenAt(), line.spokenAt()).getSeconds();
                context.add((relative > 0 ? "+" : "") + relative + "s " + line.text());
            }
            message.flexible("### 🕘 Said before\n", max -> lines(context, "-# ", max, true));
        }
        return message;
    }

    private static Message staff(StaffAction action, DiscordSettings settings, WebhookSettings webhook) {
        boolean forGood = action.duration() == null;
        String title = switch (action.type()) {
            case MUTE -> "Voice mute: {player}";
            case UNMUTE -> "Voice unmute: {player}";
            case PARDON -> "Voice pardon: {player}";
        };
        String status = switch (action.type()) {
            case MUTE -> forGood ? "Muted for good" : "Muted for " + DiscordText.duration(action.duration());
            case UNMUTE -> "Unmuted";
            case PARDON -> "Pardoned";
        };
        int color = switch (action.type()) { case MUTE -> 0xE74C3C; case UNMUTE -> 0x2ECC71; case PARDON -> 0x3498DB; };
        Message message = base(settings, webhook, title, color, action.targetName(), status,
                action.duration(), action.targetId().toString().substring(0, 8), action.timestamp());
        DiscordText details = new DiscordText();
        row(details, "Player", player(action.targetName(), action.targetId()));
        row(details, "By", DiscordText.plain(action.actor()));
        if (action.reason() != null && !action.reason().isBlank()) row(details, "Reason", DiscordText.plain(action.reason()));
        message.fixed("### 📋 Details\n", details, DETAILS_LIMIT);
        return message;
    }

    private static Message report(VoiceReport report, DiscordSettings settings, WebhookSettings webhook) {
        Message message = base(settings, webhook, "Voice report: {player}", 0x3498DB, report.targetName(),
                "Reported by " + report.reporterName(), report.window(), report.shortId(), report.createdAt());
        DiscordText details = new DiscordText();
        row(details, "Player", player(report.targetName(), report.targetId()));
        row(details, "Reporter", report.reporterId() == null ? DiscordText.plain(report.reporterName())
                : player(report.reporterName(), report.reporterId()));
        row(details, "Speech", DiscordText.plain(report.lines().size() + " lines from the last "
                + DiscordText.duration(report.window())));
        message.fixed("### 📋 Details\n", details, DETAILS_LIMIT);
        if (webhook.includeTranscript() && !report.lines().isEmpty()) {
            List<String> lines = report.lines().stream()
                    .map(line -> "[" + CLOCK.format(line.spokenAt()) + "] " + line.text()).toList();
            message.flexible("### 🗣️ Transcript\n", max -> lines(lines, "", max, false));
        }
        return message;
    }

    /** As many lines as fit in {@code max}: the oldest when {@code oldestFirst}, otherwise the newest, with "…" for the rest. */
    private static String lines(List<String> lines, String prefix, int max, boolean oldestFirst) {
        if (lines.isEmpty() || max <= 0) return "";
        Deque<String> retained = new ArrayDeque<>();
        int length = 0;
        int shown = 0;
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(oldestFirst ? i : lines.size() - 1 - i);
            String text = prefix + DiscordText.plain(raw.replace('\n', ' ')).render(Math.max(0, max - prefix.length()));
            int added = text.length() + (retained.isEmpty() ? 0 : 1);
            if (length + added > max) break;
            if (oldestFirst) retained.addLast(text); else retained.addFirst(text);
            length += added;
            shown++;
        }
        if (shown < lines.size() && length + 2 <= max) {
            if (oldestFirst) retained.addLast("…"); else retained.addFirst("…");
        }
        return String.join("\n", retained);
    }

    private static void row(DiscordText details, String label, DiscordText value) {
        if (!details.isEmpty()) details.append("\n");
        details.bold(label).append(" ").append(value);
    }

    private static DiscordText player(String name, UUID id) {
        return DiscordText.plain(name).append(" · ").code(id.toString());
    }

    private static Message base(DiscordSettings settings, WebhookSettings webhook, String defaultTitle, int color,
                                String player, String action, Duration duration, String id, Instant timestamp) {
        Map<String, String> values = Map.of("player", player, "action", action, "duration", DiscordText.duration(duration),
                "id", id, "server", settings.serverName());
        String title = webhook.title().isEmpty() ? defaultTitle : webhook.title();
        String footer = webhook.footer().isEmpty() ? "VoiceWarden" + (settings.serverName().isEmpty() ? "" : " · {server}") : webhook.footer();
        Message message = new Message(webhook.color() == -1 ? color : webhook.color());
        message.title = DiscordText.plain(DiscordText.template(title, values)).render(TITLE_LIMIT);
        message.summary = DiscordText.plain(action).render(TITLE_LIMIT) + " · <t:" + timestamp.getEpochSecond() + ":f>";
        message.footer = DiscordText.plain(DiscordText.template(footer, values)).render(FOOTER_LIMIT);
        return message;
    }

    /** A part under its heading; fixed parts are sized first, flexible ones share what is left of the text limit. */
    private record Part(String heading, IntFunction<String> body, int cap) {}

    private static final class Message {
        private final int color;
        private String title = "";
        private String summary = "";
        private String footer = "";
        private final List<Part> parts = new ArrayList<>();

        private Message(int color) { this.color = color; }

        void fixed(String heading, DiscordText body, int cap) { parts.add(new Part(heading, body::render, cap)); }
        void flexible(String heading, IntFunction<String> body) { parts.add(new Part(heading, body, -1)); }

        JsonArray json(String ping) {
            String head = "## " + title + "\n" + summary;
            String foot = "-# " + footer;
            int budget = TEXT_LIMIT - ping.length() - head.length() - foot.length();
            Map<Part, String> rendered = new IdentityHashMap<>();
            for (Part part : parts) {
                if (part.cap() < 0) continue;
                String body = part.body().apply(Math.max(0, Math.min(part.cap(), budget - part.heading().length())));
                rendered.put(part, body);
                budget -= part.heading().length() + body.length();
            }
            List<Part> flexible = parts.stream().filter(part -> part.cap() < 0).toList();
            for (int i = 0; i < flexible.size(); i++) {
                Part part = flexible.get(i);
                int room = budget - part.heading().length();
                // Later flexible parts (earlier speech) get a share, the first (the transcript) the rest.
                int share = i == 0 && flexible.size() > 1 ? room - room / CONTEXT_SHARE : room;
                String body = part.body().apply(Math.max(0, share));
                rendered.put(part, body);
                budget -= part.heading().length() + body.length();
            }

            JsonArray inside = new JsonArray();
            inside.add(text(head));
            for (Part part : parts) {
                String body = rendered.get(part);
                if (body == null || body.isEmpty()) continue;
                inside.add(separator(true));
                inside.add(text(part.heading() + body));
            }
            inside.add(separator(false));
            inside.add(text(foot));
            JsonObject container = new JsonObject();
            container.addProperty("type", CONTAINER);
            container.addProperty("accent_color", color);
            container.add("components", inside);
            JsonArray components = new JsonArray();
            if (!ping.isEmpty()) components.add(text(ping));
            components.add(container);
            return components;
        }

        private static JsonObject text(String content) {
            JsonObject text = new JsonObject();
            text.addProperty("type", TEXT);
            text.addProperty("content", content);
            return text;
        }

        private static JsonObject separator(boolean divider) {
            JsonObject separator = new JsonObject();
            separator.addProperty("type", SEPARATOR);
            separator.addProperty("divider", divider);
            separator.addProperty("spacing", divider ? 2 : 1);
            return separator;
        }
    }
}
