package com.example.regionsync.mapper;

import com.example.regionsync.model.entity.Company;
import com.example.regionsync.model.enums.SyncStatus;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class CompanyMapper implements EntityMapper<Company> {

    @Override
    public String getTableName() {
        return "companies";
    }

    @Override
    public Class<Company> getEntityClass() {
        return Company.class;
    }

    @Override
    public Company fromPayload(Map<String, Object> payload) {
        Company company = new Company();
        company.setCompanyCode(getStringValue(payload, "company_code"));
        company.setName(getStringValue(payload, "name"));
        company.setAddress(getStringValue(payload, "address"));
        company.setContactEmail(getStringValue(payload, "contact_email"));
        String status = getStringValue(payload, "status");
        company.setStatus(status != null ? status : "ACTIVE");
        return company;
    }

    @Override
    public void updateEntity(Company existing, Map<String, Object> payload) {
        String name = getStringValue(payload, "name");
        if (name != null) existing.setName(name);

        String address = getStringValue(payload, "address");
        existing.setAddress(address);

        String contactEmail = getStringValue(payload, "contact_email");
        existing.setContactEmail(contactEmail);

        String status = getStringValue(payload, "status");
        if (status != null) existing.setStatus(status);

        Object syncStatusObj = payload.get("sync_status");
        if (syncStatusObj != null) {
            try {
                existing.setSyncStatus(SyncStatus.valueOf(syncStatusObj.toString()));
            } catch (IllegalArgumentException ignored) {
                // keep existing sync status if invalid
            }
        }
    }

    @Override
    public String extractBusinessKey(Map<String, Object> payload) {
        Object key = payload.get("company_code");
        return key != null ? key.toString() : null;
    }

    private String getStringValue(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value != null ? value.toString() : null;
    }
}
