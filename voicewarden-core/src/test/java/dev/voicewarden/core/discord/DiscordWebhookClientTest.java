package dev.voicewarden.core.discord;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.voicewarden.api.ConfirmationState;
import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.api.VoiceAction;
import dev.voicewarden.api.VoiceMuteRecord;
import dev.voicewarden.core.audio.AcousticAbuseEvent;
import dev.voicewarden.core.audio.WavEncoder;
import dev.voicewarden.core.moderation.ConversationLine;
import dev.voicewarden.core.storage.VoiceReportRecord;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DiscordWebhookClientTest {

    private static final Instant TS = Instant.parse("2026-11-24T12:00:00Z");
    private static final UUID STEVE = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ALEX = UUID.fromString("22222222-3333-4444-5555-666666666666");
    private static final UUID JORDAN = UUID.fromString("33333333-4444-5555-6666-777777777777");

    private RecordingWebhookServer server;
    private DiscordWebhookClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = new RecordingWebhookServer();
    }

    @AfterEach
    void stop() {
        if (client != null) {
            client.close();
        }
        server.close();
    }

    private WebhookTarget target(IncludeTranscriptMode transcript, boolean serverInfo, boolean audio) {
        return new WebhookTarget(true, server.url(), "VoiceWarden", "https://example.com/a.png", 0x123456,
                "Voice flag", "VoiceWarden alerts", List.of("111", "222", "not-a-role"), transcript, serverInfo, audio);
    }

    private DiscordWebhookClient client(WebhookTarget flag, WebhookTarget mute, WebhookTarget report, int retries) {
        client = new DiscordWebhookClient(new DiscordWebhookConfig(flag, mute, report, 5, retries, 20, "Lobby One", "lobby-1"));
        return client;
    }

    private static FlagWebhookPayload flag(ConfirmationState state, short[] pcm) {
        return new FlagWebhookPayload(STEVE, "Steve", FlagSeverity.SEVERE, state, List.of("badword", "worse"),
                "you are a badword", VoiceAction.MUTE, pcm, TS);
    }

    private static VoiceMuteRecord muteRecord() {
        return new VoiceMuteRecord(STEVE, "Steve", "Slurs", "VoiceWarden", TS, TS.plus(Duration.ofMinutes(30)), true, "lobby-1");
    }

    private static JsonObject json(RecordingWebhookServer.Received request) {
        return JsonParser.parseString(request.bodyText()).getAsJsonObject();
    }

    private static JsonObject container(JsonObject payload) {
        assertEquals(DiscordMessages.FLAG_IS_COMPONENTS_V2, payload.get("flags").getAsInt());
        assertFalse(payload.has("embeds"));
        assertFalse(payload.has("content"));
        for (JsonElement element : payload.getAsJsonArray("components")) {
            JsonObject component = element.getAsJsonObject();
            if (component.get("type").getAsInt() == DiscordMessages.TYPE_CONTAINER) {
                return component;
            }
        }
        throw new AssertionError("No Components V2 container in payload: " + payload);
    }

    private static List<String> textDisplays(JsonObject container) {
        List<String> out = new ArrayList<>();
        for (JsonElement element : container.getAsJsonArray("components")) {
            JsonObject component = element.getAsJsonObject();
            if (component.get("type").getAsInt() == DiscordMessages.TYPE_TEXT_DISPLAY) {
                out.add(component.get("content").getAsString());
            }
        }
        return out;
    }

    private static String allText(JsonObject container) {
        return String.join("\n", textDisplays(container));
    }

    private static short[] sine(int samples) {
        short[] pcm = new short[samples];
        for (int i = 0; i < samples; i++) {
            pcm[i] = (short) (Math.sin(i / 7.0) * 12000);
        }
        return pcm;
    }

    @Test
    void flagContainerCarriesRolePingsUnconfirmedTagTranscriptAndServerInfo() throws Exception {
        client(target(IncludeTranscriptMode.FLAGGED, true, false), WebhookTarget.disabled(), WebhookTarget.disabled(), 0);

        WebhookResult result = client.sendFlag(flag(ConfirmationState.UNCONFIRMED, null)).get(5, TimeUnit.SECONDS);

        assertTrue(result.success(), result.summary());
        assertEquals(204, result.statusCode());
        RecordingWebhookServer.Received request = server.received().getFirst();
        assertEquals("POST", request.method());
        assertEquals("/api/webhooks/123/secret-token", request.path());
        assertTrue(request.contentType().startsWith("application/json"));
        JsonObject payload = json(request);
        assertEquals("VoiceWarden", payload.get("username").getAsString());
        assertEquals("https://example.com/a.png", payload.get("avatar_url").getAsString());
        JsonArray topComponents = payload.getAsJsonArray("components");
        assertEquals("<@&111> <@&222>", topComponents.get(0).getAsJsonObject().get("content").getAsString());
        JsonObject mentions = payload.getAsJsonObject("allowed_mentions");
        assertEquals(0, mentions.getAsJsonArray("parse").size());
        assertEquals(List.of("111", "222"), mentions.getAsJsonArray("roles").asList().stream().map(JsonElement::getAsString).toList());

        JsonObject box = container(payload);
        assertEquals(0x123456, box.get("accent_color").getAsInt());
        String body = allText(box);
        assertTrue(body.contains("## Voice flag: Steve (unconfirmed)"), body);
        assertTrue(body.contains("### 🗣️ Transcript"), body);
        assertTrue(body.contains("you are a badword"), body);
        assertTrue(body.contains("### 📋 Details"), body);
        assertTrue(body.contains("unconfirmed"), body);
        assertTrue(body.contains("none (alert only)"), body);
        assertTrue(body.contains("badword, worse"), body);
        assertTrue(body.contains("severe"), body);
        assertTrue(body.contains("-# VoiceWarden alerts · Lobby One · lobby-1"), body);
        assertFalse(payload.has("attachments"));
    }

    @Test
    void confirmedFlagShowsActionAndNoUnconfirmedTag() throws Exception {
        client(target(IncludeTranscriptMode.BOTH, false, false), WebhookTarget.disabled(), WebhookTarget.disabled(), 0);
        client.sendFlag(flag(ConfirmationState.CONFIRMED, null)).get(5, TimeUnit.SECONDS);
        String body = allText(container(json(server.received().getFirst())));
        assertFalse(body.contains(DiscordMessages.UNCONFIRMED_TAG));
        assertTrue(body.contains("**Action** mute"), body);
        assertFalse(body.contains("Lobby One"));
    }

    @Test
    void includeTranscriptModesControlFlagAndMuteContainers() {
        for (IncludeTranscriptMode mode : IncludeTranscriptMode.values()) {
            WebhookTarget target = target(mode, false, false);
            DiscordMessages messages = new DiscordMessages(
                    new DiscordWebhookConfig(target, target, target, 5, 0, 20, "Lobby", "lobby"));
            String flagText = allText(container(messages.flag(flag(ConfirmationState.CONFIRMED, null), false).payload()));
            String muteText = allText(container(messages.mute(
                    MuteWebhookPayload.muted(muteRecord(), "muted transcript", null, TS), false).payload()));
            assertEquals(mode.includesFlagged(), flagText.contains("### 🗣️ Transcript"), "flag container for " + mode);
            assertEquals(mode.includesMuted(), muteText.contains("### 🗣️ Transcript"), "mute container for " + mode);
        }
        assertEquals(IncludeTranscriptMode.MUTED, IncludeTranscriptMode.parse("muted"));
        assertEquals(IncludeTranscriptMode.NONE, IncludeTranscriptMode.fromFlag(false, IncludeTranscriptMode.MUTED));
    }

    @Test
    void longTextIsClampedToDiscordLimits() {
        WebhookTarget target = target(IncludeTranscriptMode.BOTH, false, false);
        DiscordMessages messages = new DiscordMessages(new DiscordWebhookConfig(target, target, target, 5, 0, 20, "L", "l"));
        String huge = "x".repeat(10_000);
        FlagWebhookPayload payload = new FlagWebhookPayload(STEVE, "y".repeat(400), FlagSeverity.MILD,
                ConfirmationState.CONFIRMED, List.of(huge), huge, null, null, TS);
        List<String> texts = textDisplays(container(messages.flag(payload, false).payload()));
        assertTrue(texts.get(0).lines().findFirst().orElse("").length() <= DiscordMessages.MAX_TITLE + 3);
        assertTrue(texts.stream().allMatch(text -> text.length() <= 4000));
    }

    @Test
    void componentsV2AggregateTextNeverExceedsFourThousandCharacters() {
        WebhookTarget target = new WebhookTarget(true, server.url(), "VoiceWarden", "", 0x123456,
                "Voice flag " + "T".repeat(300), "Footer " + "F".repeat(2500),
                List.of("111111111111111111", "222222222222222222"),
                IncludeTranscriptMode.BOTH, true, false);
        DiscordMessages messages = new DiscordMessages(new DiscordWebhookConfig(target, target, target, 5, 0, 20, "Lobby", "lobby-1"));
        FlagWebhookPayload payload = new FlagWebhookPayload(STEVE, "Steve", FlagSeverity.SEVERE,
                ConfirmationState.CONFIRMED, List.of("w".repeat(2000)), "t".repeat(5000), VoiceAction.MUTE, null, TS);
        JsonObject root = messages.flag(payload, false).payload();
        int totalChars = 0;
        for (JsonElement element : root.getAsJsonArray("components")) {
            JsonObject comp = element.getAsJsonObject();
            int type = comp.get("type").getAsInt();
            if (type == DiscordMessages.TYPE_TEXT_DISPLAY) {
                totalChars += comp.get("content").getAsString().length();
            } else if (type == DiscordMessages.TYPE_CONTAINER) {
                for (String text : textDisplays(comp)) {
                    totalChars += text.length();
                }
            }
        }
        assertTrue(totalChars <= DiscordMessages.MAX_TOTAL_TEXT_CHARS, "aggregate text was " + totalChars);
    }

    private static List<ConversationLine> nearby() {
        return List.of(
                new ConversationLine(ALEX, "Alex_Two", TS.minusSeconds(20), "who took my\ndiamonds", null),
                new ConversationLine(JORDAN, "Jordan", TS.minusSeconds(5), "not me *honest*", FlagSeverity.MILD));
    }

    private static FlagWebhookPayload flagWithContext(String transcript, List<ConversationLine> context) {
        return new FlagWebhookPayload(STEVE, "Steve", FlagSeverity.SEVERE, ConfirmationState.CONFIRMED, List.of("badword"),
                transcript, VoiceAction.MUTE, null, TS, context);
    }

    private static int aggregateText(JsonObject root) {
        int total = 0;
        for (JsonElement element : root.getAsJsonArray("components")) {
            JsonObject comp = element.getAsJsonObject();
            int type = comp.get("type").getAsInt();
            if (type == DiscordMessages.TYPE_TEXT_DISPLAY) {
                total += comp.get("content").getAsString().length();
            } else if (type == DiscordMessages.TYPE_CONTAINER) {
                total += textDisplays(comp).stream().mapToInt(String::length).sum();
            }
        }
        return total;
    }

    @Test
    void nearbyConversationRendersBetweenTranscriptAndDetails() {
        WebhookTarget target = target(IncludeTranscriptMode.BOTH, false, false);
        DiscordMessages messages = new DiscordMessages(new DiscordWebhookConfig(target, target, target, 5, 0, 20, "L", "l"));

        JsonObject box = container(messages.flag(flagWithContext("you are a badword", nearby()), false).payload());
        List<String> texts = textDisplays(box);

        assertEquals(5, texts.size(), texts.toString());
        assertTrue(texts.get(1).startsWith("### 🗣️ Transcript"), texts.get(1));
        assertEquals("### 🕘 Nearby conversation\n"
                + "-# **Alex\\_Two** · <t:" + TS.minusSeconds(20).getEpochSecond() + ":T> — who took my diamonds\n"
                + "-# **Jordan** · <t:" + TS.minusSeconds(5).getEpochSecond() + ":T> — not me \\*honest\\*", texts.get(2));
        assertTrue(texts.get(3).startsWith("### 📋 Details"), texts.get(3));
        assertTrue(texts.get(4).startsWith("-# VoiceWarden alerts"), texts.get(4));
        JsonArray children = box.getAsJsonArray("components");
        assertEquals(DiscordMessages.TYPE_SEPARATOR, children.get(3).getAsJsonObject().get("type").getAsInt());
    }

    @Test
    void emptyNearbyConversationLeavesTheCardUnchanged() {
        WebhookTarget target = target(IncludeTranscriptMode.BOTH, true, false);
        DiscordMessages messages = new DiscordMessages(new DiscordWebhookConfig(target, target, target, 5, 0, 20, "L", "l"));

        JsonObject legacy = messages.flag(flag(ConfirmationState.CONFIRMED, null), false).payload();
        JsonObject empty = messages.flag(new FlagWebhookPayload(STEVE, "Steve", FlagSeverity.SEVERE, ConfirmationState.CONFIRMED,
                List.of("badword", "worse"), "you are a badword", VoiceAction.MUTE, null, TS, List.of()), false).payload();

        assertEquals(legacy, empty);
        assertFalse(allText(container(empty)).contains("Nearby conversation"));
    }

    @Test
    void nearbyConversationFollowsEachWebhooksTranscriptMode() {
        for (IncludeTranscriptMode mode : IncludeTranscriptMode.values()) {
            WebhookTarget target = target(mode, false, false);
            DiscordMessages messages = new DiscordMessages(new DiscordWebhookConfig(target, target, target, 5, 0, 20, "L", "l"));
            String flagText = allText(container(messages.flag(flagWithContext("badword", nearby()), false).payload()));
            String muteText = allText(container(messages.mute(
                    MuteWebhookPayload.muted(muteRecord(), "badword", null, TS, nearby()), false).payload()));
            VoiceReportRecord record = VoiceReportRecord.open(ALEX, "Alex", STEVE, "Steve", 5, "[12:00:00] badword", TS, "l");
            String reportText = allText(container(messages.report(
                    new ReportWebhookPayload(record, "[12:00:00] badword", nearby()), false).payload()));
            assertEquals(mode.includesFlagged(), flagText.contains(DiscordMessages.CONTEXT_HEADING), "flag for " + mode);
            assertEquals(mode.includesMuted(), muteText.contains(DiscordMessages.CONTEXT_HEADING), "mute for " + mode);
            assertEquals(mode != IncludeTranscriptMode.NONE, reportText.contains(DiscordMessages.CONTEXT_HEADING), "report for " + mode);
        }
        WebhookTarget target = target(IncludeTranscriptMode.BOTH, false, false);
        DiscordMessages messages = new DiscordMessages(new DiscordWebhookConfig(target, target, target, 5, 0, 20, "L", "l"));
        MuteWebhookPayload unmute = MuteWebhookPayload.unmuted(muteRecord(), "ModAlex", TS);
        assertTrue(unmute.contextLines().isEmpty());
        assertFalse(allText(container(messages.mute(unmute, false).payload())).contains(DiscordMessages.CONTEXT_HEADING));
    }

    @Test
    void nearbyConversationIsClampedIntoTheFourThousandCharacterBudget() {
        WebhookTarget target = new WebhookTarget(true, server.url(), "VoiceWarden", "", 0x123456,
                "Voice flag", "Footer " + "F".repeat(300), List.of("111111111111111111"),
                IncludeTranscriptMode.BOTH, true, false);
        DiscordMessages messages = new DiscordMessages(new DiscordWebhookConfig(target, target, target, 5, 0, 20, "Lobby", "lobby-1"));
        List<ConversationLine> context = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            context.add(new ConversationLine(ALEX, "Alex", TS.minusSeconds(60 - i), "line " + i + " " + "z".repeat(400), null));
        }

        JsonObject root = messages.flag(flagWithContext("t".repeat(5000), context), false).payload();
        List<String> texts = textDisplays(container(root));
        String section = texts.stream().filter(text -> text.startsWith(DiscordMessages.CONTEXT_HEADING)).findFirst().orElseThrow();
        List<String> rows = section.lines().skip(1).toList();

        assertTrue(aggregateText(root) <= DiscordMessages.MAX_TOTAL_TEXT_CHARS, "aggregate text was " + aggregateText(root));
        assertFalse(rows.isEmpty());
        assertTrue(rows.size() < context.size());
        assertTrue(rows.getLast().contains("line 19 "), rows.getLast());
        assertTrue(rows.stream().allMatch(row -> row.length() <= DiscordMessages.MAX_CONTEXT_LINE && row.startsWith("-# **Alex**")));
        assertTrue(section.length() <= DiscordMessages.CONTEXT_RESERVE, "section was " + section.length());
        assertTrue(texts.stream().anyMatch(text -> text.startsWith("### 🗣️ Transcript")));
    }

    @Test
    void nearbyConversationIsDroppedWhenNothingFits() {
        List<String> pings = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            pings.add(String.valueOf(100_000_000_000_000_000L + i));
        }
        WebhookTarget target = new WebhookTarget(true, server.url(), "VoiceWarden", "", 0x123456,
                "Voice flag", "Footer " + "F".repeat(2500), pings, IncludeTranscriptMode.BOTH, true, false);
        DiscordMessages messages = new DiscordMessages(new DiscordWebhookConfig(target, target, target, 5, 0, 20, "Lobby", "lobby-1"));
        FlagWebhookPayload payload = new FlagWebhookPayload(STEVE, "Steve", FlagSeverity.SEVERE, ConfirmationState.CONFIRMED,
                List.of("w".repeat(2000)), "t".repeat(5000), VoiceAction.MUTE, null, TS, nearby());

        JsonObject root = messages.flag(payload, false).payload();
        String body = allText(container(root));

        assertTrue(aggregateText(root) <= DiscordMessages.MAX_TOTAL_TEXT_CHARS, "aggregate text was " + aggregateText(root));
        assertTrue(body.contains("### 🗣️ Transcript"), body);
        assertFalse(body.contains(DiscordMessages.CONTEXT_HEADING), body);
    }

    @Test
    void reportCardShowsTheTargetTranscriptAndNearbyLinesSeparately() {
        WebhookTarget target = target(IncludeTranscriptMode.BOTH, false, false);
        DiscordMessages messages = new DiscordMessages(new DiscordWebhookConfig(target, target, target, 5, 0, 20, "L", "l"));
        VoiceReportRecord record = VoiceReportRecord.open(ALEX, "Alex", STEVE, "Steve", 5,
                "[12:00:01] badword\n[Jordan] [12:00:05] not me", TS, "l");

        String text = allText(container(messages.report(new ReportWebhookPayload(record, "[12:00:01] badword", nearby()), false).payload()));

        assertTrue(text.contains(">>> [12:00:01] badword"), text);
        assertFalse(text.contains("[Jordan] [12:00:05]"), text);
        assertTrue(text.contains("-# **Jordan** · "), text);
        assertEquals(record.transcriptSnapshot(), new ReportWebhookPayload(record).transcript());
    }

    @Test
    void muteAndUnmuteContainers() throws Exception {
        WebhookTarget mute = target(IncludeTranscriptMode.MUTED, true, true);
        client(WebhookTarget.disabled(), mute, WebhookTarget.disabled(), 0);

        assertTrue(client.sendMute(MuteWebhookPayload.muted(muteRecord(), "said slurs", null, TS)).get(5, TimeUnit.SECONDS).success());
        assertTrue(client.sendMute(MuteWebhookPayload.unmuted(muteRecord(), "ModAlex", TS)).get(5, TimeUnit.SECONDS).success());

        JsonObject mutedBox = container(json(server.received().get(0)));
        String mutedText = allText(mutedBox);
        assertTrue(mutedText.contains("## Voice flag: Steve"), mutedText);
        assertTrue(mutedText.contains("**Reason** Slurs"), mutedText);
        assertTrue(mutedText.contains("<t:" + TS.plus(Duration.ofMinutes(30)).getEpochSecond()), mutedText);
        assertTrue(mutedText.contains("said slurs"), mutedText);

        JsonObject unmutedBox = container(json(server.received().get(1)));
        assertEquals(DiscordMessages.COLOR_UNMUTE, unmutedBox.get("accent_color").getAsInt());
        String unmutedText = allText(unmutedBox);
        assertTrue(unmutedText.contains("lifted: Steve"), unmutedText);
        assertTrue(unmutedText.contains("**Unmuted by** ModAlex"), unmutedText);
    }

    @Test
    void reportContainer() throws Exception {
        WebhookTarget report = target(IncludeTranscriptMode.BOTH, true, false);
        client(WebhookTarget.disabled(), WebhookTarget.disabled(), report, 0);
        VoiceReportRecord record = VoiceReportRecord.open(UUID.randomUUID(), "Alex", STEVE, "Steve", 5,
                "Steve: something rude", TS, "lobby-1").withId(42);

        assertTrue(client.sendReport(new ReportWebhookPayload(record)).get(5, TimeUnit.SECONDS).success());

        String text = allText(container(json(server.received().getFirst())));
        assertTrue(text.contains("#42"), text);
        assertTrue(text.contains("5 minutes"), text);
        assertTrue(text.contains("Alex"), text);
        assertTrue(text.contains("something rude"), text);
    }

    @Test
    void reportWithAudioSendsTheCardThenAPlayableClip() throws Exception {
        client(WebhookTarget.disabled(), WebhookTarget.disabled(), target(IncludeTranscriptMode.BOTH, true, true), 0);
        VoiceReportRecord record = VoiceReportRecord.open(UUID.randomUUID(), "Alex", STEVE, "Steve", 5,
                "[12:00:00] something rude", TS, "lobby-1").withId(7);
        short[] pcm = sine(24_000);

        WebhookResult result = client.sendReport(new ReportWebhookPayload(record, pcm)).get(5, TimeUnit.SECONDS);

        assertTrue(result.success(), result.summary());
        assertEquals(2, server.received().size());
        JsonObject cardPayload = json(server.received().get(0));
        assertFalse(cardPayload.has("attachments"));
        String text = allText(container(cardPayload));
        assertTrue(text.contains("something rude"), text);
        assertTrue(text.contains("**Audio** 1.5s clip below"), text);

        RecordingWebhookServer.Received clipRequest = server.received().get(1);
        Matcher boundaryMatch = Pattern.compile("multipart/form-data; boundary=(\\S+)").matcher(clipRequest.contentType());
        assertTrue(boundaryMatch.matches(), clipRequest.contentType());
        Part file = parseMultipart(clipRequest.body(), boundaryMatch.group(1)).get("files[0]");
        assertNotNull(file);
        assertTrue(file.headers().contains("Content-Type: audio/wav"));
        assertTrue(file.headers().contains("filename=\"voicewarden-Steve-" + TS.toEpochMilli() + ".wav\""), file.headers());
        assertArrayEquals(pcm, WavEncoder.decodePcm16Mono(file.body()));
    }

    @Test
    void audioOnlyReportSaysTheClipIsAttachedInsteadOfNoSpeech() {
        WebhookTarget withAudio = target(IncludeTranscriptMode.BOTH, false, true);
        DiscordMessages messages = new DiscordMessages(new DiscordWebhookConfig(withAudio, withAudio, withAudio, 5, 0, 20, "L", "l"));
        VoiceReportRecord silent = VoiceReportRecord.open(UUID.randomUUID(), "Alex", STEVE, "Steve", 5, "", TS, "lobby-1");

        WebhookMessage clip = messages.report(new ReportWebhookPayload(silent, sine(8_000)), false);
        String clipText = allText(container(clip.payload()));
        assertTrue(clip.hasAttachment());
        assertTrue(clipText.contains(DiscordMessages.NO_SPEECH_WITH_AUDIO), clipText);
        assertFalse(clipText.contains(DiscordMessages.NO_SPEECH_CAPTURED), clipText);

        WebhookMessage plain = messages.report(new ReportWebhookPayload(silent), false);
        String plainText = allText(container(plain.payload()));
        assertFalse(plain.hasAttachment());
        assertTrue(plainText.contains(DiscordMessages.NO_SPEECH_CAPTURED), plainText);
        assertFalse(plainText.contains("**Audio**"), plainText);

        WebhookTarget noAudio = target(IncludeTranscriptMode.BOTH, false, false);
        DiscordMessages audioOff = new DiscordMessages(new DiscordWebhookConfig(noAudio, noAudio, noAudio, 5, 0, 20, "L", "l"));
        WebhookMessage ignored = audioOff.report(new ReportWebhookPayload(silent, sine(8_000)), false);
        assertFalse(ignored.hasAttachment());
        assertTrue(allText(container(ignored.payload())).contains(DiscordMessages.NO_SPEECH_CAPTURED));
    }

    @Test
    void includeAudioSendsV2CardThenPlayableWavAttachmentFollowUp() throws Exception {
        client(target(IncludeTranscriptMode.BOTH, true, true), WebhookTarget.disabled(), WebhookTarget.disabled(), 0);
        short[] pcm = sine(16_000);

        WebhookResult result = client.sendFlag(new FlagWebhookPayload(STEVE, "St3ve:/evil", FlagSeverity.MODERATE,
                ConfirmationState.CONFIRMED, List.of("w"), "t", VoiceAction.WARN, pcm, TS)).get(5, TimeUnit.SECONDS);

        assertTrue(result.success(), result.summary());
        assertEquals(2, server.received().size());

        RecordingWebhookServer.Received cardRequest = server.received().get(0);
        assertTrue(cardRequest.contentType().startsWith("application/json"));
        JsonObject cardPayload = json(cardRequest);
        assertFalse(cardPayload.has("attachments"));
        JsonObject box = container(cardPayload);
        assertTrue(allText(box).contains("1.0s clip below"));

        RecordingWebhookServer.Received clipRequest = server.received().get(1);
        Matcher boundaryMatch = Pattern.compile("multipart/form-data; boundary=(\\S+)").matcher(clipRequest.contentType());
        assertTrue(boundaryMatch.matches(), clipRequest.contentType());
        Map<String, Part> parts = parseMultipart(clipRequest.body(), boundaryMatch.group(1));

        Part payloadPart = parts.get("payload_json");
        assertNotNull(payloadPart);
        assertTrue(payloadPart.headers().contains("Content-Type: application/json"));
        JsonObject clipPayload = JsonParser.parseString(new String(payloadPart.body(), StandardCharsets.UTF_8)).getAsJsonObject();
        assertFalse(clipPayload.has("flags"));
        assertFalse(clipPayload.has("components"));
        assertEquals("VoiceWarden", clipPayload.get("username").getAsString());
        assertEquals("https://example.com/a.png", clipPayload.get("avatar_url").getAsString());

        Part file = parts.get("files[0]");
        assertNotNull(file);
        assertTrue(file.headers().contains("Content-Type: audio/wav"));
        String expectedName = "voicewarden-St3ve__evil-" + TS.toEpochMilli() + ".wav";
        assertTrue(file.headers().contains("filename=\"" + expectedName + "\""), file.headers());
        assertEquals(expectedName,
                clipPayload.getAsJsonArray("attachments").get(0).getAsJsonObject().get("filename").getAsString());
        assertEquals(0, clipPayload.getAsJsonArray("attachments").get(0).getAsJsonObject().get("id").getAsInt());
        assertEquals(16_000, WavEncoder.sampleRate(file.body()));
        assertArrayEquals(pcm, WavEncoder.decodePcm16Mono(file.body()));
    }

    @Test
    void micSpamPostsToTheFlagWebhookWithMeasurementsAndTheClip() throws Exception {
        client(target(IncludeTranscriptMode.BOTH, false, true), WebhookTarget.disabled(), WebhookTarget.disabled(), 0);
        short[] pcm = sine(8_000);

        WebhookResult result = client.sendMicSpam(new MicSpamWebhookPayload(STEVE, "Steve", AcousticAbuseEvent.Kind.CLIPPING,
                30_000, 0.42, 800, VoiceAction.MUTE, pcm, TS)).get(5, TimeUnit.SECONDS);

        assertTrue(result.success(), result.summary());
        assertEquals(2, server.received().size());
        JsonObject box = container(json(server.received().get(0)));
        String text = allText(box);
        assertTrue(text.startsWith("## Voice mic spam: Steve\nSustained clipped, distorted microphone audio · <t:"), text);
        assertTrue(text.contains("**Kind** clipping · **Action** mute"), text);
        assertTrue(text.contains("**Peak loudness** 92% · **Clipped samples** 42% · **Lasted** 0.8s"), text);
        assertTrue(text.contains("**Audio** 0.5s clip below"), text);
        assertFalse(text.contains("Transcript"), text);
        assertEquals(0x123456, box.get("accent_color").getAsInt());
    }

    @Test
    void micSpamIsSkippedWhenTheFlagWebhookIsOff() throws Exception {
        client(WebhookTarget.disabled(), target(IncludeTranscriptMode.BOTH, false, true), WebhookTarget.disabled(), 0);

        WebhookResult result = client.sendMicSpam(new MicSpamWebhookPayload(STEVE, "Steve", AcousticAbuseEvent.Kind.LOUDNESS,
                20_000, 0, 800, VoiceAction.WARN, new short[0], TS)).get(5, TimeUnit.SECONDS);

        assertFalse(result.success());
        assertTrue(server.received().isEmpty());
    }

    @Test
    void failedV2CardSkipsAudioFollowUp() throws Exception {
        server.reply(400, Map.of(), "{\"message\": \"Invalid Form Body\"}");
        client(target(IncludeTranscriptMode.BOTH, true, true), WebhookTarget.disabled(), WebhookTarget.disabled(), 0);

        WebhookResult result = client.sendFlag(flag(ConfirmationState.CONFIRMED, sine(16_000))).get(5, TimeUnit.SECONDS);

        assertEquals(WebhookResult.Status.FAILED, result.status());
        assertEquals(1, server.received().size());
    }

    @Test
    void audioIsNotAttachedWhenDisabledOrEmpty() throws Exception {
        client(target(IncludeTranscriptMode.BOTH, true, false), WebhookTarget.disabled(), WebhookTarget.disabled(), 0);
        client.sendFlag(flag(ConfirmationState.CONFIRMED, sine(100))).get(5, TimeUnit.SECONDS);
        assertTrue(server.received().getFirst().contentType().startsWith("application/json"));
        client.close();

        client(target(IncludeTranscriptMode.BOTH, true, true), WebhookTarget.disabled(), WebhookTarget.disabled(), 0);
        client.sendFlag(flag(ConfirmationState.CONFIRMED, new short[0])).get(5, TimeUnit.SECONDS);
        assertTrue(server.received().get(1).contentType().startsWith("application/json"));
    }

    @Test
    void retriesServerErrorsWithBackoff() throws Exception {
        server.reply(500);
        server.reply(502);
        client(target(IncludeTranscriptMode.BOTH, true, false), WebhookTarget.disabled(), WebhookTarget.disabled(), 3);

        WebhookResult result = client.sendFlag(flag(ConfirmationState.CONFIRMED, null)).get(5, TimeUnit.SECONDS);

        assertTrue(result.success(), result.summary());
        assertEquals(3, result.attempts());
        assertEquals(3, server.received().size());
        long firstGapMs = gapMs(0, 1);
        long secondGapMs = gapMs(1, 2);
        assertTrue(firstGapMs >= 15, "first backoff " + firstGapMs + "ms");
        assertTrue(secondGapMs >= 35, "second backoff " + secondGapMs + "ms");
    }

    @Test
    void honoursRetryAfterOn429() throws Exception {
        server.reply(429, Map.of("Retry-After", "0.3", "Content-Type", "application/json"), "{\"retry_after\":0.3}");
        client(target(IncludeTranscriptMode.BOTH, true, false), WebhookTarget.disabled(), WebhookTarget.disabled(), 3);

        WebhookResult result = client.sendFlag(flag(ConfirmationState.CONFIRMED, null)).get(5, TimeUnit.SECONDS);

        assertTrue(result.success(), result.summary());
        assertEquals(2, result.attempts());
        assertTrue(gapMs(0, 1) >= 280, "waited " + gapMs(0, 1) + "ms");
    }

    @Test
    void readsRetryAfterFromTheBodyWhenTheHeaderIsMissing() throws Exception {
        server.reply(429, Map.of("Content-Type", "application/json"), "{\"message\":\"rate limited\",\"retry_after\":0.25}");
        client(target(IncludeTranscriptMode.BOTH, true, false), WebhookTarget.disabled(), WebhookTarget.disabled(), 1);

        WebhookResult result = client.sendFlag(flag(ConfirmationState.CONFIRMED, null)).get(5, TimeUnit.SECONDS);

        assertTrue(result.success(), result.summary());
        assertTrue(gapMs(0, 1) >= 230, "waited " + gapMs(0, 1) + "ms");
    }

    @Test
    void doesNotRetryClientErrors() throws Exception {
        server.reply(400, Map.of(), "{\"message\": \"Invalid Form Body\", \"code\": 50035}");
        server.reply(404, Map.of(), "{\"message\": \"Unknown Webhook\"}");
        client(target(IncludeTranscriptMode.BOTH, true, false), WebhookTarget.disabled(), WebhookTarget.disabled(), 3);

        WebhookResult badRequest = client.sendFlag(flag(ConfirmationState.CONFIRMED, null)).get(5, TimeUnit.SECONDS);
        WebhookResult notFound = client.sendFlag(flag(ConfirmationState.CONFIRMED, null)).get(5, TimeUnit.SECONDS);

        assertEquals(WebhookResult.Status.FAILED, badRequest.status());
        assertEquals(400, badRequest.statusCode());
        assertEquals(1, badRequest.attempts());
        assertTrue(badRequest.message().contains("Invalid Form Body"));
        assertEquals(404, notFound.statusCode());
        assertEquals(1, notFound.attempts());
        assertEquals(2, server.received().size());
    }

    @Test
    void givesUpAfterMaxRetries() throws Exception {
        for (int i = 0; i < 3; i++) {
            server.reply(503);
        }
        client(target(IncludeTranscriptMode.BOTH, true, false), WebhookTarget.disabled(), WebhookTarget.disabled(), 2);

        WebhookResult result = client.sendFlag(flag(ConfirmationState.CONFIRMED, null)).get(5, TimeUnit.SECONDS);

        assertEquals(WebhookResult.Status.FAILED, result.status());
        assertEquals(503, result.statusCode());
        assertEquals(3, result.attempts());
        assertEquals(3, server.received().size());
    }

    @Test
    void connectionFailuresAreReportedWithoutLeakingTheUrl() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String secretUrl = "http://127.0.0.1:" + closedPort + "/api/webhooks/1/very-secret";
        client(target(IncludeTranscriptMode.BOTH, true, false).withUrl(secretUrl),
                target(IncludeTranscriptMode.BOTH, true, false).withUrl("not a url ::"),
                WebhookTarget.disabled(), 1);

        WebhookResult refused = client.sendTest(TestTarget.FLAG).get(5, TimeUnit.SECONDS);
        WebhookResult invalid = client.sendTest(TestTarget.MUTE).get(5, TimeUnit.SECONDS);

        assertEquals(WebhookResult.Status.FAILED, refused.status());
        assertEquals(2, refused.attempts());
        assertFalse(refused.summary().contains("very-secret"), refused.summary());
        assertEquals(WebhookResult.Status.FAILED, invalid.status());
        assertTrue(invalid.message().contains("not a valid"));
        assertFalse(invalid.summary().contains("not a url"));
        assertFalse(target(IncludeTranscriptMode.BOTH, true, false).withUrl(secretUrl).toString().contains("very-secret"));
    }

    @Test
    void sendTestReportsEachTarget() throws Exception {
        WebhookTarget flagTarget = target(IncludeTranscriptMode.BOTH, true, true);
        WebhookTarget muteTarget = target(IncludeTranscriptMode.MUTED, true, false);
        client(flagTarget, muteTarget, WebhookTarget.disabled(), 0);

        WebhookResult flagOnly = client.sendTest(TestTarget.FLAG).get(5, TimeUnit.SECONDS);
        assertTrue(flagOnly.success(), flagOnly.summary());
        assertEquals("flag", flagOnly.target());
        assertEquals(2, server.received().size());
        assertTrue(server.received().get(0).contentType().startsWith("application/json"));
        assertTrue(server.received().get(1).contentType().startsWith("multipart/form-data"));

        server.reply(400, Map.of(), "{\"message\":\"bad\"}");
        WebhookResult all = client.sendTest(TestTarget.ALL).get(5, TimeUnit.SECONDS);
        assertEquals("all", all.target());
        assertEquals(3, all.parts().size());
        assertEquals(1, all.parts().stream().filter(part -> part.status() == WebhookResult.Status.FAILED).count());
        assertEquals(WebhookResult.Status.SKIPPED, all.parts().get(2).status());
        assertEquals(WebhookResult.Status.FAILED, all.status());
        assertTrue(all.summary().contains("report: skipped (report webhook is disabled)"), all.summary());
        assertTrue(all.summary().contains("HTTP 400"), all.summary());

        WebhookResult muteOnly = client.sendTest(TestTarget.MUTE).get(5, TimeUnit.SECONDS);
        assertTrue(muteOnly.success(), muteOnly.summary());
        String muteText = allText(container(json(server.received().getLast())));
        assertTrue(muteText.contains("## [Test] "), muteText);
        assertEquals(TestTarget.MUTE, TestTarget.parse("mute"));
    }

    @Test
    void disabledTargetsAreSkippedWithoutARequest() throws Exception {
        client(WebhookTarget.disabled(), target(IncludeTranscriptMode.BOTH, true, false).withUrl(""),
                WebhookTarget.disabled(), 0);
        WebhookResult flag = client.sendFlag(flag(ConfirmationState.CONFIRMED, null)).get(5, TimeUnit.SECONDS);
        WebhookResult mute = client.sendTest(TestTarget.MUTE).get(5, TimeUnit.SECONDS);
        assertEquals(WebhookResult.Status.SKIPPED, flag.status());
        assertEquals(WebhookResult.Status.SKIPPED, mute.status());
        assertTrue(mute.message().contains("no URL"));
        assertTrue(server.received().isEmpty());
    }

    private long gapMs(int first, int second) {
        List<RecordingWebhookServer.Received> requests = server.received();
        return TimeUnit.NANOSECONDS.toMillis(requests.get(second).receivedNanos() - requests.get(first).receivedNanos());
    }

    private record Part(String headers, byte[] body) {
    }

    private static Map<String, Part> parseMultipart(byte[] body, String boundary) {
        String latin = new String(body, StandardCharsets.ISO_8859_1);
        String delimiter = "--" + boundary;
        assertTrue(latin.endsWith(delimiter + "--\r\n"), "multipart body must end with the closing boundary");
        Map<String, Part> parts = new HashMap<>();
        int cursor = latin.indexOf(delimiter);
        while (cursor >= 0) {
            int headerStart = cursor + delimiter.length();
            if (latin.startsWith("--", headerStart)) {
                break;
            }
            headerStart += 2;
            int headerEnd = latin.indexOf("\r\n\r\n", headerStart);
            int next = latin.indexOf("\r\n" + delimiter, headerEnd);
            String headers = latin.substring(headerStart, headerEnd);
            byte[] partBody = Arrays.copyOfRange(body, headerEnd + 4, next);
            Matcher name = Pattern.compile("name=\"([^\"]+)\"").matcher(headers);
            assertTrue(name.find(), headers);
            parts.put(name.group(1), new Part(headers, partBody));
            cursor = next + 2;
        }
        return parts;
    }
}
