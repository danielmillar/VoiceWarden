package dev.voicewarden.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.k2fsa.sherpa.onnx.WaveReader;
import dev.voicewarden.api.ConfirmationState;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = RealModelIntegrationTest.MODELS_ENV, matches = ".+")
class RealModelIntegrationTest {

    static final String MODELS_ENV = "VOICEWARDEN_ENGINE_TEST_MODELS";

    private static final UUID PLAYER = new UUID(1, 1);
    private static final int CHUNK = SpeechRecognizerEngine.SAMPLE_RATE / 5;

    @Test
    void englishProfileTranscribesStreamsAndDoubleChecksARealWav() throws IOException {
        Path modelsDir = Path.of(System.getenv(MODELS_ENV));
        ModelDownloader models = new ModelDownloader(modelsDir, false, VerifiedDownloader.createDefault());
        EngineProfile profile = EngineProfile.ENGLISH;
        Path primary = models.ensure(profile.primary());
        Path verifier = models.ensure(profile.verifier().orElseThrow());
        short[] pcm = readPcm(primary.resolve("test_wavs").resolve("0.wav"));

        try (SherpaOnnxSpeechEngine engine = SherpaOnnxSpeechEngine.create(profile, primary, verifier, 2,
                new DoubleCheckEvaluator())) {
            assertTrue(engine.supportsStreaming());
            assertTrue(engine.hasDoubleCheckVerifier());

            RecognitionOutput fin = engine.transcribeFinal(PLAYER, 1, pcm, true);
            System.out.println("primary:  " + fin.text());
            System.out.println("verifier: " + fin.verifierText() + " -> " + fin.confirmation());
            assertFalse(fin.text().isBlank());
            assertEquals(ConfirmationState.CONFIRMED, fin.confirmation());

            RecognitionOutput live = null;
            for (int start = 0; start < pcm.length; start += CHUNK) {
                boolean last = start + CHUNK >= pcm.length;
                live = engine.feedLiveChunk(PLAYER, 2, Arrays.copyOfRange(pcm, start, Math.min(pcm.length, start + CHUNK)),
                        last);
                if (!last) {
                    assertEquals(1, engine.activeStreams());
                }
            }
            System.out.println("live:     " + live.text());
            assertEquals(0, engine.activeStreams(), "end of utterance must release the native stream");
            assertEquals(DoubleCheckEvaluator.normalize(fin.text()), DoubleCheckEvaluator.normalize(live.text()));

            assertEquals(ConfirmationState.CONFIRMED, engine.verifyUtterance(PLAYER, 2, live.text(), pcm).state());
            assertEquals(ConfirmationState.UNCONFIRMED,
                    engine.verifyUtterance(PLAYER, 2, "kill yourself you idiot", pcm).state());

            assertConcurrentPlayersMatchSingleThreaded(engine, pcm, DoubleCheckEvaluator.normalize(fin.text()));

            engine.feedLiveChunk(PLAYER, 3, Arrays.copyOf(pcm, CHUNK), false);
            engine.dropUtterance(PLAYER, 3);
            assertEquals(0, engine.activeStreams());
            engine.feedLiveChunk(PLAYER, 4, Arrays.copyOf(pcm, CHUNK), false);
            assertEquals(1, engine.evictIdleStreams(Duration.ZERO));
            engine.feedLiveChunk(PLAYER, 5, Arrays.copyOf(pcm, CHUNK), false);
        }
    }

    @Test
    void englishAccurateProfileTranscribesSingleWordWithoutLeadingSilence() throws IOException {
        Path modelsDir = Path.of(System.getenv(MODELS_ENV));
        ModelDownloader models = new ModelDownloader(modelsDir, false, VerifiedDownloader.createDefault());
        EngineProfile profile = EngineProfile.ENGLISH_ACCURATE;
        Path primary = models.ensure(profile.primary());
        Path verifier = models.ensure(profile.verifier().orElseThrow());
        Path jfkWav = models.ensure(EngineProfile.ENGLISH.primary()).resolve("test_wavs").resolve("0.wav");
        short[] full = readPcm(jfkWav);
        short[] singleWordAsk = Arrays.copyOfRange(full, 40 * 16, 420 * 16);
        short[] singleWordAskSecond = Arrays.copyOfRange(full, 2040 * 16, 2380 * 16);
        short[] singleWordCountry = Arrays.copyOfRange(full, 3200 * 16, 3750 * 16);
        short[] loudAsk = scale(singleWordAsk, 2.5f);

        try (SherpaOnnxSpeechEngine engine = SherpaOnnxSpeechEngine.create(profile, primary, verifier, 2,
                new DoubleCheckEvaluator())) {
            assertFalse(engine.supportsStreaming());
            assertTrue(engine.hasDoubleCheckVerifier());

            RecognitionOutput fullResult = engine.transcribeFinal(PLAYER, 9, full, true);
            assertEquals("ask not what your country can do for you ask what you can do for your country",
                    DoubleCheckEvaluator.normalize(fullResult.text()));
            assertEquals(ConfirmationState.CONFIRMED, fullResult.confirmation());

            RecognitionOutput askFinal = engine.transcribeFinal(PLAYER, 10, singleWordAsk, true);
            assertEquals("ask", DoubleCheckEvaluator.normalize(askFinal.text()));
            assertEquals(ConfirmationState.CONFIRMED, askFinal.confirmation());

            RecognitionOutput askSecondFinal = engine.transcribeFinal(PLAYER, 11, singleWordAskSecond, true);
            assertEquals("ask", DoubleCheckEvaluator.normalize(askSecondFinal.text()));
            assertEquals(ConfirmationState.CONFIRMED, askSecondFinal.confirmation());

            RecognitionOutput countryFinal = engine.transcribeFinal(PLAYER, 12, singleWordCountry, true);
            assertEquals("country", DoubleCheckEvaluator.normalize(countryFinal.text()));
            assertEquals(ConfirmationState.CONFIRMED, countryFinal.confirmation());

            RecognitionOutput loudAskFinal = engine.transcribeFinal(PLAYER, 13, loudAsk, true);
            assertEquals("ask", DoubleCheckEvaluator.normalize(loudAskFinal.text()));
            assertEquals(ConfirmationState.CONFIRMED, loudAskFinal.confirmation());
        }
    }

    private static short[] scale(short[] pcm, float factor) {
        short[] out = new short[pcm.length];
        for (int i = 0; i < pcm.length; i++) {
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(pcm[i] * factor)));
        }
        return out;
    }

    private static void assertConcurrentPlayersMatchSingleThreaded(SherpaOnnxSpeechEngine engine, short[] pcm,
            String expected) {
        int players = 6;
        ExecutorService pool = Executors.newFixedThreadPool(players);
        try {
            List<Future<List<String>>> futures = new ArrayList<>();
            for (int p = 0; p < players; p++) {
                UUID player = new UUID(9, p);
                futures.add(pool.submit(() -> {
                    String live = "";
                    for (int start = 0; start < pcm.length; start += CHUNK) {
                        boolean last = start + CHUNK >= pcm.length;
                        live = engine.feedLiveChunk(player, 1,
                                Arrays.copyOfRange(pcm, start, Math.min(pcm.length, start + CHUNK)), last).text();
                    }
                    RecognitionOutput fin = engine.transcribeFinal(player, 2, pcm, true);
                    VerificationOutput verify = engine.verifyUtterance(player, 2, fin.text(), pcm);
                    return List.of(DoubleCheckEvaluator.normalize(live), DoubleCheckEvaluator.normalize(fin.text()),
                            fin.confirmation().name(), verify.state().name());
                }));
            }
            for (Future<List<String>> future : futures) {
                assertEquals(List.of(expected, expected, "CONFIRMED", "CONFIRMED"), future.get(120, TimeUnit.SECONDS));
            }
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new AssertionError(e);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, engine.activeStreams());
    }

    private static short[] readPcm(Path wav) {
        WaveReader reader = new WaveReader(wav.toString());
        float[] samples = resample(reader.getSamples(), reader.getSampleRate(), SpeechRecognizerEngine.SAMPLE_RATE);
        short[] pcm = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            pcm[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(samples[i] * 32768f)));
        }
        return pcm;
    }

    private static float[] resample(float[] in, int fromRate, int toRate) {
        if (fromRate == toRate) {
            return in;
        }
        int outLength = (int) ((long) in.length * toRate / fromRate);
        float[] out = new float[outLength];
        double step = (double) fromRate / toRate;
        for (int i = 0; i < outLength; i++) {
            double pos = i * step;
            int index = (int) pos;
            double frac = pos - index;
            float next = index + 1 < in.length ? in[index + 1] : in[index];
            out[i] = (float) (in[index] * (1 - frac) + next * frac);
        }
        return out;
    }
}
