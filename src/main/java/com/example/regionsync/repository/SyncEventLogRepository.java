package com.example.regionsync.repository;

import com.example.regionsync.model.entity.SyncEventLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SyncEventLogRepository extends JpaRepository<SyncEventLog, Long> {
}
