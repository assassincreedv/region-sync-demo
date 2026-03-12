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
import com.example.regionsync.model.enums.RejectionReason;
import com.example.regionsync.model.event.SyncEvent;
import com.example.regionsync.model.event.SyncRejection;
import com.example.regionsync.model.event.SyncResult;
import com.example.regionsync.repository.CompanyRepository;
import com.example.regionsync.service.ConflictRecordService;
import com.example.regionsync.service.SyncApplyService;
import com.example.regionsync.service.SyncRejectionService;
import com.example.regionsync.strategy.ConflictStrategy;
import com.example.regionsync.strategy.ConflictStrategyFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
@RequiredArgsConstructor
public class SyncEventConsumer {

    private final CdcEventParser cdcEventParser;
    private final SyncProperties syncProperties;
    private final SyncTopicsProperties syncTopicsProperties;
    private final EventDeduplicationService eventDeduplicationService;
    private final GlobalLockService globalLockService;
    private final EntityMapperRegistry entityMapperRegistry;
    private final CompanyRepository companyRepository;
    private final SyncApplyService syncApplyService;
    private final SyncRejectionService syncRejectionService;
    private final ConflictRecordService conflictRecordService;
    private final SyncMetrics syncMetrics;
    private final ConflictStrategyFactory conflictStrategyFactory;

    @KafkaListener(
            topics = "#{@syncTopicsProperties.remoteCdcTopics}",
            groupId = "sync-consumer-${sync.current-region}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(String message, Acknowledgment ack) {
        long startMs = System.currentTimeMillis();
        SyncEvent event = null;
        try {
            syncMetrics.incrementReceived();

            // Determine table from topic (simplified: extract from topic name)
            String tableName = extractTableName(message);

            // 1. Parse event
            Optional<SyncEvent> parsed = cdcEventParser.parse(message, tableName);
            if (parsed.isEmpty()) {
                log.warn("Could not parse CDC event, skipping. Message preview: {}",
                        message.length() > 200 ? message.substring(0, 200) : message);
                syncMetrics.incrementSkipped();
                ack.acknowledge();
                return;
            }
            event = parsed.get();

            // 2. Loop prevention: skip if event originated from this region
            if (syncProperties.getCurrentRegion().equals(event.getSourceRegion())) {
                log.debug("Loop prevention: skipping event from own region={} eventId={}",
                        event.getSourceRegion(), event.getEventId());
                syncMetrics.incrementSkipped();
                ack.acknowledge();
                return;
            }

            // 3. Idempotency: skip if already processed
            if (eventDeduplicationService.isDuplicate(event.getEventId())) {
                log.debug("Duplicate event, skipping: eventId={}", event.getEventId());
                syncMetrics.incrementSkipped();
                ack.acknowledge();
                return;
            }

            // 4. Find mapper for table
            Optional<EntityMapper<?>> mapperOpt = entityMapperRegistry.findMapper(event.getTableName());
            if (mapperOpt.isEmpty()) {
                log.warn("No mapper found for table={}, rejecting event={}", event.getTableName(), event.getEventId());
                sendRejection(event, RejectionReason.TABLE_NOT_SUPPORTED, "No mapper for table: " + event.getTableName(), null);
                syncMetrics.incrementRejected();
                ack.acknowledge();
                return;
            }

            @SuppressWarnings("unchecked")
            EntityMapper mapper = mapperOpt.get();

            // 5. Determine conflict strategy
            String strategyName = resolveStrategyName(event.getTableName());
            ConflictStrategy strategy = conflictStrategyFactory.getStrategy(strategyName);

            SyncResult result;

            // 6. Route by operation type
            switch (event.getOperationType()) {
                case CREATE -> result = handleCreate(event, mapper, strategy);
                case UPDATE -> result = handleUpdate(event, mapper, strategy);
                case DELETE -> {
                    result = syncApplyService.applyDelete(event, mapper);
                }
                default -> {
                    log.debug("Skipping READ operation for eventId={}", event.getEventId());
                    syncMetrics.incrementSkipped();
                    ack.acknowledge();
                    return;
                }
            }

            // 7. Mark as processed
            eventDeduplicationService.markProcessed(event.getEventId());

            // 8. Record event log
            conflictRecordService.recordEvent(event, result.getAction(), result.getReason());

            // 9. Update metrics
            switch (result.getAction()) {
                case "APPLIED" -> syncMetrics.incrementApplied();
                case "REJECTED" -> syncMetrics.incrementRejected();
                case "SKIPPED" -> syncMetrics.incrementSkipped();
                case "FAILED" -> syncMetrics.incrementFailed();
                default -> { /* no-op */ }
            }

            ack.acknowledge();
            syncMetrics.recordLatency(System.currentTimeMillis() - startMs);

        } catch (Exception e) {
            log.error("Error processing sync event: {}", e.getMessage(), e);
            syncMetrics.incrementFailed();
            if (event != null) {
                conflictRecordService.recordEvent(event, "FAILED", e.getMessage());
            }
            // Rethrow to trigger retry / DLT
            throw new RuntimeException("Sync event processing failed", e);
        }
    }

    @SuppressWarnings("unchecked")
    private SyncResult handleCreate(SyncEvent event, EntityMapper mapper, ConflictStrategy strategy) {
        AtomicReference<SyncResult> resultRef = new AtomicReference<>();
        globalLockService.executeWithLock(
                event.getTableName() + ":" + event.getBusinessKey(),
                () -> {
                    boolean localExists = companyRepository.findByCompanyCode(event.getBusinessKey()).isPresent();
                    if (!strategy.shouldAcceptCreate(event, localExists)) {
                        log.warn("CREATE rejected: localExists={} for businessKey={}", localExists, event.getBusinessKey());
                        sendRejection(event, RejectionReason.DUPLICATE_ENTITY,
                                "Local entity already exists", null);
                        syncMetrics.incrementConflicts();
                        resultRef.set(SyncResult.builder()
                                .eventId(event.getEventId())
                                .success(false)
                                .action("REJECTED")
                                .reason("DUPLICATE_ENTITY: local entity already exists")
                                .build());
                    } else {
                        resultRef.set(syncApplyService.applyCreate(event, mapper));
                    }
                });
        return resultRef.get();
    }

    @SuppressWarnings("unchecked")
    private SyncResult handleUpdate(SyncEvent event, EntityMapper mapper, ConflictStrategy strategy) {
        if (!"companies".equals(event.getTableName())) {
            return SyncResult.builder()
                    .eventId(event.getEventId())
                    .success(false)
                    .action("SKIPPED")
                    .reason("Table not supported for update")
                    .build();
        }
        Optional<Company> localOpt = companyRepository.findByCompanyCode(event.getBusinessKey());
        if (localOpt.isEmpty()) {
            // Entity doesn't exist locally — apply as create
            return syncApplyService.applyCreate(event, mapper);
        }
        int localVersion = localOpt.get().getVersion();
        if (!strategy.shouldAcceptUpdate(event, localVersion)) {
            log.warn("UPDATE rejected: remoteVersion={} localVersion={} for businessKey={}",
                    event.getRemoteVersion(), localVersion, event.getBusinessKey());
            sendRejection(event, RejectionReason.VERSION_CONFLICT,
                    "Remote version " + event.getRemoteVersion() + " < local version " + localVersion,
                    localVersion);
            syncMetrics.incrementConflicts();
            return SyncResult.builder()
                    .eventId(event.getEventId())
                    .success(false)
                    .action("REJECTED")
                    .reason("VERSION_CONFLICT: remote version is stale")
                    .build();
        }
        return syncApplyService.applyUpdate(event, mapper);
    }

    private void sendRejection(SyncEvent event, RejectionReason reason, String detail, Integer localVersion) {
        SyncRejection rejection = SyncRejection.builder()
                .rejectionId(UUID.randomUUID().toString())
                .originalEventId(event.getEventId())
                .tableName(event.getTableName())
                .businessKey(event.getBusinessKey())
                .rejectionReason(reason)
                .sourceRegion(syncProperties.getCurrentRegion())
                .targetRegion(event.getSourceRegion())
                .localVersion(localVersion)
                .remoteVersion(event.getRemoteVersion())
                .conflictDetail(detail)
                .timestamp(LocalDateTime.now())
                .build();
        syncRejectionService.sendRejection(rejection);
    }

    private String extractTableName(String message) {
        // Lightweight extraction of __table before full JSON parse.
        // Debezium's ExtractNewRecordState adds __table to the flattened value.
        // Using ObjectMapper here would require an extra full parse just for routing;
        // the fast string scan is sufficient for this demo. If message format changes,
        // replace with objectMapper.readTree(message).path("__table").asText("companies").
        try {
            int idx = message.indexOf("\"__table\"");
            if (idx >= 0) {
                int start = message.indexOf("\"", idx + 9) + 1;
                int end = message.indexOf("\"", start);
                if (start > 0 && end > start) {
                    return message.substring(start, end);
                }
            }
        } catch (Exception ignored) {
            // fall through to default
        }
        return "companies"; // default for this demo
    }

    private String resolveStrategyName(String tableName) {
        if (syncProperties.getTables() != null && syncProperties.getTables().containsKey(tableName)) {
            String strategy = syncProperties.getTables().get(tableName).getConflictStrategy();
            if (strategy != null) return strategy;
        }
        return "FIRST_WRITER_WINS";
    }
}
