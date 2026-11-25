package dev.voicewarden.api.event;

import dev.voicewarden.api.ConfirmationState;
import dev.voicewarden.api.FlagSeverity;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class VoiceFlagEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String playerName;
    private final String transcript;
    private final List<String> matchedWords;
    private final FlagSeverity severity;
    private final ConfirmationState confirmationState;
    private final boolean liveDetection;
    private boolean cancelled;

    public VoiceFlagEvent(
            boolean isAsync,
            UUID playerId,
            String playerName,
            String transcript,
            List<String> matchedWords,
            FlagSeverity severity,
            ConfirmationState confirmationState,
            boolean liveDetection) {
        super(isAsync);
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.playerName = Objects.requireNonNull(playerName, "playerName");
        this.transcript = Objects.requireNonNull(transcript, "transcript");
        this.matchedWords = List.copyOf(Objects.requireNonNull(matchedWords, "matchedWords"));
        this.severity = Objects.requireNonNull(severity, "severity");
        this.confirmationState = Objects.requireNonNull(confirmationState, "confirmationState");
        this.liveDetection = liveDetection;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getTranscript() {
        return transcript;
    }

    public List<String> getMatchedWords() {
        return matchedWords;
    }

    public FlagSeverity getSeverity() {
        return severity;
    }

    public ConfirmationState getConfirmationState() {
        return confirmationState;
    }

    public boolean isLiveDetection() {
        return liveDetection;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
