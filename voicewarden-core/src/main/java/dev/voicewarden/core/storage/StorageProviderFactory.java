package dev.voicewarden.core.storage;

@FunctionalInterface
public interface StorageProviderFactory {

    StorageProvider open() throws Exception;

    static StorageProviderFactory forConfig(StorageConfig config) {
        return switch (config.type()) {
            case SQLITE -> () -> new SqliteStorageProvider(config);
            case MYSQL, MARIADB -> () -> new MysqlStorageProvider(config);
        };
    }
}
