package dev.danielmillar.voicesentinel.voice;

import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SVC 2.6.x rejects voicechat.speak before MicrophonePacketEvent. Its API has no denial event.
 * Decorates its public Compatibility interface, replacing only the no-speak status message.
 * All other calls are forwarded, and shutdown restores the original adapter when still owned.
 */
public final class VoicePermissionMessageHook implements AutoCloseable {
    private static final String NO_SPEAK = "message.voicechat.no_speak_permission";
    private final Field field;
    private final Object original;
    private final Object replacement;
    private final Logger logger;
    private volatile boolean active = true;

    private VoicePermissionMessageHook(Field field, Predicate<Player> notice, Logger logger) throws ReflectiveOperationException {
        this.field = field;
        this.logger = logger;
        Class<?> type = field.getType();
        if (!type.isInterface()) {
            throw new IllegalStateException("Simple Voice Chat Compatibility is no longer an interface");
        }
        type.getMethod("sendStatusMessage", Player.class, String.class, String[].class);
        original = field.get(null);
        if (original == null) {
            throw new IllegalStateException("Simple Voice Chat Compatibility is not initialized");
        }
        replacement = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "NevusVoice permission-message adapter";
                    default -> throw new IllegalStateException(method.getName());
                };
            }
            if (active && method.getName().equals("sendStatusMessage") && args != null && args.length == 3
                    && args[0] instanceof Player player && NO_SPEAK.equals(args[1])) {
                try {
                    if (notice.test(player)) {
                        return null;
                    }
                } catch (RuntimeException e) {
                    logger.log(Level.WARNING, "Custom speech-permission notice failed; using the voice-chat notice", e);
                }
            }
            try {
                return method.invoke(original, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
        field.set(null, replacement);
    }

    public static @Nullable VoicePermissionMessageHook install(ClassLoader voicechatLoader, Predicate<Player> notice,
                                                               Logger logger) {
        try {
            Class<?> voicechat = Class.forName("de.maxhenkel.voicechat.Voicechat", false, voicechatLoader);
            VoicePermissionMessageHook hook = install(voicechat.getField("compatibility"), notice, logger);
            logger.info("Custom voice-chat permission action bar installed");
            return hook;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            logger.log(Level.WARNING, "Could not replace Simple Voice Chat's permission notice on this version; "
                    + "local mute notices and voice enforcement remain active", e);
            return null;
        }
    }

    static VoicePermissionMessageHook install(Field field, Predicate<Player> notice, Logger logger)
            throws ReflectiveOperationException {
        return new VoicePermissionMessageHook(field, notice, logger);
    }

    @Override
    public void close() {
        active = false;
        try {
            if (field.get(null) == replacement) {
                field.set(null, original);
            }
        } catch (IllegalAccessException e) {
            logger.log(Level.WARNING, "Could not restore the voice-chat message adapter", e);
        }
    }
}
