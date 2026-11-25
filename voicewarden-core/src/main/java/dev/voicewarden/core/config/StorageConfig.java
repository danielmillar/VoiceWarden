package dev.voicewarden.core.config;

import java.util.Objects;

public record StorageConfig(
        Type type,
        String host,
        int port,
        String database,
        String username,
        String password,
        String jdbcParams,
        int maxPoolSize,
        int queryTimeoutMs) {

    public enum Type {
        SQLITE,
        MYSQL,
        MARIADB
    }

    public StorageConfig {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(jdbcParams, "jdbcParams");
    }

    @Override
    public String toString() {
        return "StorageConfig[type=" + type + ", host=" + host + ", port=" + port + ", database=" + database
                + ", username=" + username + ", password=" + Redaction.secret(password) + ", jdbcParams=" + jdbcParams
                + ", maxPoolSize=" + maxPoolSize + ", queryTimeoutMs=" + queryTimeoutMs + "]";
    }
}
