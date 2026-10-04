package dev.danielmillar.nevusvoice.luckperms;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.DefaultContextKeys;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.data.TemporaryNodeMergeStrategy;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.MetaNode;
import net.luckperms.api.node.types.PermissionNode;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Everything NevusVoice stores in LuckPerms. All writes are asynchronous ({@code modifyUser} loads, edits and saves
 * the user off-thread). Reads of online players use LuckPerms' thread-safe cached data and never hit storage.
 */
public final class LuckPermsHook {

    private static final String OFFENSES_KEY = "nevusvoice-offenses";
    private static final String LAST_OFFENSE_KEY = "nevusvoice-last-offense";

    private final LuckPerms luckPerms;
    private @Nullable EventSubscription<UserDataRecalculateEvent> recalcSubscription;

    public LuckPermsHook(LuckPerms luckPerms) {
        this.luckPerms = luckPerms;
    }

    /**
     * Denies the voice permission(s) with a temporary node so Simple Voice Chat itself blocks the player, on every
     * server sharing this LuckPerms storage, across restarts. {@code duration == null} means permanent.
     */
    public CompletableFuture<Void> applyVoiceMute(UUID player, @Nullable Duration duration, String speakPermission,
                                                  @Nullable String listenPermission, String serverContext) {
        return luckPerms.getUserManager().modifyUser(player, user -> {
            clearDenied(user, speakPermission, serverContext);
            user.data().add(denyNode(speakPermission, duration, serverContext),
                    TemporaryNodeMergeStrategy.REPLACE_EXISTING_IF_DURATION_LONGER);
            if (listenPermission != null && !listenPermission.isBlank()) {
                clearDenied(user, listenPermission, serverContext);
                user.data().add(denyNode(listenPermission, duration, serverContext),
                        TemporaryNodeMergeStrategy.REPLACE_EXISTING_IF_DURATION_LONGER);
            }
        });
    }

    /** Removes denied speak/listen nodes (only {@code value=false} nodes, so admin grants are untouched). */
    public CompletableFuture<Void> clearVoiceMute(UUID player, String speakPermission, String listenPermission,
                                                  String serverContext) {
        return luckPerms.getUserManager().modifyUser(player, user -> {
            clearDenied(user, speakPermission, serverContext);
            if (!listenPermission.isBlank()) {
                clearDenied(user, listenPermission, serverContext);
            }
        });
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

    /** Auto-mute offense history, stored as meta so it follows the player across servers. */
    public record Offenses(int count, long lastEpochMillis) {
        public static final Offenses NONE = new Offenses(0, 0);
    }

    /** Reads offenses from cached data when the user is loaded (online), else loads them from storage. */
    public CompletableFuture<Offenses> offenses(UUID player) {
        User loaded = luckPerms.getUserManager().getUser(player);
        if (loaded != null) {
            return CompletableFuture.completedFuture(readOffenses(loaded));
        }
        return luckPerms.getUserManager().loadUser(player).thenApply(LuckPermsHook::readOffenses);
    }

    /** Synchronous read for an online player; empty if LuckPerms has not loaded the user. Thread-safe and fast. */
    public Optional<Offenses> cachedOffenses(UUID player) {
        User loaded = luckPerms.getUserManager().getUser(player);
        return loaded == null ? Optional.empty() : Optional.of(readOffenses(loaded));
    }

    private static Offenses readOffenses(User user) {
        var meta = user.getCachedData().getMetaData();
        int count = meta.getMetaValue(OFFENSES_KEY, Integer::parseInt).orElse(0);
        long last = meta.getMetaValue(LAST_OFFENSE_KEY, Long::parseLong).orElse(0L);
        return new Offenses(count, last);
    }

    public CompletableFuture<Void> storeOffenses(UUID player, Offenses offenses) {
        return luckPerms.getUserManager().modifyUser(player, user -> {
            user.data().clear(NodeType.META.predicate(m -> m.getMetaKey().equals(OFFENSES_KEY)
                    || m.getMetaKey().equals(LAST_OFFENSE_KEY)));
            if (offenses.count() > 0) {
                user.data().add(MetaNode.builder(OFFENSES_KEY, Integer.toString(offenses.count())).build());
                user.data().add(MetaNode.builder(LAST_OFFENSE_KEY, Long.toString(offenses.lastEpochMillis())).build());
            }
        });
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

    public void close() {
        if (recalcSubscription != null) {
            recalcSubscription.close();
        }
    }
}
