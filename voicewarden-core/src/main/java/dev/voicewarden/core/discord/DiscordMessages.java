package dev.voicewarden.core.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.voicewarden.api.ConfirmationState;
import dev.voicewarden.api.VoiceAction;
import dev.voicewarden.api.VoiceMuteRecord;
import dev.voicewarden.core.audio.AcousticAbuseEvent;
import dev.voicewarden.core.audio.WavEncoder;
import dev.voicewarden.core.moderation.ConversationLine;
import dev.voicewarden.core.storage.VoiceReportRecord;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class DiscordMessages {

    public static final String UNCONFIRMED_TAG = "(unconfirmed)";
    public static final String NO_SPEECH_CAPTURED = "(no speech captured in this window)";
    public static final String NO_SPEECH_WITH_AUDIO = "(no speech transcribed; audio clip attached)";
    public static final int AUDIO_SAMPLE_RATE = 16_000;
    public static final int MAX_ATTACHMENT_BYTES = 8 * 1024 * 1024;
    public static final int FLAG_IS_COMPONENTS_V2 = 1 << 15;
    public static final int TYPE_TEXT_DISPLAY = 10;
    public static final int TYPE_SEPARATOR = 14;
    public static final int TYPE_CONTAINER = 17;

    public static final int COLOR_MUTE = 0xFF5C5C;
    public static final int COLOR_WARN = 0xFFB454;
    public static final int COLOR_FLAG = 0xF1C40F;
    public static final int COLOR_UNMUTE = 0x4BE38B;
    public static final int COLOR_REPORT = 0x5865F2;

    static final int MAX_TITLE = 256;
    static final int MAX_FIELD_VALUE = 1024;
    static final int MAX_TRANSCRIPT = 3500;
    static final int MAX_FOOTER = 2048;
    static final int MAX_USERNAME = 80;
    static final int MAX_TOTAL_TEXT_CHARS = 4000;
    static final int MAX_CONTEXT_LINE = 300;
    static final int CONTEXT_RESERVE = 800;
    static final String CONTEXT_HEADING = "### 🕘 Nearby conversation";
    private static final int MIN_TRANSCRIPT = 64;
    private static final String TRANSCRIPT_PREFIX = "### 🗣️ Transcript\n>>> ";

    private final DiscordWebhookConfig config;

    public DiscordMessages(DiscordWebhookConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public WebhookMessage flag(FlagWebhookPayload payload, boolean test) {
        WebhookTarget target = config.flagWebhook();
        String title = prefix(test) + orDefault(target.title(), "Voice flag") + ": " + payload.playerName();
        if (payload.unconfirmed()) {
            title = title + " " + UNCONFIRMED_TAG;
        }
        String summary = (payload.unconfirmed() ? "Unconfirmed speech flag (no action taken)" : "Flagged speech detected")
                + " · <t:" + payload.timestamp().getEpochSecond() + ":f>";
        List<String> details = new ArrayList<>();
        details.add("**Player** " + payload.playerName() + " (`" + payload.playerId() + "`)");
        details.add("**Severity** " + payload.severity().name().toLowerCase(Locale.ROOT)
                + " · **Confirmation** " + confirmationLabel(payload.confirmationState())
                + " · **Action** " + actionLabel(payload));
        if (!payload.matchedWords().isEmpty()) {
            details.add("**Matched words** " + truncate(String.join(", ", payload.matchedWords()), MAX_FIELD_VALUE));
        }
        String transcript = target.includeTranscript().includesFlagged() && !payload.transcript().isBlank()
                ? payload.transcript() : null;
        AudioAttachment audio = audio(target, payload.pcm16kAudio(), payload.playerName(), payload.timestamp());
        if (audio.note() != null) {
            details.add("**Audio** " + audio.note());
        }
        List<ConversationLine> context = target.includeTranscript().includesFlagged() ? payload.contextLines() : List.of();
        int color = target.embedColor() > 0 ? target.embedColor() : flagAccentColor(payload);
        return containerMessage(target, color, title, summary, transcript, context, details, audio);
    }

    public WebhookMessage micSpam(MicSpamWebhookPayload payload, boolean test) {
        WebhookTarget target = config.flagWebhook();
        String title = prefix(test) + "Voice mic spam: " + payload.playerName();
        String summary = "Sustained " + (payload.kind() == AcousticAbuseEvent.Kind.CLIPPING ? "clipped, distorted" : "ear-splitting")
                + " microphone audio · <t:" + payload.timestamp().getEpochSecond() + ":f>";
        List<String> details = new ArrayList<>();
        details.add("**Player** " + payload.playerName() + " (`" + payload.playerId() + "`)");
        details.add("**Kind** " + payload.kind().label() + " · **Action** " + payload.actionTaken().name().toLowerCase(Locale.ROOT));
        details.add(String.format(Locale.ROOT, "**Peak loudness** %d%% · **Clipped samples** %d%% · **Lasted** %.1fs",
                Math.round(AcousticAbuseEvent.peakLevel(payload.peakRms()) * 100), Math.round(payload.clippingRatio() * 100),
                payload.durationMs() / 1000.0));
        AudioAttachment audio = audio(target, payload.pcm16kAudio(), payload.playerName(), payload.timestamp());
        if (audio.note() != null) {
            details.add("**Audio** " + audio.note());
        }
        int color = target.embedColor() > 0 ? target.embedColor() : actionAccentColor(payload.actionTaken());
        return containerMessage(target, color, title, summary, null, List.of(), details, audio);
    }

    public WebhookMessage mute(MuteWebhookPayload payload, boolean test) {
        WebhookTarget target = config.muteWebhook();
        VoiceMuteRecord mute = payload.mute();
        String heading = orDefault(target.title(), "Voice mute");
        String title = prefix(test) + heading + (payload.isUnmute() ? " lifted: " : ": ") + mute.playerName();
        String summary = (payload.isUnmute() ? "Voice mute removed" : "Voice mute applied")
                + " · <t:" + payload.timestamp().getEpochSecond() + ":f>";
        List<String> details = new ArrayList<>();
        details.add("**Player** " + mute.playerName() + " (`" + mute.playerId() + "`)");
        if (payload.isUnmute()) {
            details.add("**Unmuted by** " + orDefault(payload.unmutedBy(), "automatic expiry"));
            details.add("**Original reason** " + truncate(mute.reason(), MAX_FIELD_VALUE));
        } else {
            details.add("**Moderator** " + mute.moderator() + " · **Duration** " + durationLabel(mute));
            details.add("**Reason** " + truncate(mute.reason(), MAX_FIELD_VALUE));
        }
        String transcript = !payload.isUnmute() && target.includeTranscript().includesMuted()
                && payload.transcript() != null && !payload.transcript().isBlank()
                ? payload.transcript() : null;
        AudioAttachment audio = payload.isUnmute()
                ? AudioAttachment.NONE
                : audio(target, payload.pcm16kAudio(), mute.playerName(), payload.timestamp());
        if (audio.note() != null) {
            details.add("**Audio** " + audio.note());
        }
        List<ConversationLine> context = !payload.isUnmute() && target.includeTranscript().includesMuted()
                ? payload.contextLines() : List.of();
        int color = payload.isUnmute()
                ? COLOR_UNMUTE
                : (target.embedColor() > 0 ? target.embedColor() : COLOR_MUTE);
        return containerMessage(target, color, title, summary, transcript, context, details, audio);
    }

    public WebhookMessage report(ReportWebhookPayload payload, boolean test) {
        WebhookTarget target = config.reportWebhook();
        VoiceReportRecord report = payload.report();
        String title = prefix(test) + orDefault(target.title(), "Voice report") + ": " + report.targetName();
        String summary = "Reported by " + report.reporterName() + " · <t:" + report.createdAt().getEpochSecond() + ":f>";
        List<String> details = new ArrayList<>();
        details.add("**Reported player** " + report.targetName() + " (`" + report.targetId() + "`)");
        details.add("**Reporter** " + report.reporterName() + " (`" + report.reporterId() + "`)");
        String window = report.windowMinutes() + (report.windowMinutes() == 1 ? " minute" : " minutes");
        details.add("**Window** " + window + (report.id() > 0 ? " · **Report ID** #" + report.id() : ""));
        AudioAttachment audio = audio(target, payload.pcm16kAudio(), report.targetName(), report.createdAt());
        if (audio.note() != null) {
            details.add("**Audio** " + audio.note());
        }
        boolean includeTranscript = target.includeTranscript() != IncludeTranscriptMode.NONE;
        String transcript = includeTranscript
                ? reportTranscript(payload.transcript(), audio.wav() != null)
                : null;
        List<ConversationLine> context = includeTranscript ? payload.contextLines() : List.of();
        int color = target.embedColor() > 0 ? target.embedColor() : COLOR_REPORT;
        return containerMessage(target, color, title, summary, transcript, context, details, audio);
    }

    private static String reportTranscript(String snapshot, boolean audioAttached) {
        if (!snapshot.isBlank()) {
            return snapshot;
        }
        return audioAttached ? NO_SPEECH_WITH_AUDIO : NO_SPEECH_CAPTURED;
    }

    static String fileName(String playerName, Instant timestamp) {
        String safe = playerName.replaceAll("[^A-Za-z0-9_-]", "_");
        if (safe.isEmpty()) {
            safe = "player";
        }
        return "voicewarden-" + safe + "-" + timestamp.toEpochMilli() + ".wav";
    }

    static String truncate(String value, int max) {
        if (max <= 0) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        if (max == 1) {
            return "…";
        }
        return value.substring(0, max - 1) + "…";
    }

    private static int textLength(String value) {
        return value == null ? 0 : value.length();
    }

    static String contextRow(ConversationLine line) {
        String text = line.text().replaceAll("\\s+", " ").strip();
        String row = "-# **" + escapeMarkdown(line.playerName()) + "** · <t:" + line.timestamp().getEpochSecond() + ":T> — "
                + escapeMarkdown(text);
        return truncate(row, MAX_CONTEXT_LINE);
    }

    private static int contextLength(List<String> rows) {
        if (rows.isEmpty()) {
            return 0;
        }
        return CONTEXT_HEADING.length() + rows.stream().mapToInt(row -> row.length() + 1).sum();
    }

    private static String contextSection(List<String> rows, int budget) {
        int used = CONTEXT_HEADING.length();
        int first = rows.size();
        while (first > 0 && used + rows.get(first - 1).length() + 1 <= budget) {
            first--;
            used += rows.get(first).length() + 1;
        }
        if (first == rows.size()) {
            return null;
        }
        return CONTEXT_HEADING + "\n" + String.join("\n", rows.subList(first, rows.size()));
    }

    private static String escapeMarkdown(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '*' || c == '_' || c == '~' || c == '`' || c == '|' || c == '>') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    private WebhookMessage containerMessage(
            WebhookTarget target,
            int accentColor,
            String title,
            String summary,
            String transcript,
            List<ConversationLine> context,
            List<String> details,
            AudioAttachment audio) {
        JsonObject root = new JsonObject();
        root.addProperty("flags", FLAG_IS_COMPONENTS_V2);
        if (!target.username().isEmpty()) {
            root.addProperty("username", truncate(target.username(), MAX_USERNAME));
        }
        if (!target.avatarUrl().isEmpty()) {
            root.addProperty("avatar_url", target.avatarUrl());
        }
        List<String> roles = target.rolePingIds().stream().filter(id -> id.chars().allMatch(Character::isDigit)).toList();
        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        JsonArray allowedRoles = new JsonArray();
        roles.forEach(allowedRoles::add);
        allowedMentions.add("roles", allowedRoles);
        root.add("allowed_mentions", allowedMentions);

        String rolePings = roles.isEmpty()
                ? null
                : String.join(" ", roles.stream().map(id -> "<@&" + id + ">").toList());
        String headerContent = "## " + truncate(title, MAX_TITLE) + "\n" + summary;
        String detailsContent = "### 📋 Details\n" + String.join("\n", details);
        String footerText = footerLine(target);
        String footerContent = footerText.isEmpty() ? null : "-# " + truncate(footerText, MAX_FOOTER);

        int nonTranscriptChars = textLength(rolePings) + headerContent.length()
                + detailsContent.length() + textLength(footerContent);
        if (nonTranscriptChars > MAX_TOTAL_TEXT_CHARS && footerContent != null) {
            int maxFooter = Math.max(0, MAX_TOTAL_TEXT_CHARS - (nonTranscriptChars - footerContent.length()));
            footerContent = maxFooter >= 4 ? truncate(footerContent, maxFooter) : null;
            nonTranscriptChars = textLength(rolePings) + headerContent.length()
                    + detailsContent.length() + textLength(footerContent);
        }
        if (nonTranscriptChars > MAX_TOTAL_TEXT_CHARS) {
            int maxDetails = Math.max(16, MAX_TOTAL_TEXT_CHARS - (nonTranscriptChars - detailsContent.length()));
            detailsContent = truncate(detailsContent, maxDetails);
            nonTranscriptChars = textLength(rolePings) + headerContent.length()
                    + detailsContent.length() + textLength(footerContent);
        }

        List<String> contextRows = context.stream().map(DiscordMessages::contextRow).toList();
        String transcriptContent = null;
        if (transcript != null) {
            int availableForTranscript = MAX_TOTAL_TEXT_CHARS - nonTranscriptChars - TRANSCRIPT_PREFIX.length();
            if (availableForTranscript < MIN_TRANSCRIPT && footerContent != null && footerContent.length() > MIN_TRANSCRIPT) {
                int reducedFooter = Math.max(MIN_TRANSCRIPT, footerContent.length() - (MIN_TRANSCRIPT - availableForTranscript));
                footerContent = truncate(footerContent, reducedFooter);
                nonTranscriptChars = textLength(rolePings) + headerContent.length()
                        + detailsContent.length() + textLength(footerContent);
                availableForTranscript = MAX_TOTAL_TEXT_CHARS - nonTranscriptChars - TRANSCRIPT_PREFIX.length();
            }
            int contextReserve = Math.min(Math.min(contextLength(contextRows), CONTEXT_RESERVE),
                    Math.max(0, availableForTranscript - MIN_TRANSCRIPT));
            int limit = Math.min(MAX_TRANSCRIPT, availableForTranscript - contextReserve);
            if (limit >= 1) {
                transcriptContent = TRANSCRIPT_PREFIX + truncate(transcript.strip(), limit);
            }
        }
        String contextContent = contextSection(contextRows,
                MAX_TOTAL_TEXT_CHARS - nonTranscriptChars - textLength(transcriptContent));

        JsonArray topComponents = new JsonArray();
        if (rolePings != null) {
            topComponents.add(textDisplay(rolePings));
        }

        JsonArray children = new JsonArray();
        children.add(textDisplay(headerContent));
        if (transcriptContent != null) {
            children.add(separator());
            children.add(textDisplay(transcriptContent));
        }
        if (contextContent != null) {
            children.add(separator());
            children.add(textDisplay(contextContent));
        }
        children.add(separator());
        children.add(textDisplay(detailsContent));
        if (footerContent != null) {
            children.add(separator());
            children.add(textDisplay(footerContent));
        }

        JsonObject container = new JsonObject();
        container.addProperty("type", TYPE_CONTAINER);
        container.addProperty("accent_color", accentColor);
        container.add("components", children);
        topComponents.add(container);
        root.add("components", topComponents);

        if (audio.wav() == null) {
            return new WebhookMessage(root);
        }
        return new WebhookMessage(root, audioFollowUpPayload(target, audio.fileName()), audio.wav(), audio.fileName());
    }

    // Discord Components V2 File blocks (type 13) only render download cards; a standard attachment
    // message with matching webhook identity merges beneath the card with Discord's inline audio player.
    private static JsonObject audioFollowUpPayload(WebhookTarget target, String fileName) {
        JsonObject root = new JsonObject();
        if (!target.username().isEmpty()) {
            root.addProperty("username", truncate(target.username(), MAX_USERNAME));
        }
        if (!target.avatarUrl().isEmpty()) {
            root.addProperty("avatar_url", target.avatarUrl());
        }
        JsonObject allowedMentions = new JsonObject();
        allowedMentions.add("parse", new JsonArray());
        root.add("allowed_mentions", allowedMentions);
        JsonArray attachments = new JsonArray();
        JsonObject attachment = new JsonObject();
        attachment.addProperty("id", 0);
        attachment.addProperty("filename", fileName);
        attachment.addProperty("description", "Voice clip (16 kHz mono WAV)");
        attachments.add(attachment);
        root.add("attachments", attachments);
        return root;
    }

    private String footerLine(WebhookTarget target) {
        List<String> parts = new ArrayList<>();
        if (!target.footer().isBlank()) {
            parts.add(target.footer().strip());
        }
        if (target.includeServerInfo()) {
            if (!config.serverName().isBlank()) {
                parts.add(config.serverName());
            }
            if (!config.serverId().isBlank() && !config.serverId().equals(config.serverName())) {
                parts.add(config.serverId());
            }
        }
        return String.join(" · ", parts);
    }

    private static JsonObject textDisplay(String content) {
        JsonObject block = new JsonObject();
        block.addProperty("type", TYPE_TEXT_DISPLAY);
        block.addProperty("content", content);
        return block;
    }

    private static JsonObject separator() {
        JsonObject sep = new JsonObject();
        sep.addProperty("type", TYPE_SEPARATOR);
        sep.addProperty("divider", true);
        sep.addProperty("spacing", 1);
        return sep;
    }

    private static int flagAccentColor(FlagWebhookPayload payload) {
        if (payload.unconfirmed() || payload.actionTaken() == null) {
            return COLOR_FLAG;
        }
        return actionAccentColor(payload.actionTaken());
    }

    private static int actionAccentColor(VoiceAction action) {
        return switch (action) {
            case MUTE -> COLOR_MUTE;
            case WARN -> COLOR_WARN;
            case REPORT -> COLOR_FLAG;
        };
    }

    private record AudioAttachment(byte[] wav, String fileName, String note) {
        static final AudioAttachment NONE = new AudioAttachment(null, null, null);
    }

    private static AudioAttachment audio(WebhookTarget target, short[] pcm, String playerName, Instant timestamp) {
        if (!target.includeAudio() || pcm == null || pcm.length == 0) {
            return AudioAttachment.NONE;
        }
        byte[] wav = WavEncoder.encodePcm16Mono(pcm, AUDIO_SAMPLE_RATE);
        double seconds = pcm.length / (double) AUDIO_SAMPLE_RATE;
        if (wav.length > MAX_ATTACHMENT_BYTES) {
            return new AudioAttachment(null, null, String.format(Locale.ROOT, "%.1fs clip is too large to attach", seconds));
        }
        return new AudioAttachment(wav, fileName(playerName, timestamp), String.format(Locale.ROOT, "%.1fs clip below", seconds));
    }

    private static String confirmationLabel(ConfirmationState state) {
        return switch (state) {
            case CONFIRMED -> "confirmed";
            case UNCONFIRMED -> "unconfirmed: no action taken";
            case UNCHECKED -> "not checked";
        };
    }

    private static String actionLabel(FlagWebhookPayload payload) {
        if (payload.unconfirmed() || payload.actionTaken() == null) {
            return "none (alert only)";
        }
        return payload.actionTaken().name().toLowerCase(Locale.ROOT);
    }

    private static String durationLabel(VoiceMuteRecord mute) {
        if (mute.expiresAt() == null) {
            return "permanent";
        }
        return "until <t:" + mute.expiresAt().getEpochSecond() + ":f> (<t:" + mute.expiresAt().getEpochSecond() + ":R>)";
    }

    private static String prefix(boolean test) {
        return test ? "[Test] " : "";
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
