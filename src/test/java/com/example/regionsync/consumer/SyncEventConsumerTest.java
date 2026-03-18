package com.example.regionsync.consumer;

import com.example.regionsync.cdc.CdcEventParser;
import com.example.regionsync.config.SyncProperties;
import com.example.regionsync.config.SyncTopicsProperties;
import com.example.regionsync.dedup.EventDeduplicationService;
import com.example.regionsync.dedup.GlobalLockService;
import com.example.regionsync.mapper.EntityMapper;
import com.example.regionsync.mapper.EntityMapperRegistry;
import com.example.regionsync.metrics.SyncMetrics;
import com.example.regionsync.model.entity.Company;
import com.example.regionsync.model.enums.OperationType;
import com.example.regionsync.model.event.SyncEvent;
import com.example.regionsync.model.event.SyncResult;
import com.example.regionsync.repository.CompanyRepository;
import com.example.regionsync.service.ConflictRecordService;
import com.example.regionsync.service.SyncApplyService;
import com.example.regionsync.service.SyncRejectionService;
import com.example.regionsync.strategy.ConflictStrategy;
import com.example.regionsync.strategy.ConflictStrategyFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncEventConsumerTest {

    @Mock private CdcEventParser cdcEventParser;
    @Mock private SyncProperties syncProperties;
    @Mock private SyncTopicsProperties syncTopicsProperties;
    @Mock private EventDeduplicationService eventDeduplicationService;
    @Mock private GlobalLockService globalLockService;
    @Mock private EntityMapperRegistry entityMapperRegistry;
    @Mock private CompanyRepository companyRepository;
    @Mock private SyncApplyService syncApplyService;
    @Mock private SyncRejectionService syncRejectionService;
    @Mock private ConflictRecordService conflictRecordService;
    @Mock private SyncMetrics syncMetrics;
    @Mock private ConflictStrategyFactory conflictStrategyFactory;
    @Mock private Acknowledgment ack;

    @InjectMocks
    private SyncEventConsumer consumer;

    @BeforeEach
    void setUp() {
        lenient().when(syncProperties.getCurrentRegion()).thenReturn("NA");
    }

    @Test
    void consume_skipsEventWhenSyncedFromRemoteIsTrue() {
        // A CDC event with synced_from_remote=true is an echo of a previous
        // sync-applied write.  It must be skipped to break the loop.
        Map<String, Object> payload = new HashMap<>();
        payload.put("synced_from_remote", true);
        payload.put("source_region", "EU");
        payload.put("company_code", "LOOP-CO");
        payload.put("__table", "companies");

        SyncEvent event = SyncEvent.builder()
                .eventId("companies:LOOP-CO:1234567890")
                .tableName("companies")
                .operationType(OperationType.UPDATE)
                .businessKey("LOOP-CO")
                .sourceRegion("EU")
                .payload(payload)
                .remoteVersion(5)
                .build();

        when(cdcEventParser.parse(anyString(), anyString())).thenReturn(Optional.of(event));

        consumer.consume("{\"__table\":\"companies\"}", ack);

        // The event must be skipped — no apply, no rejection, just ack
        verify(syncApplyService, never()).applyUpdate(any(), any());
        verify(syncApplyService, never()).applyCreate(any(), any());
        verify(syncMetrics).incrementSkipped();
        verify(ack).acknowledge();
    }

    @Test
    void consume_skipsEventWhenSyncedFromRemoteIsStringTrue() {
        // MySQL boolean columns may arrive as the string "true" or integer 1
        Map<String, Object> payload = new HashMap<>();
        payload.put("synced_from_remote", "true");
        payload.put("source_region", "EU");
        payload.put("company_code", "LOOP-CO");
        payload.put("__table", "companies");

        SyncEvent event = SyncEvent.builder()
                .eventId("companies:LOOP-CO:1234567890")
                .tableName("companies")
                .operationType(OperationType.UPDATE)
                .businessKey("LOOP-CO")
                .sourceRegion("EU")
                .payload(payload)
                .remoteVersion(5)
                .build();

        when(cdcEventParser.parse(anyString(), anyString())).thenReturn(Optional.of(event));

        consumer.consume("{\"__table\":\"companies\"}", ack);

        verify(syncApplyService, never()).applyUpdate(any(), any());
        verify(syncMetrics).incrementSkipped();
        verify(ack).acknowledge();
    }

    @Test
    void consume_skipsEventWhenSyncedFromRemoteIsOne() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("synced_from_remote", 1);
        payload.put("source_region", "EU");
        payload.put("company_code", "LOOP-CO");
        payload.put("__table", "companies");

        SyncEvent event = SyncEvent.builder()
                .eventId("companies:LOOP-CO:1234567890")
                .tableName("companies")
                .operationType(OperationType.UPDATE)
                .businessKey("LOOP-CO")
                .sourceRegion("EU")
                .payload(payload)
                .remoteVersion(5)
                .build();

        when(cdcEventParser.parse(anyString(), anyString())).thenReturn(Optional.of(event));

        consumer.consume("{\"__table\":\"companies\"}", ack);

        verify(syncApplyService, never()).applyUpdate(any(), any());
        verify(syncMetrics).incrementSkipped();
        verify(ack).acknowledge();
    }

    @Test
    void consume_processesEventWhenSyncedFromRemoteIsFalse() {
        // A CDC event with synced_from_remote=false is a genuine local change
        // and must be processed normally.
        Map<String, Object> payload = new HashMap<>();
        payload.put("synced_from_remote", false);
        payload.put("source_region", "EU");
        payload.put("company_code", "REAL-CO");
        payload.put("__table", "companies");

        SyncEvent event = SyncEvent.builder()
                .eventId("companies:REAL-CO:1234567890")
                .tableName("companies")
                .operationType(OperationType.UPDATE)
                .businessKey("REAL-CO")
                .sourceRegion("EU")
                .payload(payload)
                .remoteVersion(5)
                .build();

        when(cdcEventParser.parse(anyString(), anyString())).thenReturn(Optional.of(event));
        when(eventDeduplicationService.isDuplicate(anyString())).thenReturn(false);

        EntityMapper<?> mapper = mock(EntityMapper.class);
        when(entityMapperRegistry.findMapper("companies")).thenReturn(Optional.of(mapper));

        ConflictStrategy strategy = mock(ConflictStrategy.class);
        when(conflictStrategyFactory.getStrategy(anyString())).thenReturn(strategy);

        Company company = Company.builder().companyCode("REAL-CO").build();
        company.setVersion(3);
        when(companyRepository.findByCompanyCode("REAL-CO")).thenReturn(Optional.of(company));
        when(strategy.shouldAcceptUpdate(event, 3)).thenReturn(true);

        SyncResult result = SyncResult.builder()
                .eventId(event.getEventId())
                .success(true)
                .action("APPLIED")
                .reason("Updated from remote event")
                .build();
        when(syncApplyService.applyUpdate(any(), any())).thenReturn(result);

        consumer.consume("{\"__table\":\"companies\"}", ack);

        // The event should be processed — applyUpdate must be called
        verify(syncApplyService).applyUpdate(any(), any());
        verify(ack).acknowledge();
    }

    @Test
    void consume_skipsEventFromOwnRegion() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("synced_from_remote", false);
        payload.put("source_region", "NA");
        payload.put("company_code", "OWN-CO");
        payload.put("__table", "companies");

        SyncEvent event = SyncEvent.builder()
                .eventId("companies:OWN-CO:1234567890")
                .tableName("companies")
                .operationType(OperationType.UPDATE)
                .businessKey("OWN-CO")
                .sourceRegion("NA")
                .payload(payload)
                .remoteVersion(5)
                .build();

        when(cdcEventParser.parse(anyString(), anyString())).thenReturn(Optional.of(event));

        consumer.consume("{\"__table\":\"companies\"}", ack);

        verify(syncApplyService, never()).applyUpdate(any(), any());
        verify(syncMetrics).incrementSkipped();
        verify(ack).acknowledge();
    }

    @Test
    void consume_createRejected_recordsConflictLocally() {
        // When a remote CREATE is rejected because a local entity exists,
        // the conflict should be recorded in sync_conflict_log on the
        // rejecting side.
        Map<String, Object> payload = new HashMap<>();
        payload.put("synced_from_remote", false);
        payload.put("source_region", "EU");
        payload.put("company_code", "DUP-CO");
        payload.put("__table", "companies");

        SyncEvent event = SyncEvent.builder()
                .eventId("companies:DUP-CO:1234567890")
                .tableName("companies")
                .operationType(OperationType.CREATE)
                .businessKey("DUP-CO")
                .sourceRegion("EU")
                .payload(payload)
                .remoteVersion(0)
                .build();

        when(cdcEventParser.parse(anyString(), anyString())).thenReturn(Optional.of(event));
        when(eventDeduplicationService.isDuplicate(anyString())).thenReturn(false);

        EntityMapper<?> mapper = mock(EntityMapper.class);
        when(entityMapperRegistry.findMapper("companies")).thenReturn(Optional.of(mapper));

        ConflictStrategy strategy = mock(ConflictStrategy.class);
        when(conflictStrategyFactory.getStrategy(anyString())).thenReturn(strategy);

        // Local entity exists — CREATE should be rejected
        when(companyRepository.findByCompanyCode("DUP-CO")).thenReturn(
                Optional.of(Company.builder().companyCode("DUP-CO").build()));
        when(strategy.shouldAcceptCreate(any(), eq(true))).thenReturn(false);

        // GlobalLockService must run the lambda synchronously
        doAnswer(inv -> {
            Runnable action = inv.getArgument(1);
            action.run();
            return null;
        }).when(globalLockService).executeWithLock(anyString(), any(Runnable.class));

        consumer.consume("{\"__table\":\"companies\"}", ack);

        // Verify the conflict is recorded locally via recordResolvedConflict
        verify(conflictRecordService).recordResolvedConflict(
                eq("companies"), eq("DUP-CO"), eq("NA"), eq("EU"),
                eq("DUPLICATE_ENTITY"), anyString(),
                eq(com.example.regionsync.model.enums.ConflictResolutionAction.AUTO_WIN));
        verify(syncRejectionService).sendRejection(any());
        verify(syncMetrics).incrementConflicts();
        verify(ack).acknowledge();
    }
}
