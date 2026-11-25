package dev.voicewarden.core.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voicewarden.core.audio.VoiceActivityDetector.Decision;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class VoiceActivityDetectorTest {

    private static final int FRAME = 320;

    @Test
    void silenceAndLowNoiseNeverTrigger() {
        VoiceActivityDetector vad = new VoiceActivityDetector(AudioConfig.defaults());
        Random random = new Random(7);
        short[] frame = new short[FRAME];
        for (int f = 0; f < 500; f++) {
            for (int i = 0; i < FRAME; i++) {
                frame[i] = (short) (random.nextGaussian() * 60);
            }
            assertEquals(Decision.SILENCE, vad.process(frame, FRAME));
        }
        assertFalse(vad.inSpeech());
    }

    @Test
    void dcOffsetAndSingleSamplePopsDoNotTrigger() {
        VoiceActivityDetector vad = new VoiceActivityDetector(AudioConfig.defaults());
        short[] dc = new short[FRAME];
        Arrays.fill(dc, (short) 12_000);
        short[] pop = new short[FRAME];
        pop[100] = Short.MAX_VALUE;
        pop[101] = Short.MIN_VALUE;
        assertFalse(VoiceActivityDetector.isActive(dc, 0, FRAME, 220.0));
        assertFalse(VoiceActivityDetector.isActive(pop, 0, FRAME, 220.0));
        for (int f = 0; f < 50; f++) {
            assertEquals(Decision.SILENCE, vad.process(dc, FRAME));
        }
        for (int f = 0; f < 50; f++) {
            assertEquals(Decision.SILENCE, vad.process(pop, FRAME));
        }
    }

    @Test
    void singleLoudFrameIsNotConfirmedAsOnset() {
        VoiceActivityDetector vad = new VoiceActivityDetector(AudioConfig.defaults());
        short[] loud = TestSignals.sine(300, 16_000, FRAME, 5_000, 0);
        assertEquals(Decision.SILENCE, vad.process(loud, FRAME));
        assertEquals(Decision.SILENCE, vad.process(new short[FRAME], FRAME));
        assertEquals(Decision.SILENCE, vad.process(loud, FRAME));
        assertEquals(Decision.SPEECH_START, vad.process(loud, FRAME));
    }

    @Test
    void preRollHoldsTwoHundredMillisecondsBeforeOnset() {
        VoiceActivityDetector vad = new VoiceActivityDetector(AudioConfig.defaults());
        short[] quiet = new short[16_000];
        for (int i = 0; i < quiet.length; i++) {
            quiet[i] = (short) ((i % 97) - 48);
        }
        for (int pos = 0; pos < quiet.length; pos += FRAME) {
            assertEquals(Decision.SILENCE, vad.process(quiet, pos, FRAME));
        }
        short[] speech = TestSignals.sine(250, 16_000, FRAME * 2, 6_000, 0);
        assertEquals(Decision.SILENCE, vad.process(speech, 0, FRAME));
        assertEquals(Decision.SPEECH_START, vad.process(speech, FRAME, FRAME));

        assertEquals(3200 + 2 * FRAME, vad.preRollLength());
        assertEquals(2 * FRAME, vad.onsetLength());
        short[] copied = new short[vad.preRollLength()];
        assertEquals(copied.length, vad.copyPreRoll(copied, 0));
        assertArrayEquals(Arrays.copyOfRange(quiet, quiet.length - 3200, quiet.length), Arrays.copyOfRange(copied, 0, 3200));
        assertArrayEquals(speech, Arrays.copyOfRange(copied, 3200, copied.length));
    }

    @Test
    void hangoverClosesUtteranceAfterSilenceThreshold() {
        AudioConfig config = AudioConfig.defaults();
        VoiceActivityDetector vad = new VoiceActivityDetector(config);
        short[] speech = TestSignals.sine(250, 16_000, FRAME, 6_000, 0);
        short[] silence = new short[FRAME];
        vad.process(speech, FRAME);
        assertEquals(Decision.SPEECH_START, vad.process(speech, FRAME));
        int silentFramesBeforeEnd = config.silenceThresholdMs() / 20;
        for (int f = 1; f < silentFramesBeforeEnd; f++) {
            assertEquals(Decision.SPEECH, vad.process(silence, FRAME), "frame " + f);
        }
        assertEquals(Decision.SPEECH_END, vad.process(silence, FRAME));
        assertFalse(vad.inSpeech());
        assertEquals(Decision.SILENCE, vad.process(silence, FRAME));
    }

    @Test
    void speechDuringHangoverKeepsUtteranceOpen() {
        VoiceActivityDetector vad = new VoiceActivityDetector(AudioConfig.defaults());
        short[] speech = TestSignals.sine(250, 16_000, FRAME, 6_000, 0);
        short[] silence = new short[FRAME];
        vad.process(speech, FRAME);
        vad.process(speech, FRAME);
        for (int round = 0; round < 10; round++) {
            for (int f = 0; f < 20; f++) {
                assertEquals(Decision.SPEECH, vad.process(silence, FRAME));
            }
            assertEquals(Decision.SPEECH, vad.process(speech, FRAME));
        }
        assertTrue(vad.inSpeech());
    }

    @Test
    void decisionsAreFrameSizeIndependent() {
        for (int frame : new int[] {160, 320, 640, 960}) {
            VoiceActivityDetector vad = new VoiceActivityDetector(AudioConfig.defaults());
            short[] speech = TestSignals.sine(250, 16_000, 16_000, 6_000, 0);
            int startSample = -1;
            for (int pos = 0; pos + frame <= speech.length; pos += frame) {
                if (vad.process(speech, pos, frame) == Decision.SPEECH_START) {
                    startSample = pos + frame;
                    break;
                }
            }
            assertEquals(Math.max(640, frame), startSample, "frame size " + frame);
        }
    }

    @Test
    void silenceDoesNotAllocate() {
        VoiceActivityDetector vad = new VoiceActivityDetector(AudioConfig.defaults());
        short[] silence = new short[FRAME];
        for (int i = 0; i < 50_000; i++) {
            vad.process(silence, FRAME);
        }
        Thread self = Thread.currentThread();
        long before = TestSignals.allocatedBytes(self);
        for (int i = 0; i < 10_000; i++) {
            vad.process(silence, FRAME);
        }
        long allocated = TestSignals.allocatedBytes(self) - before;
        assertTrue(allocated < 1024, "VAD allocated " + allocated + " bytes over 10,000 silent frames");
    }
}
