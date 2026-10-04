package dev.danielmillar.nevusvoice.stt;

import dev.danielmillar.nevusvoice.audio.AudioSegment;
import dev.danielmillar.nevusvoice.audio.AudioLevels;
import dev.danielmillar.nevusvoice.audio.Decimator;
import dev.danielmillar.nevusvoice.audio.PcmBuffer;
import dev.danielmillar.nevusvoice.config.PluginConfig;
import dev.danielmillar.nevusvoice.moderation.rules.RuleAction;
import dev.danielmillar.nevusvoice.moderation.rules.RuleConfigLoader;
import dev.danielmillar.nevusvoice.moderation.rules.RuleDefinition;
import dev.danielmillar.nevusvoice.moderation.rules.RuleEngine;
import dev.danielmillar.nevusvoice.moderation.rules.RuleMatch;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end test against the real native engine and model. Opt-in (downloads natives; models must be cached):
 * {@code ./gradlew test -PsherpaIntegration=/path/to/models} where the directory contains
 * {@code parakeet-tdt-0.6b-v2-int8/{encoder.int8.onnx,decoder.int8.onnx,joiner.int8.onnx,tokens.txt,test.wav}},
 * {@code silero_vad.onnx}, and optionally {@code flagged.wav} / {@code clean.wav} (16 kHz mono).
 * Select another cached preset with {@code -PsherpaModel=whisper-small-en}.
 */
class SherpaOnnxEngineIT {

    private static final Logger LOG = Logger.getLogger("SherpaOnnxEngineIT");
    private static Path models;
    private static SherpaOnnxEngine engine;

    @TempDir
    static Path dataFolder;

    @BeforeAll
    static void load() throws Exception {
        String dir = System.getProperty("nevusvoice.it.models");
        assumeTrue(dir != null && !dir.isBlank(), "set -PsherpaIntegration=/path/to/models to run");
        models = Path.of(dir);
        try (HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()) {
            Path natives = NativeLibraries.ensure(dataFolder, System.getProperty("nevusvoice.it.natives", ""),
                    new Downloader(http, LOG), LOG);
            NativeLibraries.use(natives);
        }
        ModelManager.ResolvedModel resolved;
        Path m = models.resolve("parakeet-tdt-0.6b-v2-int8");
        if (settings().model().equals("parakeet-tdt-v2") && Files.isDirectory(m)) {
            resolved = new ModelManager.ResolvedModel("parakeet v2 (test)", "nemo_transducer",
                    m.resolve("encoder.int8.onnx"), m.resolve("decoder.int8.onnx"), m.resolve("joiner.int8.onnx"),
                    m.resolve("tokens.txt"), models.resolve("silero_vad.onnx"));
        } else {
            Files.createSymbolicLink(dataFolder.resolve("models"), models.toAbsolutePath());
            try (HttpClient http = HttpClient.newHttpClient()) {
                resolved = ModelManager.resolve(settings(), dataFolder, new Downloader(http, LOG));
            }
        }
        long t0 = System.nanoTime();
        engine = SherpaOnnxEngine.load(resolved, settings());
        System.out.printf("model loaded in %d ms%n", (System.nanoTime() - t0) / 1_000_000);
    }

    @AfterAll
    static void release() {
        if (engine != null) {
            engine.close();
        }
    }

    static PluginConfig.SpeechToText settings() {
        return new PluginConfig.SpeechToText(System.getProperty("nevusvoice.it.model", "parakeet-tdt-v2"),
                false, "", 2, 2, 4, 100, 6, Duration.ofSeconds(30),
                new PluginConfig.InputGain(true, -20, 40),
                new PluginConfig.Vad(true, 0.5f, Duration.ofMillis(100), Duration.ofMillis(300), Duration.ofMillis(250), false),
                new PluginConfig.CustomModel("nemo_transducer", "", "", "", ""));
    }

    @Test
    void transcribesReferenceSpeech() throws Exception {
        assumeTrue(Files.exists(models.resolve("parakeet-tdt-0.6b-v2-int8/test.wav")));
        String text = transcribeTimed("reference", read(models.resolve("parakeet-tdt-0.6b-v2-int8/test.wav")));
        String lower = text.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("turning away her eyes") && lower.contains("portrait"), text);
    }

    @Test
    void fullPipelineFrom48kOpusRateThroughDecimator() throws Exception {
        assumeTrue(Files.exists(models.resolve("parakeet-tdt-0.6b-v2-int8/test.wav")));
        short[] at16k = read(models.resolve("parakeet-tdt-0.6b-v2-int8/test.wav"));
        short[] at48k = upsample3(at16k);
        Decimator decimator = new Decimator();
        PcmBuffer out = new PcmBuffer(16_000);
        for (int off = 0; off + 960 <= at48k.length; off += 960) { // 20 ms frames, like Simple Voice Chat
            short[] frame = new short[960];
            System.arraycopy(at48k, off, frame, 0, 960);
            decimator.process(frame, 960, out);
        }
        String text = transcribeTimed("48k->16k pipeline", out.copy(0, out.size())).toLowerCase(Locale.ROOT);
        assertTrue(text.contains("turning away her eyes") && text.contains("portrait"), text);
    }

    @Test
    void noiseAndSilenceProduceNoTranscript() {
        Random random = new Random(42);
        short[] noise = new short[16_000 * 3];
        short[] quietNoise = new short[noise.length];
        short[] hum = new short[noise.length];
        short[] dc = new short[noise.length];
        for (int i = 0; i < noise.length; i++) {
            noise[i] = (short) (random.nextGaussian() * 400); // ~ -38 dBFS hiss
            quietNoise[i] = (short) (random.nextGaussian() * 25); // ~ -62 dBFS, boosted before VAD.
            hum[i] = (short) Math.round(50 * Math.sin(2 * Math.PI * 60 * i / 16_000));
            dc[i] = 30;
        }
        try (SherpaOnnxEngine.Worker worker = engine.newWorker()) {
            List<SherpaOnnxEngine.Result> results = worker.transcribe(List.of(segment(noise), segment(quietNoise),
                    segment(hum), segment(dc), segment(new short[16_000 * 2])));
            for (int i = 0; i < results.size(); i++) {
                assertNull(results.get(i).text(), "noise/silence input " + i + " should be rejected by VAD: " + results.get(i));
            }
        }
    }

    @Test
    void batchDecodeMatchesSingleDecode() throws Exception {
        assumeTrue(Files.exists(models.resolve("flagged.wav")) && Files.exists(models.resolve("clean.wav")));
        short[] a = read(models.resolve("parakeet-tdt-0.6b-v2-int8/test.wav"));
        short[] b = read(models.resolve("flagged.wav"));
        short[] c = read(models.resolve("clean.wav"));
        try (SherpaOnnxEngine.Worker worker = engine.newWorker()) {
            long t0 = System.nanoTime();
            List<SherpaOnnxEngine.Result> batch = worker.transcribe(List.of(segment(a), segment(b), segment(c)));
            long batchMs = (System.nanoTime() - t0) / 1_000_000;
            System.out.printf("batch of 3 (%.1fs audio) decoded in %d ms%n", (a.length + b.length + c.length) / 16000.0, batchMs);
            assertEquals(worker.transcribe(List.of(segment(a))).getFirst().text(), batch.get(0).text());
            assertEquals(worker.transcribe(List.of(segment(b))).getFirst().text(), batch.get(1).text());
            assertEquals(worker.transcribe(List.of(segment(c))).getFirst().text(), batch.get(2).text());
        }
    }

    @Test
    void sharedRecognizerIsSafeUnderConcurrency() throws Exception {
        assumeTrue(Files.exists(models.resolve("parakeet-tdt-0.6b-v2-int8/test.wav")));
        short[] pcm = read(models.resolve("parakeet-tdt-0.6b-v2-int8/test.wav"));
        String expected;
        try (SherpaOnnxEngine.Worker worker = engine.newWorker()) {
            expected = worker.transcribe(List.of(segment(pcm))).getFirst().text();
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                futures.add(pool.submit(() -> {
                    List<String> texts = new ArrayList<>();
                    try (SherpaOnnxEngine.Worker worker = engine.newWorker()) {
                        for (int i = 0; i < 3; i++) {
                            texts.add(worker.transcribe(List.of(segment(pcm))).getFirst().text());
                        }
                    }
                    return texts;
                }));
            }
            for (Future<List<String>> f : futures) {
                for (String text : f.get()) {
                    assertEquals(expected, text);
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void flaggedSpeechIsDetectedEndToEnd() throws Exception {
        assumeTrue(Files.exists(models.resolve("flagged.wav")) && Files.exists(models.resolve("clean.wav")));
        RuleEngine rules = defaultRules();
        String flagged = transcribeTimed("flagged", read(models.resolve("flagged.wav")));
        List<RuleMatch> hits = rules.match(flagged);
        System.out.println("  matches: " + hits);
        assertTrue(hits.stream().anyMatch(m -> m.action() == RuleAction.MUTE), "expected a MUTE-rule hit in: " + flagged);
        assertTrue(hits.stream().anyMatch(m -> m.action() == RuleAction.FLAG), "expected a profanity hit in: " + flagged);

        String clean = transcribeTimed("clean", read(models.resolve("clean.wav")));
        assertTrue(rules.match(clean).isEmpty(), "clean speech was flagged: " + clean + " -> " + rules.match(clean));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    @Test
    void recordedSpeechPreservesEveryCapturedSample() throws Exception {
        List<Path> wavs = recordedWavs();
        try (var worker = engine.newWorker()) {
            for (Path wav : wavs) {
                short[] pcm = read(wav);
                var result = worker.transcribe(List.of(segment(pcm))).getFirst();
                assertNotNull(result.text(), "Recorded speech was rejected: " + wav);
                assertTrue(!result.text().isBlank(), "Empty transcript: " + wav);
                assertEquals(pcm.length, result.speechSamples(), "VAD clipped recorded words: " + wav);
                System.out.printf("replay %s (%.2fs): %s%n", wav.getFileName(), pcm.length / 16000.0, result.text());
            }
        }
    }

    @Test
    void quietRecordedSpeechStillTriggersTheSameModerationRules() throws Exception {
        RuleEngine rules = defaultRules();
        try (var worker = engine.newWorker()) {
            for (Path wav : recordedWavs()) {
                short[] pcm = read(wav);
                var baseline = worker.transcribe(List.of(segment(pcm))).getFirst();
                assertNotNull(baseline.text());
                Set<String> expected = rules.match(baseline.text()).stream().map(RuleMatch::ruleId)
                        .collect(java.util.stream.Collectors.toSet());
                double originalDbfs = AudioLevels.toDbfs(AudioLevels.loudestFrameMeanSquare(pcm, 0, pcm.length, 320));
                for (int target : new int[]{-50, -55, -60, -65}) {
                    double scale = Math.pow(10, (target - originalDbfs) / 20);
                    short[] quiet = new short[pcm.length];
                    for (int i = 0; i < pcm.length; i++) quiet[i] = (short) Math.round(pcm[i] * scale);
                    var result = worker.transcribe(List.of(segment(quiet))).getFirst();
                    assertNotNull(result.text(), wav + " rejected at " + target + " dBFS");
                    assertTrue(!result.text().isBlank(), wav + " empty at " + target + " dBFS");
                    assertEquals(pcm.length, result.speechSamples());
                    assertEquals(expected, rules.match(result.text()).stream().map(RuleMatch::ruleId)
                            .collect(java.util.stream.Collectors.toSet()), wav + " at " + target + ": " + result.text());
                    System.out.printf("quiet replay %s (%d dBFS): %s%n", wav.getFileName(), target, result.text());
                }
            }
        }
    }

    @Test
    void userWhisperRecordingMatchesMuteRule() throws Exception {
        Path recording = whisperRecording();
        short[] pcm = read(recording);
        try (var worker = engine.newWorker()) {
            var result = worker.transcribe(List.of(segment(pcm))).getFirst();
            assertNotNull(result.text(), "The reported physical whisper was rejected by VAD");
            assertEquals(pcm.length, result.speechSamples());
            assertTrue(defaultRules().match(result.text()).stream().anyMatch(hit -> hit.action() == RuleAction.MUTE),
                    "Reported whispered slur was not caught: " + result.text());
            System.out.printf("WhisperTest: %s (MUTE rule matched)%n", result.text());
        }
    }

    @Test
    void whisperRecordingSurvives48kCaptureAndConcurrentBatchDecode() throws Exception {
        Path recording = whisperRecording();
        short[] at48k;
        Path capture = recording.resolveSibling("WhisperTest.48k.wav");
        try (var input = AudioSystem.getAudioInputStream((Files.exists(capture) ? capture : recording).toFile());
             var converted = AudioSystem.getAudioInputStream(new AudioFormat(48_000, 16, 1, true, false), input)) {
            byte[] bytes = converted.readAllBytes();
            at48k = new short[bytes.length / 2];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(at48k);
        }
        PcmBuffer downsampled = new PcmBuffer(16_000);
        Decimator decimator = new Decimator();
        for (int off = 0; off < at48k.length; off += 960) {
            short[] frame = java.util.Arrays.copyOfRange(at48k, off, Math.min(off + 960, at48k.length));
            decimator.process(frame, frame.length, downsampled);
        }
        short[] pcm = downsampled.copy(0, downsampled.size());
        RuleEngine rules = defaultRules();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<List<SherpaOnnxEngine.Result>>> pending = new ArrayList<>();
            for (int i = 0; i < 2; i++) pending.add(pool.submit(() -> {
                try (var worker = engine.newWorker()) {
                    return worker.transcribe(List.of(segment(pcm), segment(pcm)));
                }
            }));
            for (var future : pending) for (var result : future.get()) {
                assertNotNull(result.text());
                assertEquals(pcm.length, result.speechSamples());
                assertTrue(rules.match(result.text()).stream().anyMatch(hit -> hit.action() == RuleAction.MUTE),
                        "48 kHz captured whisper bypassed moderation: " + result.text());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Path whisperRecording() throws Exception {
        Path recording = recordedWavs().stream()
                .filter(path -> path.getFileName().toString().equalsIgnoreCase("WhisperTest.wav"))
                .findFirst().orElse(null);
        assumeTrue(recording != null, "Include WhisperTest.wav in -PsherpaRecordings to test the reported bypass");
        return recording;
    }

    @Test
    void harmlessWhisperDoesNotMatchAnyModerationRule() throws Exception {
        Path recording = recordedWavs().stream()
                .filter(path -> path.getFileName().toString().equalsIgnoreCase("clean-whisper.wav"))
                .findFirst().orElse(null);
        assumeTrue(recording != null, "Include clean-whisper.wav to check harmless physical whispering");
        RuleEngine rules = defaultRules();
        short[] pcm = read(recording);
        try (var worker = engine.newWorker()) {
            var result = worker.transcribe(List.of(segment(pcm))).getFirst();
            assertNotNull(result.text());
            assertTrue(result.text().toLowerCase(Locale.ROOT).contains("never give up"), result.text());
            assertTrue(rules.match(result.text()).isEmpty(), "Harmless whisper was flagged: " + result.text());
        }
    }

    private static List<Path> recordedWavs() throws Exception {
        String recordings = System.getProperty("nevusvoice.it.recordings");
        assumeTrue(recordings != null && !recordings.isBlank(), "set -PsherpaRecordings=/path/to/wavs to replay speech");
        List<Path> wavs;
        try (var files = Files.walk(Path.of(recordings))) {
            wavs = files.filter(path -> path.toString().endsWith(".wav") && !path.toString().endsWith(".48k.wav"))
                    .sorted().toList();
        }
        assertTrue(!wavs.isEmpty(), "No WAV files in " + recordings);
        return wavs;
    }

    private static String transcribeTimed(String label, short[] pcm) {
        try (SherpaOnnxEngine.Worker worker = engine.newWorker()) {
            worker.transcribe(List.of(segment(pcm))); // warm-up
            long t0 = System.nanoTime();
            SherpaOnnxEngine.Result result = worker.transcribe(List.of(segment(pcm))).getFirst();
            double ms = (System.nanoTime() - t0) / 1e6;
            double audioMs = pcm.length / 16.0;
            System.out.printf("[%s] %.0f ms for %.1f s audio (%.1fx real time): %s%n", label, ms, audioMs / 1000, audioMs / ms, result.text());
            assertNotNull(result.text(), "VAD rejected speech in " + label);
            return result.text();
        }
    }

    private static RuleEngine defaultRules() throws Exception {
        List<RuleDefinition> defs = new ArrayList<>();
        Set<String> allow = new java.util.HashSet<>();
        try (var in = SherpaOnnxEngineIT.class.getClassLoader().getResourceAsStream("rules.yml")) {
            var r = RuleConfigLoader.loadRulesYaml(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            defs.addAll(r.rules());
            allow.addAll(r.allowlist());
        }
        try (var in = SherpaOnnxEngineIT.class.getClassLoader().getResourceAsStream("wordlist.txt")) {
            var r = RuleConfigLoader.loadWordList(new InputStreamReader(in, StandardCharsets.UTF_8), Set.of("en"));
            defs.addAll(r.rules());
            allow.addAll(r.allowlist());
        }
        return RuleEngine.compile(defs, allow);
    }

    private static AudioSegment segment(short[] pcm) {
        return new AudioSegment(UUID.randomUUID(), "test", pcm, 16_000, Instant.now(), System.nanoTime(), null, false);
    }

    static short[] read(Path wav) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(wav.toFile())) {
            AudioFormat f = in.getFormat();
            assertEquals(16_000, (int) f.getSampleRate());
            assertEquals(1, f.getChannels());
            byte[] bytes = in.readAllBytes();
            short[] out = new short[bytes.length / 2];
            ByteBuffer.wrap(bytes).order(f.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out);
            return out;
        }
    }

    /** Linear-interpolation 16 kHz → 48 kHz, standing in for Simple Voice Chat's decoded Opus. */
    static short[] upsample3(short[] in) {
        short[] out = new short[in.length * 3];
        for (int i = 0; i < in.length; i++) {
            int next = i + 1 < in.length ? in[i + 1] : in[i];
            for (int k = 0; k < 3; k++) {
                out[i * 3 + k] = (short) (in[i] + (next - in[i]) * k / 3);
            }
        }
        return out;
    }
}
