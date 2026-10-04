package dev.danielmillar.nevusvoice.voice;

import dev.danielmillar.nevusvoice.audio.AudioSegment;
import dev.danielmillar.nevusvoice.config.PluginConfig;
import dev.danielmillar.nevusvoice.health.Metrics;
import dev.danielmillar.nevusvoice.moderation.SpeakerLocation;
import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns Simple Voice Chat microphone packets into {@link AudioSegment utterances}.
 *
 * <p>Threading: {@link #onMicrophonePacket} runs on SVC's packet thread and only does a map lookup plus a lock-free
 * enqueue. Decoding, resampling and segmentation run on the decode pool. A 100 ms timer closes utterances after
 * silence, frees idle decoders and refreshes bypass permissions; it never does heavy work itself.
 */
public final class AudioIngestService implements AutoCloseable {

    public static final String BYPASS_PERMISSION = "nevusvoice.bypass";
    private static final long SWEEP_MILLIS = 100;
    private static final int BYPASS_REFRESH_TICKS = 50; // 5 s

    private final ConcurrentHashMap<UUID, SpeakerSession> sessions = new ConcurrentHashMap<>();
    private final Supplier<PluginConfig> config;
    private final Metrics metrics;
    private final ExecutorService decodeExecutor;
    private final Consumer<AudioSegment> sink;
    private final Function<UUID, @Nullable Player> onlinePlayer;
    private final Predicate<Player> bypass;
    private final Logger logger;
    private final AtomicLong lastErrorLog = new AtomicLong();

    private volatile @Nullable VoicechatApi api;
    private volatile boolean accepting;
    private @Nullable ScheduledFuture<?> sweeper;
    private int sweepTick;

    public AudioIngestService(Supplier<PluginConfig> config, Metrics metrics, ExecutorService decodeExecutor,
                              Consumer<AudioSegment> sink, Function<UUID, @Nullable Player> onlinePlayer,
                              Predicate<Player> bypass, Logger logger) {
        this.config = config;
        this.metrics = metrics;
        this.decodeExecutor = decodeExecutor;
        this.sink = sink;
        this.onlinePlayer = onlinePlayer;
        this.bypass = bypass;
        this.logger = logger;
    }

    public void start(ScheduledExecutorService timer) {
        sweeper = timer.scheduleWithFixedDelay(this::sweep, SWEEP_MILLIS, SWEEP_MILLIS, TimeUnit.MILLISECONDS);
    }

    void setApi(VoicechatApi api) {
        this.api = api;
    }

    /** Whether audio is captured at all (engine ready and plugin enabled). Mutes are enforced regardless. */
    public void setAccepting(boolean accepting) {
        this.accepting = accepting;
        if (!accepting) {
            sessions.values().forEach(SpeakerSession::requestFlush);
        }
    }

    public boolean isAccepting() {
        return accepting;
    }

    /** SVC packet thread. O(1), never blocks, never throws. */
    void onMicrophonePacket(ServerPlayer speaker, byte[] opus, boolean whispering) {
        if (!accepting) {
            return;
        }
        PluginConfig.Audio settings = config.get().audio();
        if (whispering && settings.ignoreWhispers()) {
            return;
        }
        metrics.packets.increment();
        UUID id = speaker.getUuid();
        SpeakerSession session = sessions.get(id);
        if (session == null) {
            session = sessions.computeIfAbsent(id, key -> newSession(key, speaker));
        }
        if (session.bypass) {
            return;
        }
        session.offer(opus, whispering, () -> locationOf(speaker), System.nanoTime(), settings);
    }

    private SpeakerSession newSession(UUID id, ServerPlayer speaker) {
        String name = id.toString().substring(0, 8);
        boolean bypassed = false;
        if (speaker.getPlayer() instanceof Player player) {
            name = player.getName();
            // SVC itself checks voicechat.speak on this thread for every packet; one check per session is fine.
            bypassed = bypass.test(player);
        }
        return new SpeakerSession(id, name, bypassed, this);
    }

    private static @Nullable SpeakerLocation locationOf(ServerPlayer speaker) {
        try {
            Position pos = speaker.getPosition();
            String world = speaker.getServerLevel().getServerLevel() instanceof World w ? w.getName() : "unknown";
            return new SpeakerLocation(world, pos.getX(), pos.getY(), pos.getZ());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Player left voice chat or the server. */
    public void onDisconnect(UUID id) {
        SpeakerSession session = sessions.remove(id);
        if (session != null) {
            session.close();
        }
    }

    /** Re-evaluates bypass immediately, e.g. after a LuckPerms permission change. */
    public void refreshBypass(UUID id) {
        SpeakerSession session = sessions.get(id);
        Player player = onlinePlayer.apply(id);
        if (session != null && player != null) {
            session.bypass = bypass.test(player);
        }
    }

    private void sweep() {
        try {
            long now = System.nanoTime();
            PluginConfig.Audio settings = config.get().audio();
            long silence = settings.silenceTimeout().toNanos();
            long idle = settings.idleSessionTimeout().toNanos();
            boolean refreshBypass = ++sweepTick % BYPASS_REFRESH_TICKS == 0;
            for (SpeakerSession session : sessions.values()) {
                long lastPacket = session.lastPacketNanos;
                long quiet = now - lastPacket;
                if (session.segmentOpen && quiet > silence) {
                    session.requestSilenceFlush(lastPacket);
                } else if (!session.segmentOpen && session.decoderAllocated && quiet > idle) {
                    session.requestRelease();
                }
                if (refreshBypass) {
                    Player player = onlinePlayer.apply(session.id);
                    if (player == null) {
                        if (quiet > idle) {
                            onDisconnect(session.id); // missed disconnect event
                        }
                    } else {
                        session.bypass = bypass.test(player);
                    }
                }
            }
        } catch (Throwable t) {
            logger.log(Level.WARNING, "Voice session sweep failed", t);
        }
    }

    /** Players who spoke within the last two seconds. */
    public int activeSpeakers() {
        long threshold = System.nanoTime() - TimeUnit.SECONDS.toNanos(2);
        int count = 0;
        for (SpeakerSession session : sessions.values()) {
            if (session.segmentOpen || session.lastPacketNanos > threshold) {
                count++;
            }
        }
        return count;
    }

    // ── callbacks for sessions ───────────────────────────────────────────────

    PluginConfig.Audio settings() {
        return config.get().audio();
    }

    Metrics metrics() {
        return metrics;
    }

    ExecutorService decodeExecutor() {
        return decodeExecutor;
    }

    @Nullable OpusDecoder createDecoder() {
        VoicechatApi a = api;
        return a == null ? null : a.createDecoder();
    }

    void emit(AudioSegment segment) {
        sink.accept(segment);
    }

    void onSessionError(SpeakerSession session, Throwable error) {
        long now = System.currentTimeMillis();
        long last = lastErrorLog.get();
        if (now - last > 60_000 && lastErrorLog.compareAndSet(last, now)) {
            logger.log(Level.WARNING, "Audio processing failed for " + session.name + " (further errors suppressed for 60s)", error);
        }
    }

    @Override
    public void close() {
        accepting = false;
        ScheduledFuture<?> s = sweeper;
        if (s != null) {
            s.cancel(false);
        }
        for (UUID id : sessions.keySet()) {
            onDisconnect(id);
        }
    }
}
