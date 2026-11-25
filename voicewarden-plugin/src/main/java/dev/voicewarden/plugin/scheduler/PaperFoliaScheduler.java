package dev.voicewarden.plugin.scheduler;

import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

public final class PaperFoliaScheduler implements TaskScheduler {

    private final Plugin plugin;
    private final GlobalRegionScheduler global;
    private final AsyncScheduler async;

    public PaperFoliaScheduler(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        Server server = plugin.getServer();
        this.global = server.getGlobalRegionScheduler();
        this.async = server.getAsyncScheduler();
    }

    @Override
    public TaskHandle runGlobal(Runnable task) {
        return wrap(global.run(plugin, scheduled -> task.run()));
    }

    @Override
    public TaskHandle runGlobalDelayed(Runnable task, long delayTicks) {
        return wrap(global.runDelayed(plugin, scheduled -> task.run(), atLeastOneTick(delayTicks)));
    }

    @Override
    public TaskHandle runGlobalFixedRate(Runnable task, long initialDelayTicks, long periodTicks) {
        return wrap(global.runAtFixedRate(
                plugin, scheduled -> task.run(), atLeastOneTick(initialDelayTicks), atLeastOneTick(periodTicks)));
    }

    @Override
    public TaskHandle runAsync(Runnable task) {
        return wrap(async.runNow(plugin, scheduled -> task.run()));
    }

    @Override
    public TaskHandle runAsyncDelayed(Runnable task, Duration delay) {
        return wrap(async.runDelayed(plugin, scheduled -> task.run(), nonNegativeMillis(delay), TimeUnit.MILLISECONDS));
    }

    @Override
    public TaskHandle runAsyncFixedRate(Runnable task, Duration initialDelay, Duration period) {
        long periodMillis = Math.max(1, nonNegativeMillis(period));
        return wrap(async.runAtFixedRate(
                plugin, scheduled -> task.run(), nonNegativeMillis(initialDelay), periodMillis, TimeUnit.MILLISECONDS));
    }

    @Override
    public TaskHandle runForEntity(Entity entity, Runnable task) {
        return runForEntity(entity, task, null);
    }

    @Override
    public TaskHandle runForEntity(Entity entity, Runnable task, @Nullable Runnable retired) {
        return wrap(entity.getScheduler().run(plugin, scheduled -> task.run(), retired));
    }

    @Override
    public TaskHandle runForEntityDelayed(Entity entity, Runnable task, long delayTicks) {
        return wrap(entity.getScheduler().runDelayed(plugin, scheduled -> task.run(), null, atLeastOneTick(delayTicks)));
    }

    @Override
    public void cancelAll() {
        global.cancelTasks(plugin);
        async.cancelTasks(plugin);
    }

    static long atLeastOneTick(long ticks) {
        return Math.max(1L, ticks);
    }

    private static long nonNegativeMillis(Duration duration) {
        return Math.max(0L, duration.toMillis());
    }

    private static TaskHandle wrap(ScheduledTask task) {
        if (task == null) {
            return TaskHandle.NONE;
        }
        return new TaskHandle() {
            @Override
            public void cancel() {
                task.cancel();
            }

            @Override
            public boolean isCancelled() {
                return task.isCancelled();
            }
        };
    }
}
