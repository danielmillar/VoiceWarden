package dev.voicewarden.core.storage;

import java.util.Locale;

public enum StorageType {
    SQLITE,
    MYSQL,
    MARIADB;

    public boolean isRemote() {
        return this != SQLITE;
    }

    public static StorageType parse(String value) {
        if (value == null || value.isBlank()) {
            return SQLITE;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (StorageType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown storage type '" + value + "'. Use SQLITE, MYSQL or MARIADB.");
    }
}
