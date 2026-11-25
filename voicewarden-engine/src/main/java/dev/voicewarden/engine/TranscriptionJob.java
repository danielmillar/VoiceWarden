package dev.voicewarden.engine;

import java.util.Objects;
import java.util.UUID;

public sealed interface TranscriptionJob {

    UUID playerId();

    long utteranceId();

    long enqueuedNanos();

    default Lane lane() {
        return this instanceof Final ? Lane.FINAL : Lane.PRIORITY;
    }

    enum Lane {
        PRIORITY,
        FINAL
    }

    record Live(UUID playerId, long utteranceId, int chunkIndex, boolean endOfUtterance, short[] pcm16k,
            long enqueuedNanos) implements TranscriptionJob {
        public Live {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(pcm16k, "pcm16k");
        }
    }

    record Final(UUID playerId, long utteranceId, boolean requestDoubleCheck, short[] pcm16k, long enqueuedNanos)
            implements TranscriptionJob {
        public Final {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(pcm16k, "pcm16k");
        }
    }

    record Verify(UUID playerId, long utteranceId, String primaryTranscript, short[] pcm16k, long enqueuedNanos)
            implements TranscriptionJob {
        public Verify {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(primaryTranscript, "primaryTranscript");
            Objects.requireNonNull(pcm16k, "pcm16k");
        }
    }
}
