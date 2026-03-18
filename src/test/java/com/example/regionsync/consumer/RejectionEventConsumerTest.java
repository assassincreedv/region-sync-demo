package com.example.regionsync.consumer;

import com.example.regionsync.config.SyncProperties;
import com.example.regionsync.metrics.SyncMetrics;
import com.example.regionsync.model.entity.Company;
import com.example.regionsync.model.entity.SyncConflictLog;
import com.example.regionsync.model.enums.ConflictResolutionAction;
import com.example.regionsync.model.enums.RejectionReason;
import com.example.regionsync.model.enums.SyncStatus;
import com.example.regionsync.model.event.SyncRejection;
import com.example.regionsync.repository.CompanyRepository;
import com.example.regionsync.repository.SyncConflictLogRepository;
import com.example.regionsync.service.ConflictRecordService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RejectionEventConsumerTest {

    @Mock private ConflictRecordService conflictRecordService;
    @Mock private CompanyRepository companyRepository;
    @Mock private SyncConflictLogRepository syncConflictLogRepository;
    @Mock private SyncMetrics syncMetrics;
    @Mock private SyncProperties syncProperties;
    @Mock private ObjectMapper objectMapper;
    @Mock private Acknowledgment ack;

    @InjectMocks
    private RejectionEventConsumer consumer;

    @BeforeEach
    void setUp() {
        lenient().when(syncProperties.getCurrentRegion()).thenReturn("NA");
    }

    @Test
    void consume_duplicateEntity_naYieldsToEu_deletesLocalDuplicate() throws Exception {
        // Scenario: NA sent a sync event that EU rejected (DUPLICATE_ENTITY).
        // EU sends the rejection back with sourceRegion=EU, targetRegion=NA.
        // This consumer runs on NA (currentRegion=NA). Since EU < NA
        // lexicographically, NA must yield and delete its local duplicate.
        SyncRejection rejection = SyncRejection.builder()
                .rejectionId("rej-1")
                .tableName("companies")
                .businessKey("DUP-CO")
                .rejectionReason(RejectionReason.DUPLICATE_ENTITY)
                .sourceRegion("EU")
                .targetRegion("NA")
                .conflictDetail("Local entity already exists")
                .build();

        when(objectMapper.readValue(anyString(), eq(SyncRejection.class))).thenReturn(rejection);

        Company company = Company.builder().companyCode("DUP-CO").name("Dup").build();
        when(companyRepository.findByCompanyCode("DUP-CO")).thenReturn(Optional.of(company));

        SyncConflictLog conflictLog = SyncConflictLog.builder()
                .businessKey("DUP-CO")
                .rejectionReason("DUPLICATE_ENTITY")
                .resolved(false)
                .build();
        when(syncConflictLogRepository.findByResolvedFalse()).thenReturn(List.of(conflictLog));

        consumer.consume("{}", ack);

        // NA should yield: mark syncedFromRemote, flush, then delete
        assertTrue(company.isSyncedFromRemote());
        var inOrder = inOrder(companyRepository);
        inOrder.verify(companyRepository).saveAndFlush(company);
        inOrder.verify(companyRepository).delete(company);

        // Conflict should be marked resolved with AUTO_YIELD
        assertTrue(conflictLog.isResolved());
        assertEquals(ConflictResolutionAction.AUTO_YIELD.name(), conflictLog.getResolutionAction());
        verify(ack).acknowledge();
    }

    @Test
    void consume_duplicateEntity_euWinsOverNa_clearsConflictStatus() throws Exception {
        // EU receives a DUPLICATE_ENTITY rejection from NA. Since EU < NA, EU wins.
        when(syncProperties.getCurrentRegion()).thenReturn("EU");

        SyncRejection rejection = SyncRejection.builder()
                .rejectionId("rej-2")
                .tableName("companies")
                .businessKey("DUP-CO")
                .rejectionReason(RejectionReason.DUPLICATE_ENTITY)
                .sourceRegion("NA")
                .targetRegion("EU")
                .conflictDetail("Local entity already exists")
                .build();

        when(objectMapper.readValue(anyString(), eq(SyncRejection.class))).thenReturn(rejection);

        Company company = Company.builder().companyCode("DUP-CO").name("Dup").build();
        company.setSyncStatus(SyncStatus.CONFLICT);
        company.setSyncedFromRemote(true);
        when(companyRepository.findByCompanyCode("DUP-CO")).thenReturn(Optional.of(company));

        SyncConflictLog conflictLog = SyncConflictLog.builder()
                .businessKey("DUP-CO")
                .rejectionReason("DUPLICATE_ENTITY")
                .resolved(false)
                .build();
        when(syncConflictLogRepository.findByResolvedFalse()).thenReturn(List.of(conflictLog));

        consumer.consume("{}", ack);

        // EU should win: no deletion, CONFLICT status cleared
        verify(companyRepository, never()).delete(any());
        verify(companyRepository).save(company);
        assertEquals(SyncStatus.NORMAL, company.getSyncStatus());
        assertNull(company.getSyncConflictDetail());
        assertFalse(company.isSyncedFromRemote());

        // Conflict should be marked resolved with AUTO_WIN
        assertTrue(conflictLog.isResolved());
        assertEquals(ConflictResolutionAction.AUTO_WIN.name(), conflictLog.getResolutionAction());
        verify(ack).acknowledge();
    }

    @Test
    void consume_versionConflict_marksEntityAsConflict() throws Exception {
        // Non-duplicate rejection: should still mark entity as CONFLICT
        SyncRejection rejection = SyncRejection.builder()
                .rejectionId("rej-3")
                .tableName("companies")
                .businessKey("VER-CO")
                .rejectionReason(RejectionReason.VERSION_CONFLICT)
                .sourceRegion("EU")
                .targetRegion("NA")
                .conflictDetail("Version mismatch")
                .build();

        when(objectMapper.readValue(anyString(), eq(SyncRejection.class))).thenReturn(rejection);

        Company company = Company.builder().companyCode("VER-CO").name("Ver").build();
        when(companyRepository.findByCompanyCode("VER-CO")).thenReturn(Optional.of(company));

        consumer.consume("{}", ack);

        // Entity should be marked as CONFLICT (not auto-resolved)
        assertEquals(SyncStatus.CONFLICT, company.getSyncStatus());
        assertTrue(company.isSyncedFromRemote());
        verify(companyRepository).save(company);
        verify(companyRepository, never()).delete(any());
        verify(ack).acknowledge();
    }
}
