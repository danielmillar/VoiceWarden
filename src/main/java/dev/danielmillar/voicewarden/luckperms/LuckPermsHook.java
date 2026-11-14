package dev.danielmillar.voicewarden.luckperms;

import dev.danielmillar.voicewarden.mute.VoiceMute;
import dev.danielmillar.voicewarden.mute.VoiceMuteMirror;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.DefaultContextKeys;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.data.TemporaryNodeMergeStrategy;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.PermissionNode;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Optional LuckPerms integration: the voice-mute mirror, offline permission checks and name lookups. Only this package
 * may name LuckPerms types, and only after {@link #connect} found the plugin, so VoiceWarden starts without it. All
 * writes are asynchronous ({@code modifyUser} loads, edits and saves the user off-thread).
 */
public final class LuckPermsHook implements VoiceMuteMirror {

    private final LuckPerms luckPerms;
    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> changes = new ConcurrentHashMap<>();
    private @Nullable EventSubscription<UserDataRecalculateEvent> recalcSubscription;

    public LuckPermsHook(LuckPerms luckPerms) {
        this.luckPerms = luckPerms;
    }

    /** The hook, or null when the LuckPerms API service is not registered. Call only when LuckPerms is installed. */
    public static @Nullable LuckPermsHook connect(org.bukkit.Server server) {
        LuckPerms api = server.getServicesManager().load(LuckPerms.class);
        return api == null ? null : new LuckPermsHook(api);
    }

    /**
     * Denies the voice permission(s) with a temporary node so Simple Voice Chat itself blocks the player, on every
     * server sharing this LuckPerms storage, across restarts. {@code duration == null} means permanent.
     */
    public CompletableFuture<Void> applyVoiceMute(UUID player, @Nullable Duration duration, String speakPermission,
                                                  @Nullable String listenPermission, String serverContext) {
        var mirror = new VoiceMute(speakPermission, listenPermission == null ? "" : listenPermission, serverContext);
        return replaceVoiceMute(player, List.of(mirror), mirror, duration);
    }

    /** Removes denied speak/listen nodes (only {@code value=false} nodes, so admin grants are untouched). */
    public CompletableFuture<Void> clearVoiceMute(UUID player, String speakPermission, String listenPermission,
                                                  String serverContext) {
        return replaceVoiceMute(player, List.of(new VoiceMute(speakPermission, listenPermission, serverContext)), null, null);
    }

    @Override
    public CompletableFuture<Void> replaceVoiceMute(UUID player, List<VoiceMute> previous,
                                                     @Nullable VoiceMute next, @Nullable Duration duration) {
        if (previous.isEmpty() && next == null) {
            return CompletableFuture.completedFuture(null);
        }
        return modify(player, user -> {
            for (VoiceMute mirror : previous) {
                clearDenied(user, mirror.speakPermission(), mirror.serverContext());
                if (!mirror.listenPermission().isBlank()) {
                    clearDenied(user, mirror.listenPermission(), mirror.serverContext());
                }
            }
            if (next != null) {
                user.data().add(denyNode(next.speakPermission(), duration, next.serverContext()),
                        TemporaryNodeMergeStrategy.REPLACE_EXISTING_IF_DURATION_LONGER);
                if (!next.listenPermission().isBlank()) {
                    user.data().add(denyNode(next.listenPermission(), duration, next.serverContext()),
                            TemporaryNodeMergeStrategy.REPLACE_EXISTING_IF_DURATION_LONGER);
                }
            }
        });
    }

    public CompletableFuture<Boolean> hasPermission(UUID player, String permission) {
        return user(player).thenApply(user -> user.getCachedData()
                .getPermissionData(luckPerms.getContextManager().getStaticQueryOptions())
                .checkPermission(permission).asBoolean());
    }

    private CompletableFuture<User> user(UUID player) {
        User loaded = luckPerms.getUserManager().getUser(player);
        return loaded == null ? luckPerms.getUserManager().loadUser(player) : CompletableFuture.completedFuture(loaded);
    }

    private CompletableFuture<Void> modify(UUID player, Consumer<User> action) {
        CompletableFuture<Void> next = changes.compute(player, (id, previous) -> {
            CompletableFuture<Void> ready = previous == null ? CompletableFuture.completedFuture(null) : previous;
            return ready.handle((ignored, error) -> null).thenCompose(ignored ->
                    luckPerms.getUserManager().modifyUser(player, action));
        });
        next.whenComplete((ignored, error) -> changes.remove(player, next));
        return next;
    }

    private static Node denyNode(String permission, @Nullable Duration duration, String serverContext) {
        PermissionNode.Builder builder = PermissionNode.builder(permission).value(false);
        if (duration != null) {
            builder.expiry(duration);
        }
        if (!serverContext.isBlank()) {
            builder.withContext(DefaultContextKeys.SERVER_KEY, serverContext);
        }
        return builder.build();
    }

    private static void clearDenied(User user, String permission, String serverContext) {
        user.data().clear(node -> node instanceof PermissionNode p
                && p.getPermission().equalsIgnoreCase(permission)
                && !p.getValue()
                && (serverContext.isBlank()
                ? p.getContexts().getAnyValue(DefaultContextKeys.SERVER_KEY).isEmpty()
                : p.getContexts().contains(DefaultContextKeys.SERVER_KEY, serverContext)));
    }

    /** Resolves a (possibly offline) player name to a UUID without blocking. */
    public CompletableFuture<@Nullable UUID> lookupUuid(String name) {
        return luckPerms.getUserManager().lookupUniqueId(name);
    }

    /** Notifies when a user's permissions/meta change (fires asynchronously), e.g. to refresh bypass state. */
    public void onUserRecalculate(Object plugin, Consumer<UUID> listener) {
        recalcSubscription = luckPerms.getEventBus().subscribe(plugin, UserDataRecalculateEvent.class,
                event -> listener.accept(event.getUser().getUniqueId()));
    }

    public CompletableFuture<Void> flush() {
        return CompletableFuture.allOf(changes.values().toArray(CompletableFuture[]::new));
    }

    public void close() {
        if (recalcSubscription != null) {
            recalcSubscription.close();
        }
    }
}
