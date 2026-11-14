package dev.danielmillar.voicewarden.stt;

import dev.danielmillar.voicewarden.audio.AudioSegment;
import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.health.Metrics;
import dev.danielmillar.voicewarden.moderation.rules.RuleAction;
import dev.danielmillar.voicewarden.moderation.rules.RuleDefinition;
import dev.danielmillar.voicewarden.moderation.rules.RuleEngine;
import dev.danielmillar.voicewarden.moderation.rules.RuleMatch;
import dev.danielmillar.voicewarden.testutil.Fakes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Both models through the real {@link TranscriptionService}, downloading the pinned double-check model if needed.
 * Opt-in: {@code -PsherpaIntegration=/path/to/models}, where the directory holds {@code parakeet-tdt-v2/},
 * {@code silero_vad.onnx} and {@code wavs/0.wav} and {@code wavs/1.wav} from sherpa-onnx's Moonshine test clips.
 */
class DoubleCheckIT {

    private static final Logger LOG = Logger.getLogger("DoubleCheckIT");

    @TempDir Path dataFolder;

    @Test
    void secondModelConfirmsARealHitAndRejectsWhatItDidNotHear() throws Exception {
        String dir = System.getProperty("voicewarden.it.models");
        assumeTrue(dir != null && !dir.isBlank(), "set -PsherpaIntegration=/path/to/models to run");
        Path models = Path.of(dir);
        assumeTrue(Files.exists(models.resolve("wavs/0.wav")));
        Files.createSymbolicLink(dataFolder.resolve("models"), models.toAbsolutePath());

        var base = Fakes.config(dataFolder);
        PluginConfig config = Fakes.with(base, "speechToText", Fakes.with(base.speechToText(), "doubleCheck", true));
        var transcripts = new LinkedBlockingQueue<Transcript>();
        try (HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()) {
            var service = new TranscriptionService(dataFolder, new Downloader(http, LOG), Runnable::run, () -> config,
                    new Metrics(), LOG, transcripts::add, ready -> { });
            try {
                long before = rssMb();
                service.start(config.speechToText()).join();
                assertEquals(TranscriptionService.State.READY, service.state(), service.stateDetail());
                System.out.printf("RSS %d MB -> %d MB with both models loaded%n", before, rssMb());

                RuleEngine rules = RuleEngine.compile(List.of(new RuleDefinition("test", "Test", RuleAction.MUTE, 1,
                        true, List.of("brothels"), List.of(), List.of(), List.of())), java.util.Set.of());
                short[] speech = SherpaOnnxEngineIT.read(models.resolve("wavs/0.wav"));
                AudioSegment segment = segment(speech);
                service.submit(segment);
                Transcript first = transcripts.poll(60, TimeUnit.SECONDS);
                assertNotNull(first);
                assertFalse(rules.match(first.text()).isEmpty(), first.text());

                List<String> heard = timedCheck(service, "6.6 s hit", segment);
                assertTrue(heard.stream().anyMatch(text -> !rules.match(text).isEmpty()), "not confirmed: " + heard);

                short[] longer = SherpaOnnxEngineIT.read(models.resolve("wavs/1.wav"));
                short[] joined = Arrays.copyOf(longer, longer.length + speech.length);
                System.arraycopy(speech, 0, joined, longer.length, speech.length);
                heard = timedCheck(service, (joined.length / 16_000) + " s hit, word at the end", segment(joined));
                assertTrue(heard.stream().anyMatch(text -> !rules.match(text).isEmpty()), "not confirmed: " + heard);

                // A clip without the word: nothing to confirm.
                heard = service.doubleCheck(segment(SherpaOnnxEngineIT.read(models.resolve("wavs/1.wav")))).get(60, TimeUnit.SECONDS);
                List<RuleMatch> wrong = heard.stream().flatMap(text -> rules.match(text).stream()).toList();
                assertTrue(wrong.isEmpty(), "confirmed a word that was never said: " + heard);
            } finally {
                service.closeAsync().get(60, TimeUnit.SECONDS);
            }
            var refused = service.doubleCheck(segment(new short[16_000]));
            assertTrue(refused.isCompletedExceptionally(), "a stopped engine accepted a double check");
        }
    }

    private static List<String> timedCheck(TranscriptionService service, String label, AudioSegment segment) throws Exception {
        service.doubleCheck(segment).get(60, TimeUnit.SECONDS); // warm-up
        long[] millis = new long[5];
        List<String> heard = List.of();
        for (int i = 0; i < millis.length; i++) {
            long t0 = System.nanoTime();
            heard = service.doubleCheck(segment).get(60, TimeUnit.SECONDS);
            millis[i] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        }
        Arrays.sort(millis);
        System.out.printf("[double check %s] median %d ms, max %d ms: %s%n", label, millis[2], millis[4], heard);
        return heard;
    }

    private static AudioSegment segment(short[] pcm) {
        return new AudioSegment(UUID.randomUUID(), "test", pcm, 16_000, Instant.now(), System.nanoTime(), null, false);
    }

    private static long rssMb() throws Exception {
        for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
            if (line.startsWith("VmRSS:")) return Long.parseLong(line.replaceAll("\\D", "")) / 1024;
        }
        return -1;
    }
}
