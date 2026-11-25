package dev.voicewarden.plugin;

import dev.voicewarden.plugin.scheduler.TaskHandle;
import dev.voicewarden.plugin.scheduler.TaskScheduler;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.entity.Entity;

public class ManualScheduler implements TaskScheduler {

    public static final class Task implements TaskHandle {
        private final Runnable runnable;
        private final long delayTicks;
        private final boolean repeating;
        private boolean cancelled;

        Task(Runnable runnable, long delayTicks, boolean repeating) {
            this.runnable = runnable;
            this.delayTicks = delayTicks;
            this.repeating = repeating;
        }

        public long delayTicks() {
            return delayTicks;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    private final List<Task> pending = new ArrayList<>();
    private final Set<Entity> retired = new HashSet<>();
    private int entityTasksRun;

    public void retire(Entity entity) {
        retired.add(entity);
    }

    public int entityTasksRun() {
        return entityTasksRun;
    }

    public List<Task> pending() {
        return pending.stream().filter(task -> !task.cancelled).toList();
    }

    public void runPending() {
        List<Task> snapshot = List.copyOf(pending);
        pending.removeIf(task -> !task.repeating || task.cancelled);
        for (Task task : snapshot) {
            if (!task.cancelled) {
                task.runnable.run();
            }
        }
    }

    private TaskHandle now(Runnable runnable) {
        runnable.run();
        return TaskHandle.NONE;
    }

    private TaskHandle later(Runnable runnable, long delayTicks, boolean repeating) {
        Task task = new Task(runnable, delayTicks, repeating);
        pending.add(task);
        return task;
    }

    @Override
    public TaskHandle runGlobal(Runnable task) {
        return now(task);
    }

    @Override
    public TaskHandle runGlobalDelayed(Runnable task, long delayTicks) {
        return later(task, delayTicks, false);
    }

    @Override
    public TaskHandle runGlobalFixedRate(Runnable task, long initialDelayTicks, long periodTicks) {
        return later(task, initialDelayTicks, true);
    }

    @Override
    public TaskHandle runAsync(Runnable task) {
        return now(task);
    }

    @Override
    public TaskHandle runAsyncDelayed(Runnable task, Duration delay) {
        return later(task, delay.toMillis() / 50, false);
    }

    @Override
    public TaskHandle runAsyncFixedRate(Runnable task, Duration initialDelay, Duration period) {
        return later(task, initialDelay.toMillis() / 50, true);
    }

    @Override
    public TaskHandle runForEntity(Entity entity, Runnable task) {
        if (retired.contains(entity)) {
            return TaskHandle.NONE;
        }
        entityTasksRun++;
        return now(task);
    }

    @Override
    public TaskHandle runForEntityDelayed(Entity entity, Runnable task, long delayTicks) {
        if (retired.contains(entity)) {
            return TaskHandle.NONE;
        }
        return later(() -> {
            if (!retired.contains(entity)) {
                task.run();
            }
        }, delayTicks, false);
    }

    @Override
    public void cancelAll() {
        pending.forEach(Task::cancel);
        pending.clear();
    }
}
