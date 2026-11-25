package dev.voicewarden.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class EngineProfileTest {

    private static final String BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/";

    @Test
    void englishStreamsWithKrokoAndWhisperDoubleCheck() {
        EngineProfile p = EngineProfile.ENGLISH;
        assertEquals("sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06", p.primary().id());
        assertEquals(BASE + "sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06.tar.bz2",
                p.primary().downloadUrl().toString());
        assertEquals(ModelKind.ONLINE_TRANSDUCER, p.primary().kind());
        assertEquals("sherpa-onnx-whisper-distil-small.en", p.verifier().orElseThrow().id());
        assertEquals(BASE + "sherpa-onnx-whisper-distil-small.en.tar.bz2",
                p.verifier().orElseThrow().downloadUrl().toString());
        assertEquals(ModelKind.OFFLINE_WHISPER, p.verifier().orElseThrow().kind());
        assertTrue(p.streaming());
        assertTrue(p.defaultDoubleCheck());
    }

    @Test
    void englishAccurateUsesParakeetV2AndWhisperDoubleCheck() {
        EngineProfile p = EngineProfile.ENGLISH_ACCURATE;
        assertEquals("sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8", p.primary().id());
        assertEquals(BASE + "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2",
                p.primary().downloadUrl().toString());
        assertEquals(ModelKind.OFFLINE_TRANSDUCER, p.primary().kind());
        assertEquals("nemo_transducer", p.primary().modelType());
        assertEquals("sherpa-onnx-whisper-distil-small.en", p.verifier().orElseThrow().id());
        assertFalse(p.streaming());
        assertTrue(p.defaultDoubleCheck());
        assertEquals(EngineProfile.ENGLISH_ACCURATE, EngineProfile.DEFAULT);
    }

    @Test
    void multilangProfilesAreOfflineWithoutVerifier() {
        EngineProfile parakeet = EngineProfile.MULTILANG;
        assertEquals("sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", parakeet.primary().id());
        assertEquals(BASE + "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2",
                parakeet.primary().downloadUrl().toString());
        assertEquals(ModelKind.OFFLINE_TRANSDUCER, parakeet.primary().kind());
        assertEquals("nemo_transducer", parakeet.primary().modelType());
        assertFalse(parakeet.streaming());
        assertFalse(parakeet.defaultDoubleCheck());
        assertTrue(parakeet.verifier().isEmpty());

        EngineProfile whisper = EngineProfile.MULTILANG_GLOBAL;
        assertEquals("sherpa-onnx-whisper-turbo", whisper.primary().id());
        assertEquals(BASE + "sherpa-onnx-whisper-turbo.tar.bz2", whisper.primary().downloadUrl().toString());
        assertEquals(ModelKind.OFFLINE_WHISPER, whisper.primary().kind());
        assertFalse(whisper.streaming());
        assertFalse(whisper.defaultDoubleCheck());
        assertTrue(whisper.verifier().isEmpty());
    }

    @Test
    void archiveNamesMatchIdsAndHashesAreWellFormed() {
        for (EngineProfile profile : EngineProfile.values()) {
            ModelArtifact primary = profile.primary();
            assertEquals(primary.id() + ".tar.bz2", primary.archiveFileName());
            assertEquals(Path.of("/m", primary.id()), primary.directoryIn(Path.of("/m")));
            primary.pinnedSha256().ifPresent(sha -> assertTrue(Sha256.isValidHex(sha)));
        }
    }

    @Test
    void parsesNamesLeniently() {
        assertEquals(EngineProfile.ENGLISH, EngineProfile.parse("english"));
        assertEquals(EngineProfile.ENGLISH_ACCURATE, EngineProfile.parse("english-accurate"));
        assertEquals(EngineProfile.MULTILANG_GLOBAL, EngineProfile.parse(" Multilang_Global "));
        assertEquals(EngineProfile.DEFAULT, EngineProfile.parse(""));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EngineProfile.parse("klingon"));
        assertTrue(e.getMessage().contains("ENGLISH_ACCURATE"), e.getMessage());
    }

    @Test
    void artifactRejectsPathTraversalAndBadHashes() {
        assertThrows(IllegalArgumentException.class,
                () -> ModelArtifact.sherpaRelease("../evil", null, ModelKind.OFFLINE_WHISPER, ""));
        assertThrows(IllegalArgumentException.class,
                () -> ModelArtifact.sherpaRelease("model", "abc", ModelKind.OFFLINE_WHISPER, ""));
    }
}
