package dev.danielmillar.voicewarden.mute;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * An optional copy of each voice mute in a permissions plugin, so Simple Voice Chat blocks the player on other servers
 * too. VoiceWarden enforces mutes itself either way; {@link #NONE} is used when no permissions plugin is available.
 */
public interface VoiceMuteMirror {

    VoiceMuteMirror NONE = (player, previous, next, duration) -> CompletableFuture.completedFuture(null);

    /** Removes every {@code previous} denial, then applies {@code next} (if any) for {@code duration} (null = permanent). */
    CompletableFuture<Void> replaceVoiceMute(UUID player, List<VoiceMute> previous, @Nullable VoiceMute next,
                                             @Nullable Duration duration);
}
