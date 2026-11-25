package dev.voicewarden.core.config;

import dev.voicewarden.core.audio.RollingOpusBuffer;
import java.util.List;
import java.util.Objects;

public record VoiceReportConfig(
        boolean enabled,
        Mode mode,
        int defaultMinutes,
        int minMinutes,
        int maxMinutes,
        List<Integer> presetMinutes,
        int cooldownSeconds,
        int maxConcurrentReports,
        boolean staffChatFullTranscript,
        String webhookUrl,
        boolean includeAudio,
        int audioBufferSeconds) {

    public static final int MAX_AUDIO_BUFFER_SECONDS = RollingOpusBuffer.MAX_RETENTION_SECONDS;

    public enum Mode {
        COMMAND,
        GUI
    }

    public VoiceReportConfig {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(webhookUrl, "webhookUrl");
        if (maxMinutes < minMinutes) {
            maxMinutes = minMinutes;
        }
        defaultMinutes = Math.max(minMinutes, Math.min(maxMinutes, defaultMinutes));
        int low = minMinutes;
        int high = maxMinutes;
        audioBufferSeconds = Math.max(0, Math.min(MAX_AUDIO_BUFFER_SECONDS, audioBufferSeconds));
        presetMinutes = presetMinutes.stream().filter(minutes -> minutes >= low && minutes <= high).distinct().toList();
    }

    public boolean audioCaptureEnabled() {
        return enabled && includeAudio && audioBufferSeconds > 0 && !webhookUrl.isBlank();
    }

    public int clampMinutes(int requested) {
        return Math.max(minMinutes, Math.min(maxMinutes, requested));
    }

    @Override
    public String toString() {
        return "VoiceReportConfig[enabled=" + enabled + ", mode=" + mode + ", defaultMinutes=" + defaultMinutes
                + ", minMinutes=" + minMinutes + ", maxMinutes=" + maxMinutes + ", presetMinutes=" + presetMinutes
                + ", cooldownSeconds=" + cooldownSeconds + ", maxConcurrentReports=" + maxConcurrentReports
                + ", staffChatFullTranscript=" + staffChatFullTranscript + ", webhookUrl=" + Redaction.secret(webhookUrl)
                + ", includeAudio=" + includeAudio + ", audioBufferSeconds=" + audioBufferSeconds + "]";
    }
}
