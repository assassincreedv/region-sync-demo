package com.example.regionsync.model.entity;

import jakarta.persistence.*;
import lombok.*;
import lombok.Builder.Default;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "sync_conflict_log")
public class SyncConflictLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conflict_id", length = 36)
    private String conflictId;

    @Column(name = "table_name")
    private String tableName;

    @Column(name = "business_key")
    private String businessKey;

    @Column(name = "local_region")
    private String localRegion;

    @Column(name = "remote_region")
    private String remoteRegion;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "conflict_detail", columnDefinition = "TEXT")
    private String conflictDetail;

    @Column(name = "resolution_action")
    private String resolutionAction;

    @Default
    @Column(name = "resolved")
    private boolean resolved = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
