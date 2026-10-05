package dev.danielmillar.voicewarden.evidence;

import java.nio.file.Path;

/**
 * Evidence / recording storage (the {@code recordings} section of config.yml).
 *
 * @param mode           NONE, FLAGGED (incidents + voice reports) or ALL (every transcribed utterance as well)
 * @param directory      absolute directory evidence is written to
 * @param saveAudio      write audio as WAV next to the JSON metadata
 * @param retentionDays  delete evidence older than this many days (0 = keep forever)
 * @param maxTotalBytes  delete the oldest evidence when the directory exceeds this size (0 = unlimited)
 */
public record EvidenceSettings(Mode mode, Path directory, boolean saveAudio, int retentionDays, long maxTotalBytes) {
    public enum Mode {
        NONE,
        FLAGGED,
        ALL
    }
}
