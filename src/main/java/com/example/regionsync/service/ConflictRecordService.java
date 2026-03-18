package com.example.regionsync.service;

import com.example.regionsync.model.entity.SyncConflictLog;
import com.example.regionsync.model.entity.SyncEventLog;
import com.example.regionsync.model.enums.ConflictResolutionAction;
import com.example.regionsync.model.event.SyncEvent;
import com.example.regionsync.model.event.SyncRejection;
import com.example.regionsync.repository.SyncConflictLogRepository;
import com.example.regionsync.repository.SyncEventLogRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class ConflictRecordService {

    private final SyncConflictLogRepository syncConflictLogRepository;
    private final SyncEventLogRepository syncEventLogRepository;

    public void recordConflict(SyncRejection rejection) {
        SyncConflictLog conflictLog = SyncConflictLog.builder()
                .conflictId(rejection.getRejectionId())
                .tableName(rejection.getTableName())
                .businessKey(rejection.getBusinessKey())
                .localRegion(rejection.getTargetRegion())
                .remoteRegion(rejection.getSourceRegion())
                .rejectionReason(rejection.getRejectionReason() != null
                        ? rejection.getRejectionReason().name() : null)
                .conflictDetail(rejection.getConflictDetail())
                .resolutionAction(ConflictResolutionAction.PENDING.name())
                .resolved(false)
                .build();
        syncConflictLogRepository.save(conflictLog);
        log.info("Recorded conflict: conflictId={} table={} businessKey={}",
                rejection.getRejectionId(), rejection.getTableName(), rejection.getBusinessKey());
    }

    public void recordEvent(SyncEvent event, String action, String detail) {
        SyncEventLog eventLog = SyncEventLog.builder()
                .eventId(event.getEventId())
                .tableName(event.getTableName())
                .operationType(event.getOperationType() != null ? event.getOperationType().name() : null)
                .businessKey(event.getBusinessKey())
                .sourceRegion(event.getSourceRegion())
                .action(action)
                .detail(detail)
                .build();
        syncEventLogRepository.save(eventLog);
    }

    @Transactional(readOnly = true)
    public List<SyncConflictLog> findUnresolved() {
        return syncConflictLogRepository.findByResolvedFalse();
    }

    public SyncConflictLog markResolved(Long id, ConflictResolutionAction action) {
        SyncConflictLog log = syncConflictLogRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("SyncConflictLog not found with id: " + id));
        log.setResolved(true);
        log.setResolvedAt(LocalDateTime.now());
        log.setResolutionAction(action.name());
        return syncConflictLogRepository.save(log);
    }

    /**
     * Records a conflict detected at the API level (e.g. duplicate company
     * creation attempt against an entity synced from a remote region).
     * The conflict is immediately resolved because the existing (remote)
     * entity wins and the local creation is rejected.
     */
    public void recordResolvedConflict(String tableName, String businessKey,
                                       String localRegion, String remoteRegion,
                                       String rejectionReason, String detail,
                                       ConflictResolutionAction action) {
        SyncConflictLog conflictLog = SyncConflictLog.builder()
                .conflictId(UUID.randomUUID().toString())
                .tableName(tableName)
                .businessKey(businessKey)
                .localRegion(localRegion)
                .remoteRegion(remoteRegion)
                .rejectionReason(rejectionReason)
                .conflictDetail(detail)
                .resolutionAction(action.name())
                .resolved(true)
                .resolvedAt(LocalDateTime.now())
                .build();
        syncConflictLogRepository.save(conflictLog);
        log.info("Recorded resolved conflict: table={} businessKey={} localRegion={} remoteRegion={} action={}",
                tableName, businessKey, localRegion, remoteRegion, action);
    }

    /**
     * Records a sync event directly, for cases where no {@link SyncEvent}
     * object is available (e.g. API-level duplicate detection).
     */
    public void recordEventDirect(String tableName, String operationType,
                                  String businessKey, String sourceRegion,
                                  String action, String detail) {
        SyncEventLog eventLog = SyncEventLog.builder()
                .eventId("api:" + tableName + ":" + businessKey + ":" + System.currentTimeMillis())
                .tableName(tableName)
                .operationType(operationType)
                .businessKey(businessKey)
                .sourceRegion(sourceRegion)
                .action(action)
                .detail(detail)
                .build();
        syncEventLogRepository.save(eventLog);
    }
}
