package dev.danielmillar.nevusvoice.luckperms;

import dev.danielmillar.nevusvoice.testutil.Fakes;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedDataManager;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LuckPermsHookTest {
    @Test void readsHistoryFromLegacyMetadata() {
        var hook = hook(Map.of("voicesentinel-offenses", "3", "voicesentinel-last-offense", "12345"));
        assertEquals(new LuckPermsHook.Offenses(3, 12345), hook.cachedOffenses(UUID.randomUUID()).orElseThrow());
    }

    @Test void prefersRenamedMetadataOverLegacyHistory() {
        var hook = hook(Map.of("voicesentinel-offenses", "3", "voicesentinel-last-offense", "12345",
                "nevusvoice-offenses", "4", "nevusvoice-last-offense", "67890"));
        assertEquals(new LuckPermsHook.Offenses(4, 67890), hook.cachedOffenses(UUID.randomUUID()).orElseThrow());
    }

    @Test void missingHistoryStartsAtZero() {
        assertEquals(LuckPermsHook.Offenses.NONE, hook(Map.of()).cachedOffenses(UUID.randomUUID()).orElseThrow());
    }

    @SuppressWarnings("unchecked")
    private static LuckPermsHook hook(Map<String, String> values) {
        CachedMetaData meta = Fakes.proxy(CachedMetaData.class, (p, m, args) -> {
            if (!m.getName().equals("getMetaValue")) throw new AssertionError(m);
            String value = values.get((String) args[0]);
            return value == null ? Optional.empty() : Optional.of(((Function<String, ?>) args[1]).apply(value));
        });
        CachedDataManager cached = Fakes.proxy(CachedDataManager.class, (p, m, args) -> {
            if (!m.getName().equals("getMetaData")) throw new AssertionError(m);
            return meta;
        });
        User user = Fakes.proxy(User.class, (p, m, args) -> {
            if (!m.getName().equals("getCachedData")) throw new AssertionError(m);
            return cached;
        });
        UserManager users = Fakes.proxy(UserManager.class, (p, m, args) -> {
            if (!m.getName().equals("getUser")) throw new AssertionError(m);
            return user;
        });
        return new LuckPermsHook(Fakes.proxy(LuckPerms.class, (p, m, args) -> {
            if (!m.getName().equals("getUserManager")) throw new AssertionError(m);
            return users;
        }));
    }
}
