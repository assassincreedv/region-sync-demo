package com.example.regionsync.model.base;

import com.example.regionsync.model.enums.SyncStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@MappedSuperclass
public abstract class SyncableEntity {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "source_region", length = 4)
    private String sourceRegion;

    @Column(name = "synced_from_remote")
    private boolean syncedFromRemote = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status")
    private SyncStatus syncStatus = SyncStatus.NORMAL;

    @Column(name = "sync_conflict_detail", columnDefinition = "TEXT")
    private String syncConflictDetail;

    @Version
    @Column(name = "version")
    private int version;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (this.id == null) {
            this.id = UUID.randomUUID().toString();
        }
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
