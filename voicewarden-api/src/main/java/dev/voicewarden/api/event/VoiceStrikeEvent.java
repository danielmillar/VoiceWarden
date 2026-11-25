package dev.voicewarden.api.event;

import dev.voicewarden.api.FlagSeverity;
import dev.voicewarden.api.VoiceAction;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class VoiceStrikeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID playerId;
    private final String playerName;
    private final FlagSeverity severity;
    private final int currentStrikes;
    private final int threshold;
    private final VoiceAction action;
    private final String transcript;
    private final List<String> matchedWords;

    public VoiceStrikeEvent(
            UUID playerId,
            String playerName,
            FlagSeverity severity,
            int currentStrikes,
            int threshold,
            VoiceAction action,
            String transcript,
            List<String> matchedWords) {
        super(true);
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.playerName = Objects.requireNonNull(playerName, "playerName");
        this.severity = Objects.requireNonNull(severity, "severity");
        this.currentStrikes = currentStrikes;
        this.threshold = threshold;
        this.action = Objects.requireNonNull(action, "action");
        this.transcript = Objects.requireNonNull(transcript, "transcript");
        this.matchedWords = List.copyOf(Objects.requireNonNull(matchedWords, "matchedWords"));
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public FlagSeverity getSeverity() {
        return severity;
    }

    public int getCurrentStrikes() {
        return currentStrikes;
    }

    public int getThreshold() {
        return threshold;
    }

    public VoiceAction getAction() {
        return action;
    }

    public String getTranscript() {
        return transcript;
    }

    public List<String> getMatchedWords() {
        return matchedWords;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
