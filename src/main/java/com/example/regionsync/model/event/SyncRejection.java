package com.example.regionsync.model.event;

import com.example.regionsync.model.enums.RejectionReason;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncRejection {

    private String rejectionId;
    private String originalEventId;
    private String tableName;
    private String businessKey;
    private RejectionReason rejectionReason;
    private String sourceRegion;
    private String targetRegion;
    private Integer localVersion;
    private Integer remoteVersion;
    private String conflictDetail;
    private LocalDateTime timestamp;
}
