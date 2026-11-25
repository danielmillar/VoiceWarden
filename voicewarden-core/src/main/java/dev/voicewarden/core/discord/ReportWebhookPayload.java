package dev.voicewarden.core.discord;

import dev.voicewarden.core.moderation.ConversationLine;
import dev.voicewarden.core.storage.VoiceReportRecord;
import java.util.List;
import java.util.Objects;

public record ReportWebhookPayload(
        VoiceReportRecord report,
        String transcript,
        List<ConversationLine> contextLines,
        short[] pcm16kAudio) {

    public ReportWebhookPayload {
        Objects.requireNonNull(report, "report");
        transcript = transcript == null ? "" : transcript;
        contextLines = contextLines == null ? List.of() : List.copyOf(contextLines);
    }

    public ReportWebhookPayload(VoiceReportRecord report) {
        this(report, report.transcriptSnapshot(), List.of(), null);
    }

    public ReportWebhookPayload(VoiceReportRecord report, short[] pcm16kAudio) {
        this(report, report.transcriptSnapshot(), List.of(), pcm16kAudio);
    }

    public ReportWebhookPayload(VoiceReportRecord report, String transcript, List<ConversationLine> contextLines) {
        this(report, transcript, contextLines, null);
    }

    public boolean hasAudio() {
        return pcm16kAudio != null && pcm16kAudio.length > 0;
    }
}
