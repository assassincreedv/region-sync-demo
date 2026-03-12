package com.example.regionsync.model.event;

import com.example.regionsync.model.enums.OperationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncEvent {

    private String eventId;
    private String tableName;
    private OperationType operationType;
    private String businessKey;
    private String sourceRegion;
    private Map<String, Object> payload;
    private Long sourceTimestampMs;
    private Integer remoteVersion;
}
