package dev.danielmillar.voicewarden.stt;

import dev.danielmillar.voicewarden.TestFixtures;
import dev.danielmillar.voicewarden.audio.AudioSegment;
import dev.danielmillar.voicewarden.health.Metrics;
import dev.danielmillar.voicewarden.testutil.Fakes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @ParameterizedTest
    @ValueSource(ints = {1, 9, 10, 15, 30})
    void doubleCheckHearsEveryShortPhraseWholeInAClipTheSecondModelAccepts(int seconds) {
        int samples = seconds * SherpaOnnxEngine.SAMPLE_RATE;
        var clips = TranscriptionService.checkClips(samples);
        assertEquals(0, clips.getFirst()[0]);
        assertEquals(samples, clips.getLast()[1]);
        for (int[] clip : clips) {
            assertTrue(clip[1] - clip[0] <= TranscriptionService.CHECK_CLIP_SAMPLES, "clip too long for Moonshine");
        }
        int phrase = Math.min(samples, TranscriptionService.CHECK_OVERLAP_SAMPLES);
        for (int start = 0; start + phrase <= samples; start += 160) {
            int from = start;
            assertTrue(clips.stream().anyMatch(clip -> clip[0] <= from && from + phrase <= clip[1]),
                    "a phrase at sample " + from + " is cut in every clip");
        }
    }

    @Test void doubleCheckWithoutALoadedModelFailsInsteadOfWaiting() throws Exception {
        var config = Fakes.config(folder);
        var service = new TranscriptionService(folder, null, new Fakes.Executor(false), () -> config,
                new Metrics(), Logger.getAnonymousLogger(), transcript -> { }, ready -> {});
        var segment = new AudioSegment(TestFixtures.PLAYER, "Alex", TestFixtures.AUDIO, 16000, Instant.now(), System.nanoTime(), null, false);
        assertTrue(service.doubleCheck(segment).isCompletedExceptionally());
    }
}
