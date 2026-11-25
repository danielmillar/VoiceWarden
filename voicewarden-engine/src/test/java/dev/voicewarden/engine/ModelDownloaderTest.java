package dev.voicewarden.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelDownloaderTest {

    private static final String ID = "test-transducer";
    private static final String PATH = "/" + ID + ".tar.bz2";

    @TempDir
    Path tmp;

    private Path archive;
    private byte[] archiveBytes;
    private String archiveSha;

    @BeforeEach
    void buildArchive() throws IOException {
        archive = tmp.resolve("fixture.tar.bz2");
        TestArchives.writeTarBz2(archive, TestArchives.transducerLayout(ID));
        archiveBytes = Files.readAllBytes(archive);
        archiveSha = Sha256.of(archive);
    }

    private static ModelArtifact artifact(URI url, String sha) {
        return new ModelArtifact(ID, ID + ".tar.bz2", url, sha, ModelKind.ONLINE_TRANSDUCER, "",
                ModelArtifact.Packaging.TAR_BZ2);
    }

    private static ModelDownloader downloader(Path modelsDir, boolean auto) {
        return new ModelDownloader(modelsDir, auto, VerifiedDownloader.createDefault());
    }

    @Test
    void downloadsVerifiesExtractsAndMarksReady() throws IOException {
        Path models = tmp.resolve("models");
        try (TestArchives.FileServer server = new TestArchives.FileServer(PATH, archiveBytes)) {
            Path dir = downloader(models, true).ensure(artifact(server.uri("/redirect" + PATH), archiveSha));
            assertEquals(models.resolve(ID), dir);
            assertTrue(Files.isRegularFile(dir.resolve("encoder.onnx")));
            assertTrue(Files.isRegularFile(dir.resolve(ModelDownloader.READY_MARKER)));
            assertEquals(1, server.requests.get());
            assertTrue(ModelFiles.discover(ModelKind.ONLINE_TRANSDUCER, dir) instanceof ModelFiles.Transducer);

            downloader(models, true).ensure(artifact(server.uri(PATH), archiveSha));
            assertEquals(1, server.requests.get(), "an installed model must not be downloaded again");
        }
        assertNoLeftovers(models);
    }

    @Test
    void checksumMismatchDeletesDownloadAndFails() throws IOException {
        Path models = tmp.resolve("models");
        String wrong = "0".repeat(64);
        try (TestArchives.FileServer server = new TestArchives.FileServer(PATH, archiveBytes)) {
            IOException e = assertThrows(IOException.class,
                    () -> downloader(models, true).ensure(artifact(server.uri(PATH), wrong)));
            assertTrue(e.getMessage().contains("SHA-256 mismatch"), e.getMessage());
        }
        assertFalse(Files.exists(models.resolve(ID)));
        assertNoLeftovers(models);
    }

    @Test
    void sidecarChecksumIsHonouredWhenNothingIsPinned() throws IOException {
        Path models = Files.createDirectories(tmp.resolve("models"));
        Files.writeString(models.resolve(ID + ".tar.bz2.sha256"), "f".repeat(64) + "  " + ID + ".tar.bz2\n");
        try (TestArchives.FileServer server = new TestArchives.FileServer(PATH, archiveBytes)) {
            assertThrows(Sha256.ChecksumMismatchException.class,
                    () -> downloader(models, true).ensure(artifact(server.uri(PATH), null)));
            Files.writeString(models.resolve(ID + ".tar.bz2.sha256"), archiveSha + "\n");
            assertTrue(Files.isDirectory(downloader(models, true).ensure(artifact(server.uri(PATH), null))));
        }
    }

    @Test
    void httpErrorsFailClearly() {
        Path models = tmp.resolve("models");
        try (TestArchives.FileServer server = new TestArchives.FileServer(PATH, archiveBytes)) {
            IOException e = assertThrows(IOException.class,
                    () -> downloader(models, true).ensure(artifact(server.uri("/missing"), archiveSha)));
            assertTrue(e.getMessage().contains("HTTP 404"), e.getMessage());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void manuallyPlacedArchiveIsExtractedWithoutNetwork() throws IOException {
        Path models = Files.createDirectories(tmp.resolve("models"));
        Files.copy(archive, models.resolve(ID + ".tar.bz2"));
        URI unreachable = URI.create("http://127.0.0.1:9/never");
        Path dir = downloader(models, false).ensure(artifact(unreachable, archiveSha));
        assertTrue(Files.isRegularFile(dir.resolve("tokens.txt")));
        assertTrue(Files.readString(dir.resolve(ModelDownloader.READY_MARKER)).contains("source=archive"));
    }

    @Test
    void manuallyPlacedArchiveWithWrongChecksumIsRejectedAndKept() throws IOException {
        Path models = Files.createDirectories(tmp.resolve("models"));
        Path placed = Files.copy(archive, models.resolve(ID + ".tar.bz2"));
        assertThrows(Sha256.ChecksumMismatchException.class,
                () -> downloader(models, false).ensure(artifact(URI.create("http://127.0.0.1:9/"), "1".repeat(64))));
        assertTrue(Files.exists(placed), "an operator's archive must never be deleted");
    }

    @Test
    void manuallyExtractedDirectoryIsUsedAsIs() throws IOException {
        Path models = tmp.resolve("models");
        TestArchives.touch(models.resolve(ID), "encoder-epoch-99.int8.onnx", "decoder-epoch-99.onnx",
                "joiner-epoch-99.onnx", "tokens.txt");
        Path dir = downloader(models, false).ensure(artifact(URI.create("http://127.0.0.1:9/"), archiveSha));
        assertTrue(Files.readString(dir.resolve(ModelDownloader.READY_MARKER)).contains("source=manual"));
    }

    @Test
    void missingModelWithoutAutoDownloadNamesArchiveAndDirectory() {
        Path models = tmp.resolve("models");
        ModelUnavailableException e = assertThrows(ModelUnavailableException.class,
                () -> downloader(models, false).ensure(artifact(URI.create("https://example.invalid" + PATH), null)));
        assertTrue(e.getMessage().contains(ID + ".tar.bz2"), e.getMessage());
        assertTrue(e.getMessage().contains(models.toAbsolutePath().toString()), e.getMessage());
        assertTrue(e.getMessage().contains("https://example.invalid" + PATH), e.getMessage());
    }

    @Test
    void zipSlipEntriesAreRejected() throws IOException {
        Path models = Files.createDirectories(tmp.resolve("models"));
        TestArchives.writeTarBz2(models.resolve(ID + ".tar.bz2"),
                Map.of("../../escaped.txt", "pwned"));
        assertThrows(ArchiveExtractor.UnsafeArchiveException.class,
                () -> downloader(models, false).ensure(artifact(URI.create("http://127.0.0.1:9/"), null)));
        assertFalse(Files.exists(tmp.resolve("escaped.txt")));
        assertFalse(Files.exists(tmp.getParent().resolve("escaped.txt")));
    }

    @Test
    void escapingSymlinksAreRejected() throws IOException {
        Path out = tmp.resolve("out");
        Path absolute = tmp.resolve("abs.tar.bz2");
        TestArchives.writeTarBz2WithSymlink(absolute, "model/link", "/etc/passwd");
        assertThrows(ArchiveExtractor.UnsafeArchiveException.class, () -> ArchiveExtractor.extractTarBz2(absolute, out));
        Path climbing = tmp.resolve("climb.tar.bz2");
        TestArchives.writeTarBz2WithSymlink(climbing, "model/link", "../../outside");
        assertThrows(ArchiveExtractor.UnsafeArchiveException.class, () -> ArchiveExtractor.extractTarBz2(climbing, out));
    }

    @Test
    void incompleteArchiveIsRejectedWithFileListing() throws IOException {
        Path models = Files.createDirectories(tmp.resolve("models"));
        TestArchives.writeTarBz2(models.resolve(ID + ".tar.bz2"), Map.of(ID + "/tokens.txt", "a 0"));
        ModelLayoutException e = assertThrows(ModelLayoutException.class,
                () -> downloader(models, false).ensure(artifact(URI.create("http://127.0.0.1:9/"), null)));
        assertTrue(e.getMessage().contains("tokens.txt"), e.getMessage());
        assertFalse(Files.exists(models.resolve(ID)));
    }

    @Test
    void concurrentEnsureDownloadsOnceUnderTheLock() throws Exception {
        Path models = tmp.resolve("models");
        int callers = 6;
        try (TestArchives.FileServer server = new TestArchives.FileServer(PATH, archiveBytes)) {
            ModelArtifact target = artifact(server.uri(PATH), archiveSha);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(callers);
            try {
                List<Future<Path>> results = new ArrayList<>();
                for (int i = 0; i < callers; i++) {
                    Callable<Path> call = () -> {
                        start.await();
                        return downloader(models, true).ensure(target);
                    };
                    results.add(pool.submit(call));
                }
                start.countDown();
                for (Future<Path> result : results) {
                    assertEquals(models.resolve(ID), result.get());
                }
            } finally {
                pool.shutdownNow();
            }
            assertEquals(1, server.requests.get(), "only one caller may download while holding the lock");
        }
        assertTrue(ModelFiles.isComplete(ModelKind.ONLINE_TRANSDUCER, models.resolve(ID)));
        assertNoLeftovers(models);
    }

    @Test
    void anotherProcessHoldingTheLockBlocksInstallUntilReleased() throws Exception {
        Path models = Files.createDirectories(tmp.resolve("models"));
        Files.copy(archive, models.resolve(ID + ".tar.bz2"));
        Path lockFile = models.resolve(ModelDownloader.LOCK_DIR).resolve(ID + ".lock");
        Path ready = tmp.resolve("holder.ready");
        Path release = tmp.resolve("holder.release");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process holder = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                LockHolderMain.class.getName(), lockFile.toString(), ready.toString(), release.toString())
                .inheritIO()
                .start();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (!Files.exists(ready)) {
                assertTrue(holder.isAlive() && System.nanoTime() < deadline, "lock holder process never locked");
                Thread.sleep(20);
            }
            Future<Path> install = pool.submit(
                    () -> downloader(models, false).ensure(artifact(URI.create("http://127.0.0.1:9/"), archiveSha)));
            assertThrows(TimeoutException.class,
                    () -> install.get(500, TimeUnit.MILLISECONDS));
            assertFalse(Files.exists(models.resolve(ID)), "install ran while another process held the lock");
            Files.writeString(release, "go");
            assertEquals(models.resolve(ID), install.get(20, TimeUnit.SECONDS));
            assertEquals(0, holder.waitFor());
        } finally {
            pool.shutdownNow();
            holder.destroyForcibly();
        }
    }

    private static final String VAD_FILE = "silero_vad.onnx";
    private static final byte[] VAD_BYTES = "not really onnx".getBytes(StandardCharsets.UTF_8);

    private static ModelArtifact vadArtifact(URI url, String sha) {
        return new ModelArtifact("silero-vad", VAD_FILE, url, sha, ModelKind.SILERO_VAD, "",
                ModelArtifact.Packaging.SINGLE_FILE);
    }

    private static String vadSha() throws IOException {
        Path file = Files.createTempFile("vad", ".onnx");
        try {
            Files.write(file, VAD_BYTES);
            return Sha256.of(file);
        } finally {
            Files.delete(file);
        }
    }

    @Test
    void singleFileModelIsDownloadedVerifiedAndPublishedIntoItsDirectory() throws IOException {
        Path models = tmp.resolve("models");
        try (TestArchives.FileServer server = new TestArchives.FileServer("/" + VAD_FILE, VAD_BYTES)) {
            Path dir = downloader(models, true).ensure(vadArtifact(server.uri("/redirect/" + VAD_FILE), vadSha()));
            assertEquals(models.resolve("silero-vad"), dir);
            assertTrue(Arrays.equals(VAD_BYTES, Files.readAllBytes(dir.resolve(VAD_FILE))));
            assertTrue(Files.readString(dir.resolve(ModelDownloader.READY_MARKER)).contains("source=download"));
            ModelFiles.SileroVad files = (ModelFiles.SileroVad) ModelFiles.discover(ModelKind.SILERO_VAD, dir);
            assertEquals(dir.resolve(VAD_FILE), files.model());

            downloader(models, true).ensure(vadArtifact(server.uri("/" + VAD_FILE), vadSha()));
            assertEquals(1, server.requests.get(), "an installed model must not be downloaded again");
        }
        assertNoLeftovers(models);
        assertFalse(Files.exists(models.resolve(VAD_FILE)), "the download must not be left next to the model folder");
    }

    @Test
    void singleFileChecksumMismatchFailsAndLeavesNothing() throws IOException {
        Path models = tmp.resolve("models");
        try (TestArchives.FileServer server = new TestArchives.FileServer("/" + VAD_FILE, VAD_BYTES)) {
            assertThrows(Sha256.ChecksumMismatchException.class,
                    () -> downloader(models, true).ensure(vadArtifact(server.uri("/" + VAD_FILE), "0".repeat(64))));
        }
        assertFalse(Files.exists(models.resolve("silero-vad")));
        assertNoLeftovers(models);
    }

    @Test
    void manuallyPlacedSingleFileIsInstalledWithoutNetworkAndKept() throws IOException {
        Path models = Files.createDirectories(tmp.resolve("models"));
        Path placed = Files.write(models.resolve(VAD_FILE), VAD_BYTES);
        Path dir = downloader(models, false).ensure(vadArtifact(URI.create("http://127.0.0.1:9/"), vadSha()));
        assertTrue(Files.isRegularFile(dir.resolve(VAD_FILE)));
        assertTrue(Files.readString(dir.resolve(ModelDownloader.READY_MARKER)).contains("source=archive"));
        assertTrue(Files.exists(placed), "an operator's file must never be deleted");
    }

    @Test
    void manuallyPlacedSingleFileWithWrongChecksumIsRejected() throws IOException {
        Path models = Files.createDirectories(tmp.resolve("models"));
        Files.write(models.resolve(VAD_FILE), VAD_BYTES);
        assertThrows(Sha256.ChecksumMismatchException.class,
                () -> downloader(models, false).ensure(vadArtifact(URI.create("http://127.0.0.1:9/"), "1".repeat(64))));
        assertFalse(Files.exists(models.resolve("silero-vad")));
    }

    @Test
    void missingSingleFileWithoutAutoDownloadSaysWhereToPutIt() {
        Path models = tmp.resolve("models");
        ModelUnavailableException e = assertThrows(ModelUnavailableException.class,
                () -> downloader(models, false).ensure(vadArtifact(URI.create("https://example.invalid/" + VAD_FILE), null)));
        assertTrue(e.getMessage().contains("place '" + VAD_FILE + "'"), e.getMessage());
        assertTrue(e.getMessage().contains("copy it into " + models.toAbsolutePath().resolve("silero-vad")),
                e.getMessage());
        assertFalse(e.getMessage().contains("extract"), e.getMessage());
    }

    @Test
    void damagedSingleFileIsReplacedEvenWithAReadyMarker() throws IOException {
        Path models = tmp.resolve("models");
        try (TestArchives.FileServer server = new TestArchives.FileServer("/" + VAD_FILE, VAD_BYTES)) {
            ModelArtifact vad = vadArtifact(server.uri("/" + VAD_FILE), vadSha());
            Path dir = downloader(models, true).ensure(vad);
            Files.write(dir.resolve(VAD_FILE), new byte[] {1, 2, 3});
            assertTrue(Files.exists(dir.resolve(ModelDownloader.READY_MARKER)));

            assertThrows(ModelUnavailableException.class, () -> downloader(models, false).ensure(vad),
                    "a damaged pinned file must not be trusted just because .ready exists");

            downloader(models, true).ensure(vad);
            assertEquals(2, server.requests.get());
            assertTrue(Arrays.equals(VAD_BYTES, Files.readAllBytes(dir.resolve(VAD_FILE))));
        }
    }

    @Test
    void pinnedSileroArtifactIsASingleFileFromTheSherpaRelease() {
        ModelArtifact vad = SileroVadGate.ARTIFACT;
        assertEquals(ModelArtifact.Packaging.SINGLE_FILE, vad.packaging());
        assertEquals(ModelKind.SILERO_VAD, vad.kind());
        assertEquals(URI.create(ModelArtifact.RELEASE_BASE + VAD_FILE), vad.downloadUrl());
        assertTrue(vad.pinnedSha256().isPresent());
        assertThrows(IllegalArgumentException.class, () -> ModelArtifact.sherpaReleaseFile("vad", "../x.onnx", null,
                ModelKind.SILERO_VAD));
    }

    private static void assertNoLeftovers(Path models) throws IOException {
        if (!Files.exists(models)) {
            return;
        }
        try (Stream<Path> list = Files.list(models)) {
            List<String> stray = list.map(p -> p.getFileName().toString())
                    .filter(n -> n.contains(".extract-") || n.endsWith(VerifiedDownloader.PART_SUFFIX)
                            || n.endsWith(".download"))
                    .toList();
            assertTrue(stray.isEmpty(), "temporary files left behind: " + stray);
        }
    }
}
