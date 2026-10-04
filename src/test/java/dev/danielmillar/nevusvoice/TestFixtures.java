package dev.danielmillar.nevusvoice;

import dev.danielmillar.nevusvoice.moderation.*;
import dev.danielmillar.nevusvoice.moderation.rules.*;
import dev.danielmillar.nevusvoice.report.VoiceReport;

import java.time.*;
import java.util.*;

public final class TestFixtures {
    public static final Instant TIME = Instant.parse("2026-10-03T12:34:56Z");
    public static final UUID ID = UUID.fromString("abcdef01-2345-6789-abcd-ef0123456789");
    public static final UUID PLAYER = UUID.fromString("12345678-2345-6789-abcd-ef0123456789");
    public static final short[] AUDIO = {0, 1, -1, 32767, -32768, 42};
    private TestFixtures() {}

    public static Incident incident() { return incident("Alex_*", "hello badword @everyone", ModerationAction.NONE, AUDIO); }

    public static Incident incident(String name, String transcript, ModerationAction action, short[] audio) {
        int offset = transcript.indexOf("badword");
        List<RuleMatch> matches = offset < 0 ? List.of() : List.of(
                new RuleMatch("words", "Profanity", RuleAction.MUTE, 1, "badword", offset, offset + 7),
                new RuleMatch("duplicate", "Profanity", RuleAction.FLAG, 1, "badword", offset, offset + 7));
        return new Incident(ID, TIME, TIME.minusSeconds(2), Duration.ofMillis(500), PLAYER, name, transcript, matches,
                1, 2, 3, action, action.type() == ActionType.MUTE ? 2 : 0, true,
                List.of(new TranscriptLine(TIME.minusSeconds(14), "prior *line* @here")),
                new SpeakerLocation("world", -0.5, 64.9, 12.5), audio, 16_000);
    }

    public static VoiceReport report() {
        return new VoiceReport(ID, TIME, PLAYER, "Reporter_*", PLAYER, "Alex_*", Duration.ofSeconds(90),
                List.of(new TranscriptLine(TIME.minusSeconds(12), "older **words**"), new TranscriptLine(TIME, "latest @here")), AUDIO, 16_000);
    }
}
