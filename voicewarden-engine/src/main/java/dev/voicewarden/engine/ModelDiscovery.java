package dev.voicewarden.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class ModelDiscovery {

    private ModelDiscovery() {
    }

    static boolean isComplete(ModelKind kind, Path dir) {
        try {
            discover(kind, dir);
            return true;
        } catch (ModelLayoutException e) {
            return false;
        }
    }

    static ModelFiles discover(ModelKind kind, Path dir) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(dir, "dir");
        if (!Files.isDirectory(dir)) {
            throw new ModelLayoutException("Model directory " + dir + " does not exist");
        }
        List<Path> files = listFiles(dir);
        Discovery d = new Discovery(dir, kind, files);
        return switch (kind) {
            case ONLINE_TRANSDUCER, OFFLINE_TRANSDUCER -> new ModelFiles.Transducer(
                    d.model("encoder", name -> name.contains("encoder")),
                    d.model("decoder", name -> name.contains("decoder")),
                    d.model("joiner", name -> name.contains("joiner")),
                    d.tokens());
            case OFFLINE_WHISPER -> new ModelFiles.Whisper(
                    d.model("encoder", name -> name.contains("encoder")),
                    d.model("decoder", name -> name.contains("decoder")),
                    d.tokens());
            case OFFLINE_MOONSHINE -> discoverMoonshine(d);
            case SILERO_VAD -> new ModelFiles.SileroVad(d.model("Silero VAD", name -> name.contains("vad")));
        };
    }

    private static ModelFiles discoverMoonshine(Discovery d) {
        Optional<Path> merged = d.optionalModel(name -> name.contains("decoder") && name.contains("merged"));
        if (merged.isPresent()) {
            return new ModelFiles.MoonshineV2(
                    d.model("encoder", name -> name.contains("encoder")),
                    merged.get(),
                    d.tokens());
        }
        return new ModelFiles.MoonshineV1(
                d.model("preprocessor", name -> name.startsWith("preprocess")),
                d.model("encoder", name -> name.startsWith("encode")),
                d.model("uncached decoder", name -> name.startsWith("uncached_decode")),
                d.model("cached decoder", name -> name.startsWith("cached_decode")),
                d.tokens());
    }

    private static List<Path> listFiles(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list model directory " + dir, e);
        }
    }

    private static final class Discovery {
        private static final Comparator<Path> PREFERENCE = Comparator
                .comparing((Path p) -> !lower(p).contains("int8"))
                .thenComparingInt(p -> lower(p).length())
                .thenComparing(Discovery::lower);

        private final Path dir;
        private final ModelKind kind;
        private final List<Path> files;

        private Discovery(Path dir, ModelKind kind, List<Path> files) {
            this.dir = dir;
            this.kind = kind;
            this.files = files;
        }

        private static String lower(Path path) {
            return path.getFileName().toString().toLowerCase(Locale.ROOT);
        }

        private static boolean isModel(String name) {
            return name.endsWith(".onnx") || name.endsWith(".ort");
        }

        Optional<Path> optionalModel(Predicate<String> matcher) {
            return files.stream()
                    .filter(p -> isModel(lower(p)) && matcher.test(lower(p)))
                    .min(PREFERENCE);
        }

        Path model(String role, Predicate<String> matcher) {
            return optionalModel(matcher).orElseThrow(() -> missing(role + " model (.onnx or .ort)"));
        }

        Path tokens() {
            return files.stream()
                    .filter(p -> lower(p).endsWith("tokens.txt"))
                    .min(PREFERENCE)
                    .orElseThrow(() -> missing("tokens.txt"));
        }

        private ModelLayoutException missing(String what) {
            String present = files.isEmpty()
                    ? "(empty)"
                    : files.stream().map(p -> p.getFileName().toString()).collect(Collectors.joining(", "));
            return new ModelLayoutException("Model directory " + dir + " has no " + what + " for a " + kind
                    + " model. Files present: " + present);
        }
    }
}
