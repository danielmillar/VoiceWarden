package dev.voicewarden.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfSystemProperty(named = SileroVadGateTest.MODEL_PROPERTY, matches = ".+")
class SileroVadGateTest {

    static final String MODEL_PROPERTY = "voicewarden.test.sileroVad";
    static final String SPEECH_PROPERTY = "voicewarden.test.speechWav";

    private static final String SPEECH_WAV_SHA256 = "77b34b85923c7cb3e82670b8afc70b5d2dfce0477769c6bfa85d2722701c6d57";
    private static final int RATE = SpeechRecognizerEngine.SAMPLE_RATE;
    private static final int PRE_ROLL = RATE / 5;
    private static final int TRAILING_SILENCE = RATE / 2;
    private static final double[] WORD_ONSETS = {13.25, 32.85, 54.55};
    private static final double[] HARD_ONSETS = {13.25, 18.85, 26.05, 32.85, 54.55};

    @TempDir
    static Path tmp;

    private static float[] speech;
    private static SileroVadGate gate;

    @BeforeAll
    static void loadModel() throws IOException {
        new NativeLibraryLoader(tmp.resolve("native"), false, VerifiedDownloader.createDefault(),
                NativePlatform.current()).ensureLoaded();
        Path model = Path.of(System.getProperty(MODEL_PROPERTY));
        assertEquals(SileroVadGate.ARTIFACT.expectedSha256(), Sha256.of(model),
                "the pinned silero_vad.onnx hash must match the release file");
        Path dir = Files.createDirectories(SileroVadGate.ARTIFACT.directoryIn(tmp.resolve("models")));
        Files.copy(model, dir.resolve(SileroVadGate.ARTIFACT.archiveFileName()));
        Path wav = Path.of(System.getProperty(SPEECH_PROPERTY));
        assertEquals(SPEECH_WAV_SHA256, Sha256.of(wav), "the speech fixture offsets assume this exact recording");
        speech = readMono16k(wav);
        gate = SileroVadGate.open(dir, SileroVadSettings.DEFAULTS);
    }

    @AfterAll
    static void closeGate() {
        if (gate != null) {
            gate.close();
        }
    }

    @Test
    void aSpokenPhraseIsSpeech() {
        assertTrue(gate.hasSpeech(clip(12.6, 15.4)));
    }

    @Test
    void shortSingleWordsAreNeverSwallowed() {
        List<String> missed = new ArrayList<>();
        for (int ms : new int[] {250, 300, 350}) {
            for (double onset : WORD_ONSETS) {
                for (int shift = 0; shift < SpeechSegmenter.WINDOW_SAMPLES; shift += 64) {
                    float[] word = word(onset, ms);
                    expectSpeech(missed, place(word, PRE_ROLL + shift, TRAILING_SILENCE), ms, onset, shift, "pre-roll");
                    expectSpeech(missed, place(word, PRE_ROLL + shift, 0), ms, onset, shift, "ending the buffer");
                    expectSpeech(missed, place(word, shift, TRAILING_SILENCE), ms, onset, shift, "no pre-roll");
                }
            }
        }
        assertEquals(List.of(), missed);
    }

    @Test
    void mostTwoHundredMillisecondFragmentsStillCount() {
        int detected = 0;
        int total = 0;
        for (double onset : HARD_ONSETS) {
            for (int shift = 0; shift < SpeechSegmenter.WINDOW_SAMPLES; shift += 64) {
                for (int lead : new int[] {shift, PRE_ROLL + shift}) {
                    total++;
                    if (gate.hasSpeech(place(word(onset, 200), lead, TRAILING_SILENCE))) {
                        detected++;
                    }
                }
            }
        }
        assertTrue(detected * 10 >= total * 9, "only " + detected + " of " + total + " 200 ms fragments were detected");
    }

    @Test
    void aMissedQuietWordStillReachesRecognitionWhenLaterSpeechOpensTheGate() {
        float[] quietWord = word(18.85, 200);
        float[] utterance = new float[PRE_ROLL + quietWord.length + RATE * 6 / 10 + RATE * 3 / 2];
        System.arraycopy(quietWord, 0, utterance, PRE_ROLL, quietWord.length);
        float[] phrase = clip(13.1, 14.6);
        System.arraycopy(phrase, 0, utterance, utterance.length - phrase.length, phrase.length);
        float[] decoded = SherpaOnnxSpeechEngine.gated(gate, utterance, samples -> samples, () -> null);
        assertTrue(decoded == utterance, "recognition must see the whole utterance, including the first word");
    }

    @Test
    void aDamagedModelFileIsRefusedBeforeNativeCodeSeesIt() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("damaged").resolve(SileroVadGate.ARTIFACT.id()));
        Files.write(dir.resolve(SileroVadGate.ARTIFACT.archiveFileName()), new byte[] {1, 2, 3});
        ModelLayoutException e = assertThrows(ModelLayoutException.class,
                () -> SileroVadGate.open(dir, SileroVadSettings.DEFAULTS));
        assertTrue(e.getMessage().contains(SileroVadGate.ARTIFACT.expectedSha256()), e.getMessage());
    }

    @Test
    void nonSpeechNoiseIsRejected() {
        Random random = new Random(42);
        int n = 3 * RATE;
        List<String> flagged = new ArrayList<>();
        check(flagged, "silence", new float[n]);
        check(flagged, "faint hiss", noise(random, n, 0.005));
        check(flagged, "loud static", noise(random, n, 0.3));
        for (double hz : new double[] {60, 120, 440}) {
            float[] hum = new float[n];
            for (int i = 0; i < n; i++) {
                hum[i] = (float) (0.2 * Math.sin(2 * Math.PI * hz * i / RATE)
                        + 0.1 * Math.sin(4 * Math.PI * hz * i / RATE) + 0.01 * random.nextGaussian());
            }
            check(flagged, hz + " Hz hum", hum);
        }
        float[] bumps = new float[n];
        for (int k = 0; k < 3; k++) {
            int at = random.nextInt(n - 3000);
            for (int j = 0; j < 3000; j++) {
                bumps[at + j] += (float) (Math.exp(-j / 300.0)
                        * (0.9 * Math.sin(2 * Math.PI * 40 * j / RATE) + 0.3 * random.nextGaussian()));
            }
        }
        check(flagged, "mic bumps", bumps);
        float[] sweep = new float[n];
        double phase = 0;
        for (int i = 0; i < n; i++) {
            phase += 2 * Math.PI * (800 + 600 * Math.sin(Math.PI * i / RATE)) / RATE;
            sweep[i] = (float) (0.3 * Math.sin(phase));
        }
        check(flagged, "whistle", sweep);
        check(flagged, "crowd noise", clip(1.0, 4.0));
        assertEquals(List.of(), flagged);
    }

    @Test
    void reusedInstancesAreResetBetweenUtterances() {
        float[] phrase = clip(12.6, 15.4);
        float[] quiet = new float[2 * RATE];
        for (int round = 0; round < 3; round++) {
            assertTrue(gate.hasSpeech(phrase));
            assertTrue(!gate.hasSpeech(quiet), "speech state leaked into the next utterance");
        }
        assertArrayEquals(gate.probabilities(phrase), gate.probabilities(phrase));
    }

    @Test
    void concurrentCallersGetCorrectAnswersAndBoundedInstances() throws Exception {
        int threads = 8;
        float[] phrase = place(word(32.85, 300), PRE_ROLL, TRAILING_SILENCE);
        float[] hum = new float[RATE];
        for (int i = 0; i < hum.length; i++) {
            hum[i] = (float) (0.2 * Math.sin(2 * Math.PI * 120 * i / RATE));
        }
        try (SileroVadGate shared = SileroVadGate.open(SileroVadGate.ARTIFACT.directoryIn(tmp.resolve("models")),
                SileroVadSettings.DEFAULTS)) {
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<Integer>> results = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    int offset = t;
                    Callable<Integer> call = () -> {
                        start.await();
                        int wrong = 0;
                        for (int i = 0; i < 20; i++) {
                            boolean speak = (i + offset) % 2 == 0;
                            boolean heard = shared.hasSpeech(speak ? phrase : hum);
                            if (heard != speak) {
                                wrong++;
                            }
                        }
                        return wrong;
                    };
                    results.add(pool.submit(call));
                }
                start.countDown();
                for (Future<Integer> result : results) {
                    assertEquals(0, result.get());
                }
            } finally {
                pool.shutdownNow();
            }
            assertTrue(shared.createdInstances() <= threads, "pool grew past the number of callers: "
                    + shared.createdInstances());
        }
    }

    @Test
    void closedGateRefusesWorkAndClosesTwiceSafely() {
        SileroVadGate temporary = SileroVadGate.open(SileroVadGate.ARTIFACT.directoryIn(tmp.resolve("models")),
                SileroVadSettings.DEFAULTS);
        assertFalse(temporary.describe().isBlank());
        temporary.close();
        temporary.close();
        assertThrows(IllegalStateException.class, () -> temporary.hasSpeech(new float[RATE]));
    }

    @Test
    void emptyAudioIsNotSpeech() {
        assertFalse(gate.hasSpeech(new float[0]));
    }

    private static void check(List<String> flagged, String name, float[] samples) {
        if (gate.hasSpeech(samples)) {
            flagged.add(name);
        }
    }

    private static void expectSpeech(List<String> missed, float[] buffer, int ms, double onset, int shift, String where) {
        if (!gate.hasSpeech(buffer)) {
            missed.add(ms + " ms word at " + onset + " s, shift " + shift + ", " + where);
        }
    }

    private static float[] noise(Random random, int n, double amplitude) {
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = (float) (random.nextGaussian() * amplitude);
        }
        return out;
    }

    private static float[] clip(double fromSeconds, double toSeconds) {
        return Arrays.copyOfRange(speech, (int) (fromSeconds * RATE), (int) (toSeconds * RATE));
    }

    private static float[] word(double onsetSeconds, int ms) {
        int from = (int) (onsetSeconds * RATE);
        return Arrays.copyOfRange(speech, from, from + ms * RATE / 1000);
    }

    private static float[] place(float[] word, int lead, int trail) {
        float[] out = new float[lead + word.length + trail];
        System.arraycopy(word, 0, out, lead, word.length);
        return out;
    }

    private static float[] readMono16k(Path wav) throws IOException {
        ByteBuffer bytes = ByteBuffer.wrap(Files.readAllBytes(wav)).order(ByteOrder.LITTLE_ENDIAN);
        bytes.position(12);
        while (bytes.remaining() >= 8) {
            int id = bytes.getInt();
            int size = bytes.getInt();
            if (id == 0x20746d66) {
                int start = bytes.position();
                assertEquals(1, bytes.getShort(start + 2), "fixture must be mono");
                assertEquals(RATE, bytes.getInt(start + 4), "fixture must be 16 kHz");
                assertEquals(16, bytes.getShort(start + 14), "fixture must be 16-bit");
            }
            if (id == 0x61746164) {
                float[] out = new float[size / 2];
                for (int i = 0; i < out.length; i++) {
                    out[i] = bytes.getShort() / 32768f;
                }
                return out;
            }
            bytes.position(bytes.position() + size + (size & 1));
        }
        throw new IOException("No data chunk in " + wav);
    }
}
