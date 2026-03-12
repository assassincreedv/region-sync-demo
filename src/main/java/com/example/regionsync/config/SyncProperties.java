package com.example.regionsync.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "sync")
public class SyncProperties {

    private String currentRegion;
    private String remoteRegion;
    private String databaseName;
    private Map<String, TableConfig> tables;
    private ConflictConfig conflict = new ConflictConfig();

    @Data
    public static class TableConfig {
        private String name;
        private String businessKeyField;
        private boolean dedupOnInsert;
        private String conflictStrategy;
    }

    @Data
    public static class ConflictConfig {
        private boolean autoResolveEnabled;
        private String tieBreakerStrategy;
    }
}
