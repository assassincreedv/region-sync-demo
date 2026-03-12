package com.example.regionsync.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "sync_event_log")
public class SyncEventLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", length = 64)
    private String eventId;

    @Column(name = "table_name")
    private String tableName;

    @Column(name = "operation_type")
    private String operationType;

    @Column(name = "business_key")
    private String businessKey;

    @Column(name = "source_region")
    private String sourceRegion;

    @Column(name = "action")
    private String action;

    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @PrePersist
    protected void onCreate() {
        this.processedAt = LocalDateTime.now();
    }
}
