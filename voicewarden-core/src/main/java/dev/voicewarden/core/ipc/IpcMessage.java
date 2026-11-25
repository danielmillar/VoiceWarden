package dev.voicewarden.core.ipc;

import dev.voicewarden.api.ConfirmationState;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public sealed interface IpcMessage {

    IpcMessageType type();

    record AuthRequest(IpcAuthToken token) implements IpcMessage {
        public AuthRequest {
            Objects.requireNonNull(token, "token");
        }

        @Override
        public IpcMessageType type() {
            return IpcMessageType.AUTH_REQ;
        }
    }

    record AuthOk(String engineVersion, String profile) implements IpcMessage {
        public AuthOk {
            Objects.requireNonNull(engineVersion, "engineVersion");
            Objects.requireNonNull(profile, "profile");
        }

        @Override
        public IpcMessageType type() {
            return IpcMessageType.AUTH_OK;
        }
    }

    record AuthFail(String reason) implements IpcMessage {
        public AuthFail {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public IpcMessageType type() {
            return IpcMessageType.AUTH_FAIL;
        }
    }

    record Ping(long nonce) implements IpcMessage {
        @Override
        public IpcMessageType type() {
            return IpcMessageType.PING;
        }
    }

    record Pong(long nonce) implements IpcMessage {
        @Override
        public IpcMessageType type() {
            return IpcMessageType.PONG;
        }
    }

    record AudioLiveChunk(UUID playerId, long utteranceId, int chunkIndex, boolean endOfUtterance, short[] pcm16)
            implements IpcMessage {
        public AudioLiveChunk {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(pcm16, "pcm16");
        }

        @Override
        public IpcMessageType type() {
            return IpcMessageType.AUDIO_LIVE_CHUNK;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof AudioLiveChunk other
                    && playerId.equals(other.playerId)
                    && utteranceId == other.utteranceId
                    && chunkIndex == other.chunkIndex
                    && endOfUtterance == other.endOfUtterance
                    && Arrays.equals(pcm16, other.pcm16);
        }

        @Override
        public int hashCode() {
            return Objects.hash(playerId, utteranceId, chunkIndex, endOfUtterance, Arrays.hashCode(pcm16));
        }

        @Override
        public String toString() {
            return "AudioLiveChunk[playerId=" + playerId + ", utteranceId=" + utteranceId + ", chunkIndex=" + chunkIndex
                    + ", endOfUtterance=" + endOfUtterance + ", samples=" + pcm16.length + "]";
        }
    }

    record AudioFinal(UUID playerId, long utteranceId, boolean requestDoubleCheck, short[] pcm16) implements IpcMessage {
        public AudioFinal {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(pcm16, "pcm16");
        }

        @Override
        public IpcMessageType type() {
            return IpcMessageType.AUDIO_FINAL;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof AudioFinal other
                    && playerId.equals(other.playerId)
                    && utteranceId == other.utteranceId
                    && requestDoubleCheck == other.requestDoubleCheck
                    && Arrays.equals(pcm16, other.pcm16);
        }

        @Override
        public int hashCode() {
            return Objects.hash(playerId, utteranceId, requestDoubleCheck, Arrays.hashCode(pcm16));
        }

        @Override
        public String toString() {
            return "AudioFinal[playerId=" + playerId + ", utteranceId=" + utteranceId + ", requestDoubleCheck="
                    + requestDoubleCheck + ", samples=" + pcm16.length + "]";
        }
    }

    record VerifyRequest(UUID playerId, long utteranceId, String primaryTranscript, short[] pcm16) implements IpcMessage {
        public VerifyRequest {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(primaryTranscript, "primaryTranscript");
            Objects.requireNonNull(pcm16, "pcm16");
        }

        @Override
        public IpcMessageType type() {
            return IpcMessageType.VERIFY_REQUEST;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof VerifyRequest other
                    && playerId.equals(other.playerId)
                    && utteranceId == other.utteranceId
                    && primaryTranscript.equals(other.primaryTranscript)
                    && Arrays.equals(pcm16, other.pcm16);
        }

        @Override
        public int hashCode() {
            return Objects.hash(playerId, utteranceId, primaryTranscript, Arrays.hashCode(pcm16));
        }

        @Override
        public String toString() {
            return "VerifyRequest[playerId=" + playerId + ", utteranceId=" + utteranceId + ", primaryTranscript="
                    + primaryTranscript + ", samples=" + pcm16.length + "]";
        }
    }

    record DropUtterance(UUID playerId, long utteranceId) implements IpcMessage {
        public DropUtterance {
            Objects.requireNonNull(playerId, "playerId");
        }

        @Override
        public IpcMessageType type() {
            return IpcMessageType.DROP_UTTERANCE;
        }
    }

    record TranscriptResult(
            UUID playerId,
            long utteranceId,
            boolean isFinal,
            boolean isLive,
            boolean isVerificationResult,
            ConfirmationState confirmationState,
            String text,
            String language,
            int latencyMs) implements IpcMessage {
        public TranscriptResult {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(confirmationState, "confirmationState");
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(language, "language");
        }

        @Override
        public IpcMessageType type() {
            return IpcMessageType.TRANSCRIPT_RESULT;
        }
    }

    record EngineStatusRequest() implements IpcMessage {
        @Override
        public IpcMessageType type() {
            return IpcMessageType.ENGINE_STATUS_REQ;
        }
    }

    record EngineStatusResponse(
            long uptimeMs,
            long processedLive,
            long processedFinal,
            long processedVerify,
            long droppedOverload,
            int queuedJobs,
            String profile,
            boolean ready) implements IpcMessage {
        public EngineStatusResponse {
            Objects.requireNonNull(profile, "profile");
        }

        @Override
        public IpcMessageType type() {
            return IpcMessageType.ENGINE_STATUS_RESP;
        }
    }

    record Shutdown() implements IpcMessage {
        @Override
        public IpcMessageType type() {
            return IpcMessageType.SHUTDOWN;
        }
    }
}
