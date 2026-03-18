package com.example.regionsync.service;

import com.example.regionsync.api.DuplicateEntityException;
import com.example.regionsync.config.SyncProperties;
import com.example.regionsync.dedup.EntityDeduplicationService;
import com.example.regionsync.model.entity.Company;
import com.example.regionsync.model.enums.ConflictResolutionAction;
import com.example.regionsync.repository.CompanyRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final SyncProperties syncProperties;
    private final ConflictRecordService conflictRecordService;
    private final EntityDeduplicationService entityDeduplicationService;

    public Company create(Company company) {
        if (company.getCompanyCode() != null) {
            // 1. Check local database for existing entity
            Optional<Company> existingOpt = companyRepository.findByCompanyCode(company.getCompanyCode());
            if (existingOpt.isPresent()) {
                Company existing = existingOpt.get();
                String currentRegion = syncProperties.getCurrentRegion();
                String existingRegion = existing.getSourceRegion();

                String detail;
                if (existingRegion != null && !existingRegion.equals(currentRegion)) {
                    detail = "Company with companyCode '" + company.getCompanyCode()
                            + "' has already been created in " + existingRegion + " region";
                } else {
                    detail = "Company with companyCode '" + company.getCompanyCode() + "' already exists";
                }

                conflictRecordService.recordResolvedConflict(
                        "companies",
                        company.getCompanyCode(),
                        currentRegion,
                        existingRegion != null ? existingRegion : currentRegion,
                        "DUPLICATE_ENTITY",
                        detail,
                        ConflictResolutionAction.AUTO_WIN);
                conflictRecordService.recordEventDirect(
                        "companies",
                        "CREATE",
                        company.getCompanyCode(),
                        currentRegion,
                        "REJECTED",
                        detail);

                throw new DuplicateEntityException(detail);
            }

            // 2. Cross-region deduplication via Redis SETNX.
            //    tryRegister uses SETNX semantics: only the first region to
            //    register a given companyCode wins; subsequent attempts from
            //    other regions are rejected immediately.
            String currentRegion = syncProperties.getCurrentRegion();
            boolean registered = entityDeduplicationService.tryRegister(
                    "companies", company.getCompanyCode(), currentRegion);

            if (!registered) {
                String registeredRegion = entityDeduplicationService.getRegisteredRegion(
                        "companies", company.getCompanyCode());
                // If the same region registered (e.g. concurrent local requests),
                // this will be caught by a DB unique constraint or the local
                // check above on retry.  When a different region registered,
                // return a clear cross-region duplicate error.
                String detail = "Company with companyCode '" + company.getCompanyCode()
                        + "' has already been created in "
                        + (registeredRegion != null ? registeredRegion : "another") + " region";
                log.warn("Cross-region duplicate detected: companyCode={} registeredBy={}",
                        company.getCompanyCode(), registeredRegion);

                conflictRecordService.recordResolvedConflict(
                        "companies",
                        company.getCompanyCode(),
                        currentRegion,
                        registeredRegion != null ? registeredRegion : currentRegion,
                        "DUPLICATE_ENTITY",
                        detail,
                        ConflictResolutionAction.AUTO_WIN);
                conflictRecordService.recordEventDirect(
                        "companies",
                        "CREATE",
                        company.getCompanyCode(),
                        currentRegion,
                        "REJECTED",
                        detail);

                throw new DuplicateEntityException(detail);
            }
        }

        company.setId(UUID.randomUUID().toString());
        company.setSourceRegion(syncProperties.getCurrentRegion());
        company.setSyncedFromRemote(false);
        try {
            Company saved = companyRepository.save(company);
            // Confirm the registration so the key lives for 7 days (beyond
            // the initial 30-min registration TTL) to guard late duplicates.
            if (company.getCompanyCode() != null) {
                entityDeduplicationService.confirm("companies", company.getCompanyCode());
            }
            return saved;
        } catch (Exception e) {
            // If the DB save fails, release the Redis registration so a
            // retry or another region can claim the business key.
            if (company.getCompanyCode() != null) {
                entityDeduplicationService.release("companies", company.getCompanyCode());
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<Company> findAll() {
        return companyRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<Company> findById(String id) {
        return companyRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<Company> findByCompanyCode(String code) {
        return companyRepository.findByCompanyCode(code);
    }

    public Company update(String id, Company updates) {
        Company existing = companyRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Company not found with id: " + id));
        // Guard against a request that supplies a companyCode belonging to another entity
        if (updates.getCompanyCode() != null
                && !updates.getCompanyCode().equals(existing.getCompanyCode())) {
            companyRepository.findByCompanyCode(updates.getCompanyCode()).ifPresent(other -> {
                if (!other.getId().equals(id)) {
                    throw new DuplicateEntityException(
                            "Company with companyCode '" + updates.getCompanyCode() + "' already exists");
                }
            });
        }
        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getAddress() != null) existing.setAddress(updates.getAddress());
        if (updates.getContactEmail() != null) existing.setContactEmail(updates.getContactEmail());
        if (updates.getStatus() != null) existing.setStatus(updates.getStatus());
        // company_code is the business key and must not be changed after creation
        // Reset syncedFromRemote so this local change is picked up by remote
        // consumers. Without this, an entity previously synced from remote would
        // keep synced_from_remote=true, and the CDC event from this local update
        // would be incorrectly skipped by the remote region.
        existing.setSyncedFromRemote(false);
        existing.setSourceRegion(syncProperties.getCurrentRegion());
        return companyRepository.save(existing);
    }

    public void delete(String id) {
        companyRepository.deleteById(id);
    }
}
