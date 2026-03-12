package com.example.regionsync.service;

import com.example.regionsync.config.SyncProperties;
import com.example.regionsync.model.entity.Company;
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

    public Company create(Company company) {
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
        if (updates.getName() != null) existing.setName(updates.getName());
        if (updates.getAddress() != null) existing.setAddress(updates.getAddress());
        if (updates.getContactEmail() != null) existing.setContactEmail(updates.getContactEmail());
        if (updates.getStatus() != null) existing.setStatus(updates.getStatus());
        // company_code is the business key and must not be changed after creation
        return companyRepository.save(existing);
    }

    public void delete(String id) {
        companyRepository.deleteById(id);
    }
}
