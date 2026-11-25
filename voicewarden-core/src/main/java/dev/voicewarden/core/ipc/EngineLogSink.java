package dev.voicewarden.core.ipc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@FunctionalInterface
public interface EngineLogSink {

    enum Stream {
        STDOUT,
        STDERR
    }

    void engineOutput(Stream stream, long pid, String line);

    static EngineLogSink slf4j() {
        Logger log = LoggerFactory.getLogger("VoiceWardenEngine");
        return (stream, pid, line) -> {
            if (stream == Stream.STDERR) {
                log.warn("[engine pid {}] {}", pid, line);
            } else {
                log.info("[engine pid {}] {}", pid, line);
            }
        };
    }
}
