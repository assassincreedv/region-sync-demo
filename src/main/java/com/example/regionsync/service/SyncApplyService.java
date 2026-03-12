package com.example.regionsync.service;

import com.example.regionsync.config.SyncProperties;
import com.example.regionsync.mapper.EntityMapper;
import com.example.regionsync.model.base.SyncableEntity;
import com.example.regionsync.model.entity.Company;
import com.example.regionsync.model.event.SyncEvent;
import com.example.regionsync.model.event.SyncResult;
import com.example.regionsync.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class SyncApplyService {

    private final CompanyRepository companyRepository;
    private final SyncProperties syncProperties;

    @SuppressWarnings("unchecked")
    public SyncResult applyCreate(SyncEvent event, EntityMapper mapper) {
        try {
            SyncableEntity entity = mapper.fromPayload(event.getPayload());
            entity.setSourceRegion(event.getSourceRegion());
            entity.setSyncedFromRemote(true);

            if ("companies".equals(event.getTableName())) {
                companyRepository.save((Company) entity);
            }

            log.info("Applied CREATE for table={} businessKey={}", event.getTableName(), event.getBusinessKey());
            return SyncResult.builder()
                    .eventId(event.getEventId())
                    .success(true)
                    .action("APPLIED")
                    .reason("Created from remote event")
                    .build();
        } catch (Exception e) {
            log.error("Failed to apply CREATE for event {}: {}", event.getEventId(), e.getMessage());
            return SyncResult.builder()
                    .eventId(event.getEventId())
                    .success(false)
                    .action("FAILED")
                    .reason(e.getMessage())
                    .build();
        }
    }

    @SuppressWarnings("unchecked")
    public SyncResult applyUpdate(SyncEvent event, EntityMapper mapper) {
        try {
            if ("companies".equals(event.getTableName())) {
                Optional<Company> opt = companyRepository.findByCompanyCode(event.getBusinessKey());
                if (opt.isEmpty()) {
                    log.warn("No local entity found for UPDATE: table={} key={}", event.getTableName(), event.getBusinessKey());
                    return SyncResult.builder()
                            .eventId(event.getEventId())
                            .success(false)
                            .action("SKIPPED")
                            .reason("Local entity not found for update")
                            .build();
                }
                Company existing = opt.get();
                mapper.updateEntity(existing, event.getPayload());
                existing.setSyncedFromRemote(true);
                companyRepository.save(existing);
            }

            log.info("Applied UPDATE for table={} businessKey={}", event.getTableName(), event.getBusinessKey());
            return SyncResult.builder()
                    .eventId(event.getEventId())
                    .success(true)
                    .action("APPLIED")
                    .reason("Updated from remote event")
                    .build();
        } catch (Exception e) {
            log.error("Failed to apply UPDATE for event {}: {}", event.getEventId(), e.getMessage());
            return SyncResult.builder()
                    .eventId(event.getEventId())
                    .success(false)
                    .action("FAILED")
                    .reason(e.getMessage())
                    .build();
        }
    }

    public SyncResult applyDelete(SyncEvent event, EntityMapper mapper) {
        try {
            if ("companies".equals(event.getTableName())) {
                Optional<Company> opt = companyRepository.findByCompanyCode(event.getBusinessKey());
                opt.ifPresent(company -> companyRepository.delete(company));
            }

            log.info("Applied DELETE for table={} businessKey={}", event.getTableName(), event.getBusinessKey());
            return SyncResult.builder()
                    .eventId(event.getEventId())
                    .success(true)
                    .action("APPLIED")
                    .reason("Deleted from remote event")
                    .build();
        } catch (Exception e) {
            log.error("Failed to apply DELETE for event {}: {}", event.getEventId(), e.getMessage());
            return SyncResult.builder()
                    .eventId(event.getEventId())
                    .success(false)
                    .action("FAILED")
                    .reason(e.getMessage())
                    .build();
        }
    }
}
