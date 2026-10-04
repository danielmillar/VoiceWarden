package dev.danielmillar.nevusvoice.stt;

import dev.danielmillar.nevusvoice.TestFixtures;
import dev.danielmillar.nevusvoice.audio.AudioSegment;
import dev.danielmillar.nevusvoice.health.Metrics;
import dev.danielmillar.nevusvoice.testutil.Fakes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class TranscriptionServiceTest {
    @TempDir Path folder;

    @Test void disabledCaptureClearsQueuedAudioAndCanResumeWithoutLoadingNativeCode() throws Exception {
        var config = new AtomicReference<>(Fakes.config(folder));
        var metrics = new Metrics();
        var service = new TranscriptionService(folder, null, new Fakes.Executor(false), config::get,
                metrics, Logger.getAnonymousLogger(), transcript -> fail("Unexpected transcription"), ready -> {});
        var segment = new AudioSegment(TestFixtures.PLAYER, "Alex", TestFixtures.AUDIO, 16000, Instant.now(), System.nanoTime(), null, false);
        service.submit(segment);
        assertEquals(1, service.queueSize());
        config.set(Fakes.with(config.get(), "enabled", false));
        service.discardQueued();
        assertEquals(0, service.queueSize());
        assertEquals(1, metrics.queueDropped.sum());
        service.submit(segment);
        assertEquals(0, service.queueSize());
        config.set(Fakes.with(config.get(), "enabled", true));
        service.submit(segment);
        assertEquals(1, service.queueSize());
        assertTrue(service.closeAsync().isDone());
        service.discardQueued();
        service.submit(segment);
        assertEquals(0, service.queueSize());
    }
}
