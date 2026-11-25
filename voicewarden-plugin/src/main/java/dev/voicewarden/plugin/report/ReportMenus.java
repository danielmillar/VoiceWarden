package dev.voicewarden.plugin.report;

import dev.voicewarden.plugin.mute.KnownPlayer;
import java.util.List;
import org.bukkit.entity.Player;

public interface ReportMenus {

    void openTargets(Player reporter, ReportMenuHolder holder, List<KnownPlayer> targets);

    void openWindows(Player reporter, ReportMenuHolder holder, List<Integer> minutes);
}
