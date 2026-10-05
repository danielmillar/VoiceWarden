package dev.danielmillar.voicewarden.moderation;

import dev.danielmillar.voicewarden.luckperms.LuckPermsHook;
import dev.danielmillar.voicewarden.testutil.Fakes;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedDataManager;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class OffenseTrackerTest {
    private final UUID player = UUID.randomUUID();
    private final AtomicReference<LuckPermsHook.Offenses> history = new AtomicReference<>(new LuckPermsHook.Offenses(4, System.currentTimeMillis()));
    private final AtomicInteger loads = new AtomicInteger();
    private final AtomicInteger writes = new AtomicInteger();
    private CompletableFuture<Void> writeResult = CompletableFuture.completedFuture(null);

    @Test void unloadedPlayerRetainsDurableLadderHistory() {
        var tracker = tracker();
        assertEquals(5, tracker.recordNext(player, 0));
        assertEquals(6, tracker.recordNext(player, 0));
        assertEquals(6, tracker.lookup(player, 0).join().count());
        assertEquals(1, loads.get());
        assertEquals(2, writes.get());
    }

    @Test void quittingWaitsForPendingOwnWriteBeforeReadingStorage() {
        var tracker = tracker();
        writeResult = new CompletableFuture<>();
        assertEquals(5, tracker.recordNext(player, 0));
        tracker.forget(player);
        var lookup = tracker.lookup(player, 0);
        assertFalse(lookup.isDone());
        assertEquals(1, loads.get());
        history.set(new LuckPermsHook.Offenses(5, System.currentTimeMillis()));
        writeResult.complete(null);
        assertEquals(5, lookup.join().count());
        assertEquals(6, tracker.recordNext(player, 0));
    }

    @Test void resetIsOrderedAfterPendingIncrementAndResetsNextOffense() {
        var tracker = tracker();
        writeResult = new CompletableFuture<>();
        assertEquals(5, tracker.recordNext(player, 0));
        var reset = tracker.reset(player);
        assertFalse(reset.isDone());
        assertEquals(1, writes.get());
        writeResult.complete(null);
        reset.join();
        assertEquals(2, writes.get());
        assertEquals(1, tracker.recordNext(player, 0));
    }

    @Test void oldOffensesDecayAndDryRunDoesNotWrite() {
        history.set(new LuckPermsHook.Offenses(4, 1));
        var tracker = tracker();
        assertEquals(1, tracker.peekNext(player, 1));
        assertEquals(0, writes.get());
        assertEquals(1, tracker.recordNext(player, 1));
    }

    private OffenseTracker tracker() {
        CachedMetaData meta = Fakes.proxy(CachedMetaData.class, (p, m, a) -> {
            if (!m.getName().equals("getMetaValue")) throw new AssertionError(m);
            return a[0].equals("voicewarden-offenses") ? Optional.of(history.get().count())
                    : Optional.of(history.get().lastEpochMillis());
        });
        CachedDataManager data = Fakes.proxy(CachedDataManager.class, (p, m, a) -> meta);
        User user = Fakes.proxy(User.class, (p, m, a) -> data);
        UserManager users = Fakes.proxy(UserManager.class, (p, m, a) -> switch (m.getName()) {
            case "getUser" -> null;
            case "loadUser" -> { loads.incrementAndGet(); yield CompletableFuture.completedFuture(user); }
            case "modifyUser" -> { writes.incrementAndGet(); yield writeResult; }
            default -> throw new AssertionError(m);
        });
        var hook = new LuckPermsHook(Fakes.proxy(LuckPerms.class, (p, m, a) -> users));
        return new OffenseTracker(hook, Logger.getAnonymousLogger());
    }
}
