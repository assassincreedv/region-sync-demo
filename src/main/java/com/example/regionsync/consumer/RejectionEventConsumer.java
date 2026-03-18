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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class RejectionEventConsumer {

    private final ConflictRecordService conflictRecordService;
    private final CompanyRepository companyRepository;
    private final SyncConflictLogRepository syncConflictLogRepository;
    private final SyncMetrics syncMetrics;
    private final SyncProperties syncProperties;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = "#{@syncTopicsProperties.rejectionInbox}",
            groupId = "rejection-consumer-${sync.current-region}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(String message, Acknowledgment ack) {
        try {
            SyncRejection rejection = objectMapper.readValue(message, SyncRejection.class);

            log.warn("Received rejection: rejectionId={} reason={} table={} businessKey={}",
                    rejection.getRejectionId(), rejection.getRejectionReason(),
                    rejection.getTableName(), rejection.getBusinessKey());

            // Record the conflict
            conflictRecordService.recordConflict(rejection);

            // Auto-resolve DUPLICATE_ENTITY conflicts immediately instead of
            // waiting for the scheduled ConflictAutoResolver
            if (RejectionReason.DUPLICATE_ENTITY == rejection.getRejectionReason()
                    && "companies".equals(rejection.getTableName())
                    && rejection.getBusinessKey() != null) {
                autoResolveImmediately(rejection);
            } else if ("companies".equals(rejection.getTableName())
                    && rejection.getBusinessKey() != null) {
                // For non-duplicate rejections, just mark the entity as CONFLICT
                markEntityConflict(rejection);
            }

            // Update metrics
            syncMetrics.incrementConflicts();

            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process rejection message: {}", e.getMessage(), e);
            throw new RuntimeException("Rejection processing failed", e);
        }
    }

    /**
     * Immediately resolves a DUPLICATE_ENTITY conflict using lexicographic
     * region comparison (same logic as {@code ConflictAutoResolver}).
     * The region with the smaller name wins (e.g. EU &lt; NA → EU wins).
     */
    private void autoResolveImmediately(SyncRejection rejection) {
        String currentRegion = syncProperties.getCurrentRegion();
        // sourceRegion is the region that sent the rejection (remote)
        String remoteRegion = rejection.getSourceRegion();

        if (currentRegion.equals(remoteRegion)) {
            log.warn("Skipping auto-resolve: currentRegion equals remoteRegion={}", currentRegion);
            markEntityConflict(rejection);
            return;
        }

        // Deterministic tie-breaker: smaller region name wins (EU < NA)
        boolean currentRegionWins = currentRegion.compareTo(remoteRegion) < 0;

        if (!currentRegionWins) {
            // Current region YIELDS — delete the local duplicate
            log.info("Immediate auto-resolve: region {} yields to {} for businessKey={}",
                    currentRegion, remoteRegion, rejection.getBusinessKey());
            companyRepository.findByCompanyCode(rejection.getBusinessKey())
                    .ifPresent(company -> {
                        company.setSyncedFromRemote(true);
                        companyRepository.saveAndFlush(company);
                        companyRepository.delete(company);
                        log.info("Deleted local duplicate company: {}", rejection.getBusinessKey());
                    });
            markConflictResolved(rejection, ConflictResolutionAction.AUTO_YIELD);
        } else {
            // Current region WINS — clear the CONFLICT status
            log.info("Immediate auto-resolve: region {} wins over {} for businessKey={}",
                    currentRegion, remoteRegion, rejection.getBusinessKey());
            companyRepository.findByCompanyCode(rejection.getBusinessKey())
                    .ifPresent(company -> {
                        company.setSyncStatus(SyncStatus.NORMAL);
                        company.setSyncConflictDetail(null);
                        company.setSyncedFromRemote(false);
                        companyRepository.save(company);
                        log.info("Cleared CONFLICT status on winning company: {}", rejection.getBusinessKey());
                    });
            markConflictResolved(rejection, ConflictResolutionAction.AUTO_WIN);
        }
    }

    private void markEntityConflict(SyncRejection rejection) {
        Optional<Company> companyOpt = companyRepository.findByCompanyCode(rejection.getBusinessKey());
        companyOpt.ifPresent(company -> {
            company.setSyncStatus(SyncStatus.CONFLICT);
            company.setSyncConflictDetail(rejection.getConflictDetail());
            company.setSyncedFromRemote(true);
            companyRepository.save(company);
            log.info("Marked company as CONFLICT: {}", rejection.getBusinessKey());
        });
    }

    private void markConflictResolved(SyncRejection rejection, ConflictResolutionAction action) {
        syncConflictLogRepository.findByResolvedFalse().stream()
                .filter(c -> rejection.getBusinessKey().equals(c.getBusinessKey())
                        && "DUPLICATE_ENTITY".equals(c.getRejectionReason()))
                .forEach(conflict -> {
                    conflict.setResolved(true);
                    conflict.setResolvedAt(LocalDateTime.now());
                    conflict.setResolutionAction(action.name());
                    syncConflictLogRepository.save(conflict);
                });
    }
}
