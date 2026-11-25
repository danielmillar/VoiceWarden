package dev.voicewarden.core.config;

public record TranscriptConfig(
        int retentionSeconds,
        int maxLinesPerPlayer,
        int contextLines,
        int contextWindowSeconds,
        int contextRadiusBlocks) {

    public TranscriptConfig {
        if (contextLines < 0 || contextWindowSeconds < 1 || contextRadiusBlocks < 0) {
            throw new IllegalArgumentException("context_lines and context_radius_blocks must not be negative and context_window_seconds must be positive");
        }
    }

    public boolean contextEnabled() {
        return contextLines > 0;
    }

    public boolean sameRetention(TranscriptConfig other) {
        return retentionSeconds == other.retentionSeconds && maxLinesPerPlayer == other.maxLinesPerPlayer;
    }
}
