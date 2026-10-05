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

/** Consumer-thread-only payload construction. */
final class DiscordPayload {
    record Body(byte[] bytes, String contentType) {}
    static final int MAX_ATTACHMENT_BYTES = 9 * 1024 * 1024;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

    static Body build(Object event, DiscordSettings settings, WebhookSettings webhook, String validRole) {
        Embed embed;
        short[] audio;
        int sampleRate;
        String shortId;
        if (event instanceof Incident incident) {
            embed = incident(incident, settings, webhook);
            audio = incident.audio();
            sampleRate = incident.sampleRate();
            shortId = incident.shortId();
        } else if (event instanceof StaffAction action) {
            embed = staff(action, settings, webhook);
            audio = new short[0];
            sampleRate = 16_000;
            shortId = action.targetId().toString().substring(0, 8);
        } else if (event instanceof VoiceReport report) {
            embed = report(report, settings, webhook);
            audio = report.audio();
            sampleRate = report.sampleRate();
            shortId = report.shortId();
        } else throw new IllegalArgumentException("Unsupported webhook event");

        JsonObject payload = new JsonObject();
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        if (!validRole.isEmpty()) {
            JsonArray roles = new JsonArray();
            roles.add(validRole);
            mentions.add("roles", roles);
            payload.addProperty("content", "<@&" + validRole + ">");
        }
        payload.add("allowed_mentions", mentions);
        if (!webhook.username().isBlank()) payload.addProperty("username", webhook.username());
        if (!webhook.avatarUrl().isBlank()) payload.addProperty("avatar_url", webhook.avatarUrl());
        JsonArray embeds = new JsonArray();
        embeds.add(embed.json());
        payload.add("embeds", embeds);
        if (!webhook.includeAudio() || audio.length == 0) {
            return new Body(payload.toString().getBytes(StandardCharsets.UTF_8), "application/json");
        }

        String filename = "voice-" + shortId + ".wav";
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

    private static Embed incident(Incident incident, DiscordSettings settings, WebhookSettings webhook) {
        ActionType type = incident.action().type();
        String action = switch (type) {
            case NONE -> "Flagged";
            case WARN -> "Warned";
            case MUTE -> "Muted for " + DiscordText.duration(incident.action().duration()) + " (offense #" + incident.offense() + ")";
        };
        String title = switch (type) {
            case NONE -> "Voice flag: {player}";
            case WARN -> "Voice warning: {player}";
            case MUTE -> "Voice auto-mute: {player}";
        };
        int color = switch (type) { case NONE -> 0xF1C40F; case WARN -> 0xE67E22; case MUTE -> 0xE74C3C; };
        Embed embed = base(settings, webhook, title, color, incident.playerName(), action,
                incident.action().duration(), incident.shortId(), incident.detectedAt());
        embed.field("Player", player(incident.playerName(), incident.playerId()));
        embed.field("Action", DiscordText.plain(action + (incident.dryRun() ? " — dry run, not applied" : "")));
        embed.field("Flags", DiscordText.plain(incident.flagsInWindow() + " / " + incident.flagThreshold() + " in window"));
        Map<String, Set<String>> matched = new LinkedHashMap<>();
        for (RuleMatch match : incident.matches()) {
            matched.computeIfAbsent(match.category(), ignored -> new LinkedHashSet<>()).add(match.matchedText());
        }
        StringJoiner matches = new StringJoiner("\n");
        matched.forEach((category, words) -> matches.add(category + ": " + String.join(", ", words)));
        embed.field("Matched", DiscordText.plain(matches.length() == 0 ? "—" : matches.toString()));
        if (webhook.includeContext() && !incident.context().isEmpty()) {
            StringJoiner context = new StringJoiner("\n");
            for (TranscriptLine line : incident.context()) {
                long relative = Duration.between(incident.spokenAt(), line.spokenAt()).getSeconds();
                context.add((relative > 0 ? "+" : "") + relative + "s " + line.text());
            }
            embed.field("Context", DiscordText.plain(context.toString()));
        }
        if (webhook.includeServerInfo()) {
            String server = settings.serverName();
            if (incident.location() != null) server += (server.isEmpty() ? "" : "\n") + incident.location().formatBlock();
            if (!server.isEmpty()) embed.field("Server", DiscordText.plain(server));
        }
        embed.field("Incident id", DiscordText.plain(incident.id().toString()));
        if (webhook.includeTranscript()) {
            DiscordText transcript = DiscordText.highlighted(incident.transcript(), incident.matches(), webhook.spoilerFlaggedWords());
            embed.description = transcript::render;
        }
        return embed;
    }

    private static Embed staff(StaffAction action, DiscordSettings settings, WebhookSettings webhook) {
        boolean mute = action.type() == StaffAction.Type.MUTE;
        Embed embed = base(settings, webhook, mute ? "Voice mute: {player}" : "Voice unmute: {player}",
                mute ? 0xE74C3C : 0x2ECC71, action.targetName(), mute ? "Muted" : "Unmuted",
                action.duration(), action.targetId().toString().substring(0, 8), action.timestamp());
        embed.field("Player", player(action.targetName(), action.targetId()));
        embed.field("By", DiscordText.plain(action.actor()));
        if (mute) embed.field("Duration", DiscordText.plain(DiscordText.duration(action.duration())));
        if (action.reason() != null && !action.reason().isBlank()) embed.field("Reason", DiscordText.plain(action.reason()));
        return embed;
    }

    private static Embed report(VoiceReport report, DiscordSettings settings, WebhookSettings webhook) {
        Embed embed = base(settings, webhook, "Voice report: {player}", 0x3498DB, report.targetName(), "Reported",
                report.window(), report.shortId(), report.createdAt());
        embed.field("Reported player", player(report.targetName(), report.targetId()));
        embed.field("Reporter", report.reporterId() == null ? DiscordText.plain(report.reporterName()) : player(report.reporterName(), report.reporterId()));
        embed.field("Window", DiscordText.plain(DiscordText.duration(report.window())));
        embed.field("Lines", DiscordText.plain(Integer.toString(report.lines().size())));
        if (webhook.includeTranscript()) embed.description = max -> reportLines(report.lines(), max);
        return embed;
    }

    private static String reportLines(List<TranscriptLine> lines, int max) {
        if (lines.isEmpty() || max <= 0) return "";
        Deque<String> retained = new ArrayDeque<>();
        int length = 0;
        int index = lines.size() - 1;
        for (; index >= 0; index--) {
            TranscriptLine line = lines.get(index);
            String text = DiscordText.plain("[" + CLOCK.format(line.spokenAt()) + "] " + line.text()).render(max);
            int added = text.length() + (retained.isEmpty() ? 0 : 1);
            if (length + added > max) break;
            retained.addFirst(text);
            length += added;
        }
        if (index >= 0 && length + 2 <= max) retained.addFirst("…");
        return String.join("\n", retained);
    }

    private static DiscordText player(String name, UUID id) { return DiscordText.plain(name).append("\n").code(id.toString()); }

    private static Embed base(DiscordSettings settings, WebhookSettings webhook, String defaultTitle, int color,
                              String player, String action, Duration duration, String id, Instant timestamp) {
        Map<String, String> values = Map.of("player", player, "action", action, "duration", DiscordText.duration(duration),
                "id", id, "server", settings.serverName());
        String title = webhook.title().isEmpty() ? defaultTitle : webhook.title();
        String footer = webhook.footer().isEmpty() ? "VoiceWarden" + (settings.serverName().isEmpty() ? "" : " • {server}") : webhook.footer();
        return new Embed(DiscordText.plain(DiscordText.template(title, values)),
                DiscordText.plain(DiscordText.template(footer, values)), webhook.color() == -1 ? color : webhook.color(), timestamp);
    }

    private record Field(String name, DiscordText value) {}
    private static final class Embed {
        private final DiscordText title;
        private final DiscordText footer;
        private final int color;
        private final Instant timestamp;
        private final List<Field> fields = new ArrayList<>();
        private IntFunction<String> description = ignored -> "";

        private Embed(DiscordText title, DiscordText footer, int color, Instant timestamp) {
            this.title = title; this.footer = footer; this.color = color; this.timestamp = timestamp;
        }
        void field(String name, DiscordText value) { fields.add(new Field(name, value)); }

        JsonObject json() {
            JsonObject json = new JsonObject();
            String renderedTitle = title.render(256);
            String renderedFooter = footer.render(2048);
            int budget = 6000 - renderedTitle.length() - renderedFooter.length();
            if (!renderedTitle.isEmpty()) json.addProperty("title", renderedTitle);
            json.addProperty("color", color);
            json.addProperty("timestamp", timestamp.toString());
            JsonObject footerJson = new JsonObject();
            footerJson.addProperty("text", renderedFooter);
            json.add("footer", footerJson);
            JsonArray renderedFields = new JsonArray();
            for (Field field : fields) {
                if (renderedFields.size() == 25 || budget < 2) break;
                String name = DiscordText.plain(field.name()).render(Math.min(256, budget - 1));
                String value = field.value().render(Math.min(1024, budget - name.length()));
                if (value.isEmpty()) value = "—";
                JsonObject item = new JsonObject();
                item.addProperty("name", name);
                item.addProperty("value", value);
                renderedFields.add(item);
                budget -= name.length() + value.length();
            }
            json.add("fields", renderedFields);
            String renderedDescription = description.apply(Math.min(4096, budget));
            if (!renderedDescription.isEmpty()) json.addProperty("description", renderedDescription);
            return json;
        }
    }
}
