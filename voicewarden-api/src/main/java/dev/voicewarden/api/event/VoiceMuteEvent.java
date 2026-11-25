package dev.voicewarden.api.event;

import dev.voicewarden.api.VoiceMuteRecord;
import java.util.Objects;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class VoiceMuteEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final VoiceMuteRecord record;
    private final boolean automatic;

    public VoiceMuteEvent(VoiceMuteRecord record, boolean automatic) {
        super(true);
        this.record = Objects.requireNonNull(record, "record");
        this.automatic = automatic;
    }

    public VoiceMuteRecord getRecord() {
        return record;
    }

    public boolean isAutomatic() {
        return automatic;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
