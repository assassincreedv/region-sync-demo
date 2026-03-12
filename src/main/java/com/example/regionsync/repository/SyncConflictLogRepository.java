package com.example.regionsync.repository;

import com.example.regionsync.model.entity.SyncConflictLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SyncConflictLogRepository extends JpaRepository<SyncConflictLog, Long> {

    List<SyncConflictLog> findByResolvedFalse();

    @Query("SELECT s FROM SyncConflictLog s WHERE s.resolved = false " +
           "AND s.rejectionReason = :rejectionReason " +
           "AND s.createdAt < :cutoff")
    List<SyncConflictLog> findUnresolvedByReasonBefore(
            @Param("rejectionReason") String rejectionReason,
            @Param("cutoff") LocalDateTime cutoff);
}
