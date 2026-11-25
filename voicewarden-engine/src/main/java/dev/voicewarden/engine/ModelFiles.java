package dev.voicewarden.engine;

import java.nio.file.Path;

public sealed interface ModelFiles {

    record Transducer(Path encoder, Path decoder, Path joiner, Path tokens) implements ModelFiles {
    }

    record MoonshineV1(Path preprocessor, Path encoder, Path uncachedDecoder, Path cachedDecoder, Path tokens)
            implements ModelFiles {
    }

    record MoonshineV2(Path encoder, Path mergedDecoder, Path tokens) implements ModelFiles {
    }

    record Whisper(Path encoder, Path decoder, Path tokens) implements ModelFiles {
    }

    record SileroVad(Path model) implements ModelFiles {
    }

    static boolean isComplete(ModelKind kind, Path dir) {
        return ModelDiscovery.isComplete(kind, dir);
    }

    static ModelFiles discover(ModelKind kind, Path dir) {
        return ModelDiscovery.discover(kind, dir);
    }
}
