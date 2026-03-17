package com.example.regionsync.service;

import com.example.regionsync.config.SyncProperties;
import com.example.regionsync.model.entity.SyncConflictLog;
import com.example.regionsync.model.enums.ConflictResolutionAction;
import com.example.regionsync.repository.CompanyRepository;
import com.example.regionsync.repository.SyncConflictLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConflictAutoResolver {

    private static final String DUPLICATE_ENTITY_REASON = "DUPLICATE_ENTITY";

    private final CompanyRepository companyRepository;
    private final SyncConflictLogRepository syncConflictLogRepository;
    private final SyncProperties syncProperties;
    private final ConflictRecordService conflictRecordService;

    @Scheduled(fixedDelay = 60000)   // Polls every 60 s; only processes conflicts older than 5 min (see cutoff below)
    @Transactional
    public void autoResolve() {
        if (!syncProperties.getConflict().isAutoResolveEnabled()) {
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
        List<SyncConflictLog> conflicts = syncConflictLogRepository
                .findUnresolvedByReasonBefore(DUPLICATE_ENTITY_REASON, cutoff);

        if (conflicts.isEmpty()) {
            return;
        }

        log.info("Auto-resolving {} DUPLICATE_ENTITY conflicts", conflicts.size());
        String currentRegion = syncProperties.getCurrentRegion();

        for (SyncConflictLog conflict : conflicts) {
            try {
                resolveConflict(conflict, currentRegion);
            } catch (Exception e) {
                log.error("Failed to auto-resolve conflict id={}: {}", conflict.getId(), e.getMessage());
            }
        }
    }

    private void resolveConflict(SyncConflictLog conflict, String currentRegion) {
        String remoteRegion = conflict.getRemoteRegion();

        if (currentRegion.equals(remoteRegion)) {
            log.warn("Auto-resolve: skipping conflict with remoteRegion equal to currentRegion={} for businessKey={}",
                    currentRegion, conflict.getBusinessKey());
            return;
        }

        // Region priority: determined lexicographically (EU < NA), so EU always wins.
        // This ensures a deterministic, configuration-free tie-breaker consistent across
        // all nodes — no external coordination required.
        boolean currentRegionWins = currentRegion.compareTo(remoteRegion) < 0;

        if (!currentRegionWins) {
            // Current region yields — delete the local duplicate
            log.info("Auto-resolve: current region {} yields to {} for businessKey={}",
                    currentRegion, remoteRegion, conflict.getBusinessKey());
            companyRepository.findByCompanyCode(conflict.getBusinessKey())
                    .ifPresent(company -> {
                        // Mark the entity so that the resulting CDC events (the
                        // update + the rewritten delete) carry synced_from_remote=true.
                        // The remote region's consumer will then skip them, preventing
                        // the winning side from also deleting its copy.
                        company.setSyncedFromRemote(true);
                        companyRepository.saveAndFlush(company);
                        companyRepository.delete(company);
                        log.info("Deleted local duplicate company: {}", conflict.getBusinessKey());
                    });
            conflict.setResolved(true);
            conflict.setResolvedAt(LocalDateTime.now());
            conflict.setResolutionAction(ConflictResolutionAction.AUTO_YIELD.name());
        } else {
            log.info("Auto-resolve: current region {} wins over {} for businessKey={}",
                    currentRegion, remoteRegion, conflict.getBusinessKey());
            conflict.setResolved(true);
            conflict.setResolvedAt(LocalDateTime.now());
            conflict.setResolutionAction(ConflictResolutionAction.AUTO_WIN.name());
        }

        syncConflictLogRepository.save(conflict);
    }
}
