package dev.voicewarden.engine;

import dev.voicewarden.api.ConfirmationState;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class FakeSpeechEngine implements SpeechRecognizerEngine {

    final List<String> calls = new CopyOnWriteArrayList<>();
    final List<String> drops = new CopyOnWriteArrayList<>();
    final CountDownLatch firstCallStarted = new CountDownLatch(1);
    final AtomicBoolean closed = new AtomicBoolean();
    private final CountDownLatch gate;
    private final AtomicBoolean gateArmed;
    private final boolean streaming;

    FakeSpeechEngine() {
        this(null);
    }

    FakeSpeechEngine(CountDownLatch gate) {
        this(gate, true);
    }

    FakeSpeechEngine(CountDownLatch gate, boolean streaming) {
        this.gate = gate;
        this.gateArmed = new AtomicBoolean(gate != null);
        this.streaming = streaming;
    }

    static String name(UUID player) {
        return Long.toString(player.getLeastSignificantBits());
    }

    private void enter(String call) {
        calls.add(call);
        firstCallStarted.countDown();
        if (gateArmed.compareAndSet(true, false)) {
            try {
                if (!gate.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test gate never opened");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    @Override
    public boolean supportsStreaming() {
        return streaming;
    }

    @Override
    public boolean hasDoubleCheckVerifier() {
        return true;
    }

    @Override
    public RecognitionOutput feedLiveChunk(UUID playerId, long utteranceId, short[] pcm16k, boolean endOfUtterance) {
        enter("live:" + name(playerId) + ":" + utteranceId);
        return RecognitionOutput.unchecked("live " + pcm16k.length + (endOfUtterance ? " end" : ""), "en");
    }

    @Override
    public RecognitionOutput transcribeFinal(UUID playerId, long utteranceId, short[] pcm16k, boolean requestDoubleCheck) {
        enter("final:" + name(playerId) + ":" + utteranceId);
        if (pcm16k.length == 13) {
            throw new IllegalStateException("simulated decoder failure");
        }
        ConfirmationState state = requestDoubleCheck ? ConfirmationState.CONFIRMED : ConfirmationState.UNCHECKED;
        return new RecognitionOutput("final " + pcm16k.length, "en", state, requestDoubleCheck ? "final" : "");
    }

    @Override
    public VerificationOutput verifyUtterance(UUID playerId, long utteranceId, String primaryTranscript, short[] pcm16k) {
        enter("verify:" + name(playerId) + ":" + utteranceId);
        ConfirmationState state = primaryTranscript.contains("noise")
                ? ConfirmationState.UNCONFIRMED
                : ConfirmationState.CONFIRMED;
        return new VerificationOutput(state, "verified " + primaryTranscript, "en");
    }

    @Override
    public void dropUtterance(UUID playerId, long utteranceId) {
        drops.add(name(playerId) + ":" + utteranceId);
    }

    @Override
    public void close() {
        closed.set(true);
    }
}
