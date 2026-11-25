package dev.voicewarden.core.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class MysqlStorageProvider extends JdbcStorageProvider {

    static final long MIN_HIKARI_TIMEOUT_MS = 250L;

    private static final List<String> SCHEMA = List.of(
            "CREATE TABLE IF NOT EXISTS " + MUTES + " ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + " player_id CHAR(36) NOT NULL,"
                    + " player_name VARCHAR(64) NOT NULL,"
                    + " reason VARCHAR(512) NOT NULL,"
                    + " moderator VARCHAR(64) NOT NULL,"
                    + " created_at BIGINT NOT NULL,"
                    + " expires_at BIGINT NULL,"
                    + " active TINYINT(1) NOT NULL DEFAULT 1,"
                    + " server_id VARCHAR(64) NOT NULL,"
                    + " INDEX idx_vw_mutes_player (player_id, created_at),"
                    + " INDEX idx_vw_mutes_active (active, expires_at),"
                    + " INDEX idx_vw_mutes_server (server_id, player_id, created_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
            "CREATE TABLE IF NOT EXISTS " + STRIKES + " ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + " player_id CHAR(36) NOT NULL,"
                    + " player_name VARCHAR(64) NOT NULL,"
                    + " severity VARCHAR(16) NOT NULL,"
                    + " matched_words TEXT NOT NULL,"
                    + " transcript TEXT NOT NULL,"
                    + " created_at BIGINT NOT NULL,"
                    + " server_id VARCHAR(64) NOT NULL,"
                    + " pardoned TINYINT(1) NOT NULL DEFAULT 0,"
                    + " INDEX idx_vw_strikes_player (player_id, pardoned, created_at),"
                    + " INDEX idx_vw_strikes_server (server_id, player_id, created_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
            "CREATE TABLE IF NOT EXISTS " + REPORTS + " ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                    + " reporter_id CHAR(36) NOT NULL,"
                    + " reporter_name VARCHAR(64) NOT NULL,"
                    + " target_id CHAR(36) NOT NULL,"
                    + " target_name VARCHAR(64) NOT NULL,"
                    + " window_minutes INT NOT NULL,"
                    + " transcript_snapshot MEDIUMTEXT NOT NULL,"
                    + " created_at BIGINT NOT NULL,"
                    + " reviewed TINYINT(1) NOT NULL DEFAULT 0,"
                    + " reviewed_by VARCHAR(64) NULL,"
                    + " reviewed_at BIGINT NULL,"
                    + " server_id VARCHAR(64) NOT NULL,"
                    + " INDEX idx_vw_reports_open (reviewed, created_at),"
                    + " INDEX idx_vw_reports_reporter (reporter_id, reviewed)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

    private final HikariDataSource dataSource;

    public MysqlStorageProvider(StorageConfig config) {
        super(config);
        if (!config.type().isRemote()) {
            throw new IllegalArgumentException("MysqlStorageProvider needs storage.type MYSQL or MARIADB, got " + config.type());
        }
        HikariDataSource opened;
        try {
            opened = new HikariDataSource(hikariConfig(config));
        } catch (RuntimeException e) {
            throw new StorageException("Cannot connect to " + config.type() + " at " + config.host() + ":" + config.port()
                    + "/" + config.database() + ": " + rootMessage(e), e);
        }
        this.dataSource = opened;
        try (Connection connection = dataSource.getConnection()) {
            executeDdl(connection, SCHEMA);
        } catch (SQLException e) {
            dataSource.close();
            throw new StorageException("Cannot create VoiceWarden tables in " + config.database() + ": " + e.getMessage(), e);
        } catch (Throwable t) {
            dataSource.close();
            throw t;
        }
    }

    public static String jdbcUrl(StorageConfig config) {
        String scheme = config.type() == StorageType.MARIADB ? "mariadb" : "mysql";
        String params = config.jdbcParams();
        while (params.startsWith("?") || params.startsWith("&")) {
            params = params.substring(1);
        }
        return "jdbc:" + scheme + "://" + config.host() + ":" + config.port() + "/" + config.database()
                + (params.isEmpty() ? "" : "?" + params);
    }

    public static String driverClassName(StorageType type) {
        return switch (type) {
            case MARIADB -> "org.mariadb.jdbc.Driver";
            case MYSQL -> "com.mysql.cj.jdbc.Driver";
            case SQLITE -> throw new IllegalArgumentException("SQLite does not use a pooled JDBC driver");
        };
    }

    public static HikariConfig hikariConfig(StorageConfig config) {
        long connectionTimeout = Math.max(MIN_HIKARI_TIMEOUT_MS, config.queryTimeoutMs());
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("VoiceWarden-" + config.type().name().toLowerCase(Locale.ROOT));
        hikari.setJdbcUrl(jdbcUrl(config));
        hikari.setDriverClassName(driverClassName(config.type()));
        hikari.setUsername(config.username());
        hikari.setPassword(config.password());
        hikari.setMaximumPoolSize(config.maxPoolSize());
        hikari.setMinimumIdle(Math.min(2, config.maxPoolSize()));
        hikari.setConnectionTimeout(connectionTimeout);
        hikari.setValidationTimeout(Math.max(MIN_HIKARI_TIMEOUT_MS, connectionTimeout / 2));
        hikari.setMaxLifetime(TimeUnit.MINUTES.toMillis(30));
        hikari.setIdleTimeout(TimeUnit.MINUTES.toMillis(10));
        hikari.setKeepaliveTime(TimeUnit.MINUTES.toMillis(5));
        hikari.setInitializationFailTimeout(connectionTimeout);
        hikari.addDataSourceProperty("connectTimeout", String.valueOf(connectionTimeout));
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(Math.max(10_000L, config.queryTimeoutMs() * 5)));
        if (config.type() == StorageType.MYSQL) {
            hikari.addDataSourceProperty("cachePrepStmts", "true");
            hikari.addDataSourceProperty("prepStmtCacheSize", "250");
            hikari.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            hikari.addDataSourceProperty("useServerPrepStmts", "true");
        }
        return hikari;
    }

    @Override
    protected <T> T withMuteConnection(SqlWork<T> work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return work.run(connection);
        }
    }

    @Override
    protected <T> T withReportConnection(SqlWork<T> work) throws SQLException {
        return withMuteConnection(work);
    }

    @Override
    public boolean isAvailable() {
        if (dataSource.isClosed() || !dataSource.isRunning()) {
            return false;
        }
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(queryTimeoutSeconds);
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
