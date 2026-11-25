package dev.voicewarden.api.event;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class VoiceUnmuteEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String playerName;
    private final String moderator;
    private final boolean expired;

    public VoiceUnmuteEvent(UUID playerId, String playerName, String moderator, boolean expired) {
        super(true);
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.playerName = Objects.requireNonNull(playerName, "playerName");
        this.moderator = Objects.requireNonNull(moderator, "moderator");
        this.expired = expired;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getModerator() {
        return moderator;
    }

    public boolean isExpired() {
        return expired;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
