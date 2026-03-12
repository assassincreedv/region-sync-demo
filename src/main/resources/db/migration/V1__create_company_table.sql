CREATE TABLE companies (
    id VARCHAR(36) PRIMARY KEY,
    company_code VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    address VARCHAR(500),
    contact_email VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    source_region VARCHAR(4) NOT NULL,
    synced_from_remote TINYINT(1) NOT NULL DEFAULT 0,
    sync_status VARCHAR(20) NOT NULL DEFAULT 'NORMAL',
    sync_conflict_detail TEXT,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_company_code (company_code),
    INDEX idx_source_region (source_region),
    INDEX idx_sync_status (sync_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
