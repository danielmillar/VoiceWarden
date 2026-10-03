package dev.danielmillar.voicesentinel.testutil;

import dev.danielmillar.voicesentinel.config.ConfigLoader;
import dev.danielmillar.voicesentinel.config.PluginConfig;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

public final class Fakes {
    private Fakes() { }

    public static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
            if (m.getDeclaringClass() == Object.class) {
                return switch (m.getName()) {
                    case "toString" -> "Fake " + type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    default -> throw new AssertionError(m);
                };
            }
            return handler.invoke(p, m, a);
        }));
    }

    public static void resource(Path folder, String name) throws IOException {
        try (var in = Fakes.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new AssertionError("Missing " + name);
            Files.copy(in, folder.resolve(name));
        }
    }

    public static PluginConfig config(Path folder) throws IOException {
        resource(folder, "config.yml");
        return ConfigLoader.load(folder, folder.resolve("config.yml")).config();
    }

    /** Copies a record with one field replaced, retaining all the bundled defaults. */
    @SuppressWarnings("unchecked")
    public static <T extends Record> T with(T original, String field, Object value) {
        try {
            var components = original.getClass().getRecordComponents();
            Class<?>[] types = new Class<?>[components.length];
            Object[] values = new Object[components.length];
            boolean found = false;
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                boolean replace = components[i].getName().equals(field);
                found |= replace;
                values[i] = replace ? value : components[i].getAccessor().invoke(original);
            }
            if (!found) throw new AssertionError("Unknown record field " + field);
            return (T) original.getClass().getDeclaredConstructor(types).newInstance(values);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** Same-thread or explicitly drained executor: no sleeps and reproducible backlog. */
    public static final class Executor extends AbstractExecutorService {
        private final boolean direct;
        private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        private boolean shutdown;

        public Executor(boolean direct) { this.direct = direct; }
        @Override public void execute(Runnable command) {
            if (shutdown) throw new java.util.concurrent.RejectedExecutionException();
            if (direct) command.run(); else pending.add(command);
        }
        public void drain() {
            while (!pending.isEmpty()) pending.removeFirst().run();
        }
        public int pending() { return pending.size(); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> tasks = List.copyOf(pending);
            pending.clear();
            return tasks;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && pending.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
