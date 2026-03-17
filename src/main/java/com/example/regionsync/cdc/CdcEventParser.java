package com.example.regionsync.cdc;

import com.example.regionsync.model.enums.OperationType;
import com.example.regionsync.model.event.SyncEvent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class CdcEventParser {

    private final ObjectMapper objectMapper;

    public Optional<SyncEvent> parse(String json, String fallbackTableName) {
        try {
            Map<String, Object> root = objectMapper.readValue(json, new TypeReference<>() {});

            // Debezium/Kafka Connect JSON 通常是真实数据放在 payload 里
            Object payloadObj = root.get("payload");
            Map<String, Object> data;

            if (payloadObj instanceof Map<?, ?> payloadMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) payloadMap;
                data = casted;
            } else {
                // 兼容扁平结构
                data = root;
            }

            String op = getString(data, "__op");
            OperationType operationType = mapOperationType(op);
            if (operationType == null) {
                log.warn("Unknown __op value '{}' for table '{}', skipping", op, fallbackTableName);
                return Optional.empty();
            }

            String actualTableName = getString(data, "__table");
            if (actualTableName == null || actualTableName.isBlank()) {
                actualTableName = fallbackTableName;
            }

            Long sourceTimestampMs = getLong(data, "__source_ts_ms");

            // 你当前消息里没有 __source_region，真实字段是 source_region
            String sourceRegion = getString(data, "source_region");

            String businessKey = getString(data, "company_code");

            Integer remoteVersion = getInteger(data, "version");
            if (remoteVersion == null) {
                remoteVersion = 0;
            }

            String eventId = actualTableName + ":"
                    + (businessKey != null ? businessKey : "unknown") + ":"
                    + (sourceTimestampMs != null ? sourceTimestampMs : "unknown");

            return Optional.of(SyncEvent.builder()
                    .eventId(eventId)
                    .tableName(actualTableName)
                    .operationType(operationType)
                    .businessKey(businessKey)
                    .sourceRegion(sourceRegion)
                    .payload(data)
                    .sourceTimestampMs(sourceTimestampMs)
                    .remoteVersion(remoteVersion)
                    .build());

        } catch (Exception e) {
            log.warn("Failed to parse CDC event JSON for table '{}': {}", fallbackTableName, e.getMessage(), e);
            return Optional.empty();
        }
    }

    private OperationType mapOperationType(String op) {
        if (op == null) {
            return null;
        }
        return switch (op) {
            case "c" -> OperationType.CREATE;
            case "u" -> OperationType.UPDATE;
            case "d" -> OperationType.DELETE;
            case "r" -> OperationType.READ;
            default -> null;
        };
    }

    private String getString(Map<String, Object> data, String key) {
        Object value = data.get(key);
        return value != null ? value.toString() : null;
    }

    private Long getLong(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private Integer getInteger(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception e) {
            return null;
        }
    }
}