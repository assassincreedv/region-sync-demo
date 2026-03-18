package com.example.regionsync.service;

import com.example.regionsync.model.entity.SyncConflictLog;
import com.example.regionsync.model.entity.SyncEventLog;
import com.example.regionsync.model.enums.ConflictResolutionAction;
import com.example.regionsync.model.enums.RejectionReason;
import com.example.regionsync.model.event.SyncRejection;
import com.example.regionsync.repository.SyncConflictLogRepository;
import com.example.regionsync.repository.SyncEventLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ConflictRecordServiceTest {

    @Mock
    private SyncConflictLogRepository syncConflictLogRepository;

    @Mock
    private SyncEventLogRepository syncEventLogRepository;

    @InjectMocks
    private ConflictRecordService conflictRecordService;

    @Test
    void recordConflict_shouldMapLocalRegionToTargetAndRemoteRegionToSource() {
        // The rejection is sent BY the rejecting region (sourceRegion)
        // TO the region whose event was rejected (targetRegion).
        // The rejection consumer runs on the targetRegion, so:
        //   localRegion  = targetRegion  (the consumer's own region)
        //   remoteRegion = sourceRegion  (the region that sent the rejection)
        SyncRejection rejection = SyncRejection.builder()
                .rejectionId("rej-1")
                .tableName("companies")
                .businessKey("CONFLICT-CO")
                .rejectionReason(RejectionReason.DUPLICATE_ENTITY)
                .sourceRegion("EU")    // EU sent the rejection
                .targetRegion("NA")    // NA's event was rejected; NA receives this
                .conflictDetail("Local entity already exists")
                .build();

        conflictRecordService.recordConflict(rejection);

        ArgumentCaptor<SyncConflictLog> captor = ArgumentCaptor.forClass(SyncConflictLog.class);
        verify(syncConflictLogRepository).save(captor.capture());

        SyncConflictLog saved = captor.getValue();
        assertEquals("NA", saved.getLocalRegion(),
                "localRegion should be the rejection target (the consumer's own region)");
        assertEquals("EU", saved.getRemoteRegion(),
                "remoteRegion should be the rejection source (the region that rejected)");
    }

    @Test
    void recordResolvedConflict_shouldSaveImmediatelyResolvedConflict() {
        conflictRecordService.recordResolvedConflict(
                "companies", "ACME", "NA", "EU",
                "DUPLICATE_ENTITY",
                "Company with companyCode 'ACME' has already been created in EU region",
                ConflictResolutionAction.AUTO_WIN);

        ArgumentCaptor<SyncConflictLog> captor = ArgumentCaptor.forClass(SyncConflictLog.class);
        verify(syncConflictLogRepository).save(captor.capture());

        SyncConflictLog saved = captor.getValue();
        assertEquals("companies", saved.getTableName());
        assertEquals("ACME", saved.getBusinessKey());
        assertEquals("NA", saved.getLocalRegion());
        assertEquals("EU", saved.getRemoteRegion());
        assertEquals("DUPLICATE_ENTITY", saved.getRejectionReason());
        assertEquals(ConflictResolutionAction.AUTO_WIN.name(), saved.getResolutionAction());
        assertTrue(saved.isResolved(), "Conflict should be immediately resolved");
        assertNotNull(saved.getResolvedAt(), "resolvedAt should be set");
        assertNotNull(saved.getConflictId(), "conflictId should be auto-generated");
    }

    @Test
    void recordEventDirect_shouldSaveEventLog() {
        conflictRecordService.recordEventDirect(
                "companies", "CREATE", "ACME", "NA",
                "REJECTED", "Duplicate detected");

        ArgumentCaptor<SyncEventLog> captor = ArgumentCaptor.forClass(SyncEventLog.class);
        verify(syncEventLogRepository).save(captor.capture());

        SyncEventLog saved = captor.getValue();
        assertEquals("companies", saved.getTableName());
        assertEquals("CREATE", saved.getOperationType());
        assertEquals("ACME", saved.getBusinessKey());
        assertEquals("NA", saved.getSourceRegion());
        assertEquals("REJECTED", saved.getAction());
        assertEquals("Duplicate detected", saved.getDetail());
        assertTrue(saved.getEventId().startsWith("api:companies:ACME:"),
                "Event ID should follow api: prefix pattern");
    }
}
