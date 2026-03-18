package com.example.regionsync.consumer;

import com.example.regionsync.metrics.SyncMetrics;
import com.example.regionsync.model.entity.Company;
import com.example.regionsync.model.enums.SyncStatus;
import com.example.regionsync.model.event.SyncRejection;
import com.example.regionsync.repository.CompanyRepository;
import com.example.regionsync.service.ConflictRecordService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class RejectionEventConsumer {

    private final ConflictRecordService conflictRecordService;
    private final CompanyRepository companyRepository;
    private final SyncMetrics syncMetrics;
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

            // Mark the local entity as conflicted
            if ("companies".equals(rejection.getTableName()) && rejection.getBusinessKey() != null) {
                Optional<Company> companyOpt = companyRepository.findByCompanyCode(rejection.getBusinessKey());
                companyOpt.ifPresent(company -> {
                    company.setSyncStatus(SyncStatus.CONFLICT);
                    company.setSyncConflictDetail(rejection.getConflictDetail());
                    // Mark as synced-from-remote so the resulting CDC event is
                    // recognised as a sync echo and skipped by the remote consumer,
                    // preventing an infinite rejection → CDC → rejection loop.
                    company.setSyncedFromRemote(true);
                    companyRepository.save(company);
                    log.info("Marked company as CONFLICT: {}", rejection.getBusinessKey());
                });
            }

            // Update metrics
            syncMetrics.incrementConflicts();

            ack.acknowledge();
        } catch (Exception e) {
            log.error("Failed to process rejection message: {}", e.getMessage(), e);
            throw new RuntimeException("Rejection processing failed", e);
        }
    }
}
