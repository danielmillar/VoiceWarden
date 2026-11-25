package dev.voicewarden.core.storage;

import java.nio.file.Path;
import java.util.Objects;

public record StorageConfig(
        StorageType type,
        Path dataDirectory,
        String host,
        int port,
        String database,
        String username,
        String password,
        String jdbcParams,
        int maxPoolSize,
        long queryTimeoutMs,
        long reconnectIntervalMs,
        String serverId,
        boolean networkStrikes) {

    public static final String MUTE_DATABASE_FILE = "mute_history.db";
    public static final String REPORT_DATABASE_FILE = "voice_reports.db";
    public static final int DEFAULT_PORT = 3306;
    public static final int DEFAULT_MAX_POOL_SIZE = 10;
    public static final long DEFAULT_QUERY_TIMEOUT_MS = 2000L;
    public static final long DEFAULT_RECONNECT_INTERVAL_MS = 15000L;
    public static final String DEFAULT_SERVER_ID = "default";

    public StorageConfig {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        host = host == null || host.isBlank() ? "localhost" : host.trim();
        port = port <= 0 ? DEFAULT_PORT : port;
        database = database == null ? "" : database.trim();
        username = username == null ? "" : username;
        password = password == null ? "" : password;
        jdbcParams = jdbcParams == null ? "" : jdbcParams.trim();
        maxPoolSize = maxPoolSize <= 0 ? DEFAULT_MAX_POOL_SIZE : maxPoolSize;
        queryTimeoutMs = queryTimeoutMs <= 0 ? DEFAULT_QUERY_TIMEOUT_MS : queryTimeoutMs;
        reconnectIntervalMs = reconnectIntervalMs <= 0 ? DEFAULT_RECONNECT_INTERVAL_MS : reconnectIntervalMs;
        serverId = serverId == null || serverId.isBlank() ? DEFAULT_SERVER_ID : serverId.trim();
        if (type.isRemote() && database.isEmpty()) {
            throw new IllegalArgumentException("storage.database must be set when storage.type is " + type);
        }
    }

    public static Builder builder(StorageType type, Path dataDirectory) {
        return new Builder(type, dataDirectory);
    }

    public static StorageConfig sqlite(Path dataDirectory, String serverId) {
        return builder(StorageType.SQLITE, dataDirectory).serverId(serverId).build();
    }

    public Path muteDatabasePath() {
        return dataDirectory.resolve(MUTE_DATABASE_FILE);
    }

    public Path reportDatabasePath() {
        return dataDirectory.resolve(REPORT_DATABASE_FILE);
    }

    public int queryTimeoutSeconds() {
        return (int) Math.max(1L, (queryTimeoutMs + 999L) / 1000L);
    }

    @Override
    public String toString() {
        return "StorageConfig[type=" + type
                + ", dataDirectory=" + dataDirectory
                + ", host=" + host
                + ", port=" + port
                + ", database=" + database
                + ", username=" + username
                + ", password=" + (password.isEmpty() ? "" : "<redacted>")
                + ", jdbcParams=" + jdbcParams
                + ", maxPoolSize=" + maxPoolSize
                + ", queryTimeoutMs=" + queryTimeoutMs
                + ", reconnectIntervalMs=" + reconnectIntervalMs
                + ", serverId=" + serverId
                + ", networkStrikes=" + networkStrikes + "]";
    }

    public static final class Builder {
        private final StorageType type;
        private final Path dataDirectory;
        private String host = "localhost";
        private int port = DEFAULT_PORT;
        private String database = "";
        private String username = "";
        private String password = "";
        private String jdbcParams = "";
        private int maxPoolSize = DEFAULT_MAX_POOL_SIZE;
        private long queryTimeoutMs = DEFAULT_QUERY_TIMEOUT_MS;
        private long reconnectIntervalMs = DEFAULT_RECONNECT_INTERVAL_MS;
        private String serverId = DEFAULT_SERVER_ID;
        private boolean networkStrikes = true;

        private Builder(StorageType type, Path dataDirectory) {
            this.type = Objects.requireNonNull(type, "type");
            this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        }

        public Builder host(String value) {
            host = value;
            return this;
        }

        public Builder port(int value) {
            port = value;
            return this;
        }

        public Builder database(String value) {
            database = value;
            return this;
        }

        public Builder username(String value) {
            username = value;
            return this;
        }

        public Builder password(String value) {
            password = value;
            return this;
        }

        public Builder jdbcParams(String value) {
            jdbcParams = value;
            return this;
        }

        public Builder maxPoolSize(int value) {
            maxPoolSize = value;
            return this;
        }

        public Builder queryTimeoutMs(long value) {
            queryTimeoutMs = value;
            return this;
        }

        public Builder reconnectIntervalMs(long value) {
            reconnectIntervalMs = value;
            return this;
        }

        public Builder serverId(String value) {
            serverId = value;
            return this;
        }

        public Builder networkStrikes(boolean value) {
            networkStrikes = value;
            return this;
        }

        public StorageConfig build() {
            return new StorageConfig(
                    type, dataDirectory, host, port, database, username, password, jdbcParams, maxPoolSize,
                    queryTimeoutMs, reconnectIntervalMs, serverId, networkStrikes);
        }
    }
}
