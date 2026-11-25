package dev.voicewarden.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MysqlStorageProviderTest {

    private static StorageConfig.Builder remote(StorageType type) {
        return StorageConfig.builder(type, Path.of("data"))
                .host("db.internal")
                .port(3307)
                .database("voicewarden")
                .username("vw")
                .password("hunter2")
                .maxPoolSize(6);
    }

    @Test
    void buildsMysqlAndMariadbUrls() {
        assertEquals("jdbc:mysql://db.internal:3307/voicewarden",
                MysqlStorageProvider.jdbcUrl(remote(StorageType.MYSQL).build()));
        assertEquals("jdbc:mariadb://db.internal:3307/voicewarden?useSSL=false&a=b",
                MysqlStorageProvider.jdbcUrl(remote(StorageType.MARIADB).jdbcParams("?useSSL=false&a=b").build()));
    }

    @Test
    void hikariConfigUsesShortTimeoutsAndTheRightDriver() {
        HikariConfig mysql = MysqlStorageProvider.hikariConfig(remote(StorageType.MYSQL).build());
        assertEquals("com.mysql.cj.jdbc.Driver", mysql.getDriverClassName());
        assertEquals(2000L, mysql.getConnectionTimeout());
        assertEquals(6, mysql.getMaximumPoolSize());
        assertTrue(mysql.getMaxLifetime() > mysql.getKeepaliveTime());
        assertEquals("vw", mysql.getUsername());

        HikariConfig mariadb = MysqlStorageProvider.hikariConfig(remote(StorageType.MARIADB).queryTimeoutMs(100).build());
        assertEquals("org.mariadb.jdbc.Driver", mariadb.getDriverClassName());
        assertEquals(MysqlStorageProvider.MIN_HIKARI_TIMEOUT_MS, mariadb.getConnectionTimeout());
    }

    @Test
    void configDefaultsAndRedaction() {
        StorageConfig config = StorageConfig.builder(StorageType.MYSQL, Path.of("data"))
                .database("vw").password("hunter2").queryTimeoutMs(0).reconnectIntervalMs(-1).serverId(" ").build();
        assertEquals(StorageConfig.DEFAULT_QUERY_TIMEOUT_MS, config.queryTimeoutMs());
        assertEquals(StorageConfig.DEFAULT_RECONNECT_INTERVAL_MS, config.reconnectIntervalMs());
        assertEquals(StorageConfig.DEFAULT_SERVER_ID, config.serverId());
        assertEquals(2, config.queryTimeoutSeconds());
        assertTrue(config.networkStrikes());
        assertFalse(config.toString().contains("hunter2"));
        assertEquals(1, StorageConfig.builder(StorageType.SQLITE, Path.of("x")).queryTimeoutMs(1).build().queryTimeoutSeconds());
    }

    @Test
    void remoteConfigNeedsADatabaseName() {
        assertThrows(IllegalArgumentException.class,
                () -> StorageConfig.builder(StorageType.MARIADB, Path.of("data")).build());
        assertEquals(StorageType.MARIADB, StorageType.parse("mariadb"));
        assertThrows(IllegalArgumentException.class, () -> StorageType.parse("postgres"));
    }
}
