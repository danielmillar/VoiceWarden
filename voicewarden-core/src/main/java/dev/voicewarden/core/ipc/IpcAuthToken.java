package dev.voicewarden.core.ipc;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;

public final class IpcAuthToken {

    public static final int LENGTH_BYTES = 32;
    public static final String ENV_VAR = "VOICEWARDEN_IPC_TOKEN";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private final byte[] bytes;

    private IpcAuthToken(byte[] bytes) {
        this.bytes = bytes;
    }

    public static IpcAuthToken generate() {
        byte[] bytes = new byte[LENGTH_BYTES];
        RANDOM.nextBytes(bytes);
        return new IpcAuthToken(bytes);
    }

    public static IpcAuthToken of(byte[] bytes) {
        if (bytes == null || bytes.length != LENGTH_BYTES) {
            throw new IllegalArgumentException("IPC auth token must be exactly " + LENGTH_BYTES + " bytes");
        }
        return new IpcAuthToken(bytes.clone());
    }

    public static IpcAuthToken fromHex(String hex) {
        if (hex == null || hex.length() != LENGTH_BYTES * 2) {
            throw new IllegalArgumentException("IPC auth token must be " + LENGTH_BYTES * 2 + " hex characters");
        }
        try {
            return new IpcAuthToken(HEX.parseHex(hex));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("IPC auth token is not valid hex", e);
        }
    }

    public static IpcAuthToken fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    public static IpcAuthToken fromEnvironment(Map<String, String> environment) {
        String value = environment.get(ENV_VAR);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(ENV_VAR + " is not set; the engine must be launched by the VoiceWarden supervisor");
        }
        return fromHex(value.trim());
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public String toHex() {
        return HEX.formatHex(bytes);
    }

    public boolean matches(byte[] candidate) {
        return candidate != null && MessageDigest.isEqual(bytes, candidate);
    }

    public boolean matches(IpcAuthToken other) {
        return other != null && MessageDigest.isEqual(bytes, other.bytes);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof IpcAuthToken other && matches(other);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "IpcAuthToken[redacted]";
    }
}
