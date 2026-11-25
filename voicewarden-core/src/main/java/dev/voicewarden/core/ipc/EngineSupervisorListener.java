package dev.voicewarden.core.ipc;

import java.time.Duration;

public interface EngineSupervisorListener {

    EngineSupervisorListener NONE = new EngineSupervisorListener() { };

    default void onEngineReady(EngineClient client, long pid) {
    }

    default void onEngineLost(long pid, String reason) {
    }

    default void onRestartScheduled(int restartsInWindow, Duration delay) {
    }

    default void onGaveUp(String reason) {
    }
}
