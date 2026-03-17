package com.example.regionsync.consumer;

import com.example.regionsync.cdc.CdcEventParser;
import com.example.regionsync.config.SyncProperties;
import com.example.regionsync.config.SyncTopicsProperties;
import com.example.regionsync.dedup.EventDeduplicationService;
import com.example.regionsync.dedup.GlobalLockService;
import com.example.regionsync.mapper.CompanyMapper;
import com.example.regionsync.mapper.EntityMapperRegistry;
import com.example.regionsync.metrics.SyncMetrics;
import com.example.regionsync.model.enums.OperationType;
import com.example.regionsync.model.event.SyncEvent;
import com.example.regionsync.repository.CompanyRepository;
import com.example.regionsync.service.ConflictRecordService;
import com.example.regionsync.service.SyncApplyService;
import com.example.regionsync.service.SyncRejectionService;
import com.example.regionsync.strategy.ConflictStrategyFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SyncEventConsumerTest {

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

    private SyncEventConsumer consumer;
    private CdcEventParser cdcEventParser;

    @BeforeEach
    void setUp() {
        cdcEventParser = new CdcEventParser(new ObjectMapper());
        consumer = new SyncEventConsumer(
                cdcEventParser,
                syncProperties,
                syncTopicsProperties,
                eventDeduplicationService,
                globalLockService,
                entityMapperRegistry,
                companyRepository,
                syncApplyService,
                syncRejectionService,
                conflictRecordService,
                syncMetrics,
                conflictStrategyFactory
        );
    }

    @Test
    void shouldSkipEventFromOwnRegion() {
        // Event where __source_region matches the current region (loop prevention)
        String json = """
                {
                    "company_code": "ACME-001",
                    "name": "Acme",
                    "source_region": "EU",
                    "version": 0,
                    "__op": "c",
                    "__table": "companies",
                    "__source_ts_ms": 1700000000000,
                    "__source_region": "EU"
                }
                """;

        when(syncProperties.getCurrentRegion()).thenReturn("EU");

        consumer.consume(json, ack);

        verify(syncMetrics).incrementSkipped();
        verify(ack).acknowledge();
        // Should not attempt to apply the event
        verifyNoInteractions(syncApplyService);
    }

    @Test
    void shouldSkipBounceBackEvent() {
        // Simulates a bounce-back: NA creates a company, it syncs to EU,
        // EU Debezium captures it (__source_region = "EU"), but the payload's
        // source_region column is still "NA". NA consumer should skip this.
        String json = """
                {
                    "company_code": "ACME-001",
                    "name": "Acme",
                    "source_region": "NA",
                    "version": 0,
                    "__op": "c",
                    "__table": "companies",
                    "__source_ts_ms": 1700000000000,
                    "__source_region": "EU"
                }
                """;

        when(syncProperties.getCurrentRegion()).thenReturn("NA");

        consumer.consume(json, ack);

        verify(syncMetrics).incrementSkipped();
        verify(ack).acknowledge();
        verifyNoInteractions(syncApplyService);
    }

    @Test
    void shouldProcessEventFromRemoteRegion() {
        // A genuine event from EU that NA should process
        String json = """
                {
                    "company_code": "EU-BETA-001",
                    "name": "Beta GmbH",
                    "source_region": "EU",
                    "version": 0,
                    "__op": "c",
                    "__table": "companies",
                    "__source_ts_ms": 1700000000000,
                    "__source_region": "EU"
                }
                """;

        when(syncProperties.getCurrentRegion()).thenReturn("NA");
        when(eventDeduplicationService.isDuplicate(anyString())).thenReturn(false);
        when(entityMapperRegistry.findMapper("companies")).thenReturn(Optional.of(new CompanyMapper()));

        Map<String, SyncProperties.TableConfig> tables = new HashMap<>();
        SyncProperties.TableConfig config = new SyncProperties.TableConfig();
        config.setConflictStrategy("FIRST_WRITER_WINS");
        tables.put("companies", config);
        when(syncProperties.getTables()).thenReturn(tables);

        // Need to provide a strategy and mock the create flow
        var strategy = new com.example.regionsync.strategy.FirstWriterWinsStrategy();
        when(conflictStrategyFactory.getStrategy("FIRST_WRITER_WINS")).thenReturn(strategy);

        // Mock that no local entity exists
        when(companyRepository.findByCompanyCode("EU-BETA-001")).thenReturn(Optional.empty());

        // Mock the lock service to just execute the runnable
        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(1);
            action.run();
            return null;
        }).when(globalLockService).executeWithLock(anyString(), any(Runnable.class));

        // Mock the apply service
        var syncResult = com.example.regionsync.model.event.SyncResult.builder()
                .eventId("test")
                .success(true)
                .action("APPLIED")
                .reason("Created from remote event")
                .build();
        when(syncApplyService.applyCreate(any(), any())).thenReturn(syncResult);

        consumer.consume(json, ack);

        verify(syncApplyService).applyCreate(any(), any());
        verify(syncMetrics).incrementApplied();
        verify(ack).acknowledge();
    }
}
