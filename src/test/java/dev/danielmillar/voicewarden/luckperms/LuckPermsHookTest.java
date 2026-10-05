package dev.danielmillar.voicewarden.luckperms;

import dev.danielmillar.voicewarden.testutil.Fakes;
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
    @Test void readsVoiceWardenHistory() {
        var hook = hook(Map.of("voicewarden-offenses", "4", "voicewarden-last-offense", "67890"));
        assertEquals(new LuckPermsHook.Offenses(4, 67890), hook.cachedOffenses(UUID.randomUUID()).orElseThrow());
    }

    @Test void missingHistoryStartsAtZero() {
        assertEquals(LuckPermsHook.Offenses.NONE, hook(Map.of()).cachedOffenses(UUID.randomUUID()).orElseThrow());
    }

    @Test void ignoresOtherPluginHistory() {
        var hook = hook(Map.of("otherplugin-offenses", "3", "otherplugin-last-offense", "12345"));
        assertEquals(LuckPermsHook.Offenses.NONE, hook.cachedOffenses(UUID.randomUUID()).orElseThrow());
    }

    @Test void offlinePermissionUsesEffectiveCachedPermissionsWithStaticContext() {
        var loaded = new java.util.concurrent.CompletableFuture<User>();
        var options = Fakes.proxy(net.luckperms.api.query.QueryOptions.class, (p, m, a) -> { throw new AssertionError(m); });
        var contexts = Fakes.proxy(net.luckperms.api.context.ContextManager.class, (p, m, a) -> {
            assertEquals("getStaticQueryOptions", m.getName());
            return options;
        });
        var permissions = Fakes.proxy(net.luckperms.api.cacheddata.CachedPermissionData.class, (p, m, a) -> {
            assertEquals("checkPermission", m.getName());
            assertEquals("voicewarden.bypass", a[0]);
            return net.luckperms.api.util.Tristate.TRUE;
        });
        var cached = Fakes.proxy(CachedDataManager.class, (p, m, a) -> {
            assertEquals("getPermissionData", m.getName());
            org.junit.jupiter.api.Assertions.assertSame(options, a[0]);
            return permissions;
        });
        var user = Fakes.proxy(User.class, (p, m, a) -> cached);
        var users = Fakes.proxy(UserManager.class, (p, m, a) -> switch (m.getName()) {
            case "getUser" -> null;
            case "loadUser" -> loaded;
            default -> throw new AssertionError(m);
        });
        var hook = new LuckPermsHook(Fakes.proxy(LuckPerms.class, (p, m, a) -> switch (m.getName()) {
            case "getUserManager" -> users;
            case "getContextManager" -> contexts;
            default -> throw new AssertionError(m);
        }));
        var bypass = hook.hasPermission(UUID.randomUUID(), "voicewarden.bypass");
        org.junit.jupiter.api.Assertions.assertFalse(bypass.isDone());
        loaded.complete(user);
        org.junit.jupiter.api.Assertions.assertTrue(bypass.join());
    }

    @Test void cleanupRemovesOnlyDeniedNodesForStoredPermissionsAndContext() throws Exception {
        var nodes = new java.util.ArrayList<net.luckperms.api.node.types.PermissionNode>();
        nodes.add(permission("old.speak", "old-server", false));
        nodes.add(permission("old.listen", "old-server", false));
        var grant = permission("old.speak", "old-server", true);
        var otherServer = permission("old.speak", "other-server", false);
        var otherPermission = permission("other.permission", "old-server", false);
        nodes.addAll(java.util.List.of(grant, otherServer, otherPermission));
        var data = Fakes.proxy(net.luckperms.api.model.data.NodeMap.class, (p, m, a) -> {
            assertEquals("clear", m.getName());
            var predicate = java.util.function.Predicate.class.getMethod("test", Object.class);
            var remove = new java.util.ArrayList<net.luckperms.api.node.types.PermissionNode>();
            for (var node : nodes) {
                if (Boolean.TRUE.equals(predicate.invoke(a[0], node))) remove.add(node);
            }
            nodes.removeAll(remove);
            return null;
        });
        var user = Fakes.proxy(User.class, (p, m, a) -> {
            assertEquals("data", m.getName());
            return data;
        });
        var users = Fakes.proxy(UserManager.class, (p, m, a) -> {
            assertEquals("modifyUser", m.getName());
            java.util.function.Consumer.class.getMethod("accept", Object.class).invoke(a[1], user);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        var hook = new LuckPermsHook(Fakes.proxy(LuckPerms.class, (p, m, a) -> users));
        hook.replaceVoiceMute(UUID.randomUUID(), java.util.List.of(
                new LuckPermsHook.VoiceMute("old.speak", "old.listen", "old-server")), null, null).join();
        assertEquals(java.util.List.of(grant, otherServer, otherPermission), nodes);
    }

    private static net.luckperms.api.node.types.PermissionNode permission(String permission, String server, boolean value) {
        var contexts = Fakes.proxy(net.luckperms.api.context.ImmutableContextSet.class, (p, m, a) -> switch (m.getName()) {
            case "contains" -> a[0].equals("server") && a[1].equals(server);
            case "getAnyValue" -> Optional.of(server);
            default -> throw new AssertionError(m);
        });
        return Fakes.proxy(net.luckperms.api.node.types.PermissionNode.class, (p, m, a) -> switch (m.getName()) {
            case "getPermission" -> permission;
            case "getValue" -> value;
            case "getContexts" -> contexts;
            default -> throw new AssertionError(m);
        });
    }

    @SuppressWarnings("unchecked")
    private static LuckPermsHook hook(Map<String, String> values) {
        CachedMetaData meta = Fakes.proxy(CachedMetaData.class, (p, m, args) -> {
            if (!m.getName().equals("getMetaValue")) throw new AssertionError(m);
            if (!args[0].equals("voicewarden-offenses") && !args[0].equals("voicewarden-last-offense")) {
                throw new AssertionError("Must only read VoiceWarden metadata: " + args[0]);
            }
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
