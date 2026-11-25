package dev.voicewarden.plugin.scheduler;

import java.time.Duration;
import org.bukkit.entity.Entity;
import org.jetbrains.annotations.Nullable;

public interface TaskScheduler {

    TaskHandle runGlobal(Runnable task);

    TaskHandle runGlobalDelayed(Runnable task, long delayTicks);

    TaskHandle runGlobalFixedRate(Runnable task, long initialDelayTicks, long periodTicks);

    TaskHandle runAsync(Runnable task);

    TaskHandle runAsyncDelayed(Runnable task, Duration delay);

    TaskHandle runAsyncFixedRate(Runnable task, Duration initialDelay, Duration period);

    TaskHandle runForEntity(Entity entity, Runnable task);

    default TaskHandle runForEntity(Entity entity, Runnable task, @Nullable Runnable retired) {
        return runForEntity(entity, task);
    }

    TaskHandle runForEntityDelayed(Entity entity, Runnable task, long delayTicks);

    void cancelAll();
}
