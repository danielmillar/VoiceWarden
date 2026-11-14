package dev.danielmillar.voicewarden.placeholderapi;

import dev.danielmillar.voicewarden.config.Durations;
import dev.danielmillar.voicewarden.config.PluginConfig;
import dev.danielmillar.voicewarden.moderation.OffenseTracker;
import dev.danielmillar.voicewarden.mute.MuteService;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The values behind the {@code %voicewarden_...%} placeholders. PlaceholderAPI may ask from any thread and very often,
 * so this reads only the in-memory mute and offence maps: no database, no blocking.
 */
public final class VoiceStatus {

    /** Every placeholder after the {@code voicewarden_} prefix. */
    public static final List<String> NAMES = List.of("muted", "mute_remaining", "mute_reason", "offenses", "next_mute");

    private final MuteService mutes;
    private final OffenseTracker offenses;
    private final Supplier<PluginConfig> config;
    private final LongSupplier clock;

    public VoiceStatus(MuteService mutes, OffenseTracker offenses, Supplier<PluginConfig> config, LongSupplier clock) {
        this.mutes = mutes;
        this.offenses = offenses;
        this.config = config;
        this.clock = clock;
    }

    /** The value of {@code %voicewarden_<params>%}, empty without a player, or null for an unknown placeholder. */
    public @Nullable String resolve(@Nullable UUID player, String params) {
        if (player == null) {
            return NAMES.contains(params) ? "" : null;
        }
        return switch (params) {
            case "muted" -> Boolean.toString(mutes.isMuted(player));
            case "mute_remaining" -> mutes.get(player)
                    .map(mute -> Durations.formatShort(mute.remaining(clock.getAsLong())))
                    .orElse("");
            case "mute_reason" -> mutes.get(player).map(MuteService.Mute::reason).orElse("");
            case "offenses" -> Integer.toString(offenses.lookup(player, ladder().resetAfterDays()).join().count());
            case "next_mute" -> Durations.formatShort(config.get().moderation()
                    .muteDurationFor(offenses.peekNext(player, ladder().resetAfterDays())));
            // The consent placeholder belongs here once consent state exists.
            default -> null;
        };
    }

    private PluginConfig.MuteLadder ladder() {
        return config.get().moderation().ladder();
    }
}
