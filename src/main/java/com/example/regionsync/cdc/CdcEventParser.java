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

    public Optional<SyncEvent> parse(String json, String tableName) {
        try {
            Map<String, Object> data = objectMapper.readValue(json, new TypeReference<>() {});

            String op = (String) data.get("__op");
            OperationType operationType = mapOperationType(op);
            if (operationType == null) {
                log.warn("Unknown __op value '{}' for table '{}', skipping", op, tableName);
                return Optional.empty();
            }

            Long sourceTimestampMs = null;
            Object tsObj = data.get("__source_ts_ms");
            if (tsObj != null) {
                sourceTimestampMs = Long.parseLong(tsObj.toString());
            }

            String sourceRegion = (String) data.get("__source_region");

            Object businessKeyObj = data.get("company_code");
            String businessKey = businessKeyObj != null ? businessKeyObj.toString() : null;

            Object versionObj = data.get("version");
            Integer remoteVersion = versionObj != null ? Integer.parseInt(versionObj.toString()) : 0;

            String eventId = tableName + ":" + (businessKey != null ? businessKey : "unknown") + ":"
                    + (sourceTimestampMs != null ? sourceTimestampMs : "unknown");

            return Optional.of(SyncEvent.builder()
                    .eventId(eventId)
                    .tableName(tableName)
                    .operationType(operationType)
                    .businessKey(businessKey)
                    .sourceRegion(sourceRegion)
                    .payload(data)
                    .sourceTimestampMs(sourceTimestampMs)
                    .remoteVersion(remoteVersion)
                    .build());

        } catch (Exception e) {
            log.warn("Failed to parse CDC event JSON for table '{}': {}", tableName, e.getMessage());
            return Optional.empty();
        }
    }

    private OperationType mapOperationType(String op) {
        if (op == null) return null;
        return switch (op) {
            case "c" -> OperationType.CREATE;
            case "u" -> OperationType.UPDATE;
            case "d" -> OperationType.DELETE;
            case "r" -> OperationType.READ;
            default -> null;
        };
    }
}
