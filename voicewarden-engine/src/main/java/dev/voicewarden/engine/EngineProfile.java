package dev.voicewarden.engine;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

public enum EngineProfile {
    ENGLISH(
            ModelArtifact.sherpaRelease(
                    "sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06",
                    "c8676e5ff9ac2a85296e53ee0fd4d5fb1db6770e7a7647166eeafe349ade6834",
                    ModelKind.ONLINE_TRANSDUCER,
                    ""),
            Verifiers.WHISPER_DISTIL_SMALL_EN,
            true,
            "en"),
    ENGLISH_ACCURATE(
            ModelArtifact.sherpaRelease(
                    "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
                    "6fc4426b54aa549a0019b8a75b8dea59164e71790a37ac56a045ad57f915f714",
                    ModelKind.OFFLINE_TRANSDUCER,
                    "nemo_transducer"),
            Verifiers.WHISPER_DISTIL_SMALL_EN,
            true,
            "en"),
    MULTILANG(
            ModelArtifact.sherpaRelease(
                    "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
                    "1495e91b59d7fc53ddca91f3431758ec99f8fa253fdb0e1e851be0f72b14b0f9",
                    ModelKind.OFFLINE_TRANSDUCER,
                    "nemo_transducer"),
            null,
            false,
            "auto"),
    MULTILANG_GLOBAL(
            ModelArtifact.sherpaRelease(
                    "sherpa-onnx-whisper-turbo",
                    "b11acbbcd660b44a8e0df33724feb5aaa709cf65668f2823d59f656312544f22",
                    ModelKind.OFFLINE_WHISPER,
                    ""),
            null,
            false,
            "auto");

    public static final EngineProfile DEFAULT = ENGLISH_ACCURATE;

    private final ModelArtifact primary;
    private final ModelArtifact verifier;
    private final boolean defaultDoubleCheck;
    private final String defaultLanguage;

    EngineProfile(ModelArtifact primary, ModelArtifact verifier, boolean defaultDoubleCheck, String defaultLanguage) {
        this.primary = primary;
        this.verifier = verifier;
        this.defaultDoubleCheck = defaultDoubleCheck;
        this.defaultLanguage = defaultLanguage;
    }

    public ModelArtifact primary() {
        return primary;
    }

    public Optional<ModelArtifact> verifier() {
        return Optional.ofNullable(verifier);
    }

    public boolean streaming() {
        return primary.kind().streaming();
    }

    public boolean defaultDoubleCheck() {
        return defaultDoubleCheck;
    }

    public String defaultLanguage() {
        return defaultLanguage;
    }

    public static EngineProfile parse(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        String key = value.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
        for (EngineProfile profile : values()) {
            if (profile.name().equals(key)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unknown engine profile '" + value + "'. Valid profiles: "
                + Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", ")));
    }

    private static final class Verifiers {
        static final ModelArtifact WHISPER_DISTIL_SMALL_EN = ModelArtifact.sherpaRelease(
                "sherpa-onnx-whisper-distil-small.en",
                "1483a4ddd62ea2e892a366262740f4dd5e24fc6effecbdf277c5b2d34600f7f1",
                ModelKind.OFFLINE_WHISPER,
                "");

        private Verifiers() {
        }
    }
}
