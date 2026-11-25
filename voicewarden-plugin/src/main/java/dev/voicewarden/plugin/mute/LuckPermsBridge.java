package dev.voicewarden.plugin.mute;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.PermissionNode;
import org.jetbrains.annotations.Nullable;

final class LuckPermsBridge implements MuteMirror {

    static final String SPEAK_PERMISSION = "voicechat.speak";
    static final String MUTED_PERMISSION = "voicewarden.muted";
    private static final Set<String> MANAGED = Set.of(SPEAK_PERMISSION, MUTED_PERMISSION);

    private final LuckPerms luckPerms;

    private LuckPermsBridge(LuckPerms luckPerms) {
        this.luckPerms = luckPerms;
    }

    static MuteMirror create() {
        return new LuckPermsBridge(LuckPermsProvider.get());
    }

    @Override
    public void apply(UUID playerId, @Nullable Duration duration) {
        applyAsync(playerId, duration);
    }

    @Override
    public CompletableFuture<Void> applyAsync(UUID playerId, @Nullable Duration duration) {
        List<Node> nodes = List.of(node(SPEAK_PERMISSION, false, duration), node(MUTED_PERMISSION, true, duration));
        return luckPerms.getUserManager().modifyUser(playerId, user -> {
            user.data().clear(LuckPermsBridge::isManaged);
            nodes.forEach(user.data()::add);
        });
    }

    @Override
    public void remove(UUID playerId) {
        removeAsync(playerId);
    }

    @Override
    public CompletableFuture<Void> removeAsync(UUID playerId) {
        return luckPerms.getUserManager().modifyUser(playerId, user -> user.data().clear(LuckPermsBridge::isManaged));
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    private static boolean isManaged(Node node) {
        return node instanceof PermissionNode permission && MANAGED.contains(permission.getPermission());
    }

    private static Node node(String permission, boolean value, @Nullable Duration duration) {
        var builder = PermissionNode.builder(permission).value(value);
        if (duration != null) {
            builder.expiry(duration);
        }
        return builder.build();
    }
}
