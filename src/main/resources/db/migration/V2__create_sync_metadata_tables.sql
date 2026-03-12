CREATE TABLE sync_conflict_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conflict_id VARCHAR(36) NOT NULL,
    table_name VARCHAR(100) NOT NULL,
    business_key VARCHAR(255) NOT NULL,
    local_region VARCHAR(4) NOT NULL,
    remote_region VARCHAR(4) NOT NULL,
    rejection_reason VARCHAR(50) NOT NULL,
    conflict_detail TEXT,
    resolution_action VARCHAR(30),
    resolved TINYINT(1) NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    resolved_at TIMESTAMP(3),
    INDEX idx_resolved (resolved),
    INDEX idx_conflict_id (conflict_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sync_event_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL,
    table_name VARCHAR(100) NOT NULL,
    operation_type VARCHAR(20) NOT NULL,
    business_key VARCHAR(255),
    source_region VARCHAR(4) NOT NULL,
    action VARCHAR(20) NOT NULL,
    detail TEXT,
    processed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    INDEX idx_event_id (event_id),
    INDEX idx_source_region (source_region)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
