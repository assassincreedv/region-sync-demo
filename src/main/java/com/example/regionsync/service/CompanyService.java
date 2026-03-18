package com.example.regionsync.service;

import com.example.regionsync.api.DuplicateEntityException;
import com.example.regionsync.config.SyncProperties;
import com.example.regionsync.model.entity.Company;
import com.example.regionsync.model.enums.ConflictResolutionAction;
import com.example.regionsync.repository.CompanyRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
@RequiredArgsConstructor
public class CompanyService {

    private final CompanyRepository companyRepository;
    private final SyncProperties syncProperties;
    private final ConflictRecordService conflictRecordService;

    public Company create(Company company) {
        if (company.getCompanyCode() != null) {
            Optional<Company> existingOpt = companyRepository.findByCompanyCode(company.getCompanyCode());
            if (existingOpt.isPresent()) {
                Company existing = existingOpt.get();
                String currentRegion = syncProperties.getCurrentRegion();
                String existingRegion = existing.getSourceRegion();

                // Determine the appropriate error message based on whether the
                // existing entity came from a remote region (synced) or was
                // created locally.
                String detail;
                if (existingRegion != null && !existingRegion.equals(currentRegion)) {
                    detail = "Company with companyCode '" + company.getCompanyCode()
                            + "' has already been created in " + existingRegion + " region";
                } else {
                    detail = "Company with companyCode '" + company.getCompanyCode() + "' already exists";
                }

                // Log to sync_conflict_log and sync_event_log so that the
                // conflict is visible in the metadata tables.
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
        }
        company.setId(UUID.randomUUID().toString());
        company.setSourceRegion(syncProperties.getCurrentRegion());
        company.setSyncedFromRemote(false);
        return companyRepository.save(company);
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
