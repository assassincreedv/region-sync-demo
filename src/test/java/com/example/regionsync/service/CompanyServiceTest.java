package com.example.regionsync.service;

import com.example.regionsync.api.DuplicateEntityException;
import com.example.regionsync.config.SyncProperties;
import com.example.regionsync.model.entity.Company;
import com.example.regionsync.repository.CompanyRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyServiceTest {

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private SyncProperties syncProperties;

    @InjectMocks
    private CompanyService companyService;

    @BeforeEach
    void setUp() {
        lenient().when(syncProperties.getCurrentRegion()).thenReturn("NA");
    }

    @Test
    void create_shouldSucceedWhenCompanyCodeIsNew() {
        Company company = Company.builder().companyCode("NEW-CO").name("New Co").build();
        when(companyRepository.findByCompanyCode("NEW-CO")).thenReturn(Optional.empty());
        when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

        Company result = companyService.create(company);

        assertNotNull(result.getId());
        assertEquals("NA", result.getSourceRegion());
        assertFalse(result.isSyncedFromRemote());
        verify(companyRepository).save(company);
    }

    @Test
    void create_shouldThrowDuplicateEntityExceptionWhenCompanyCodeExists() {
        Company existing = Company.builder().companyCode("DUP-CO").name("Existing").build();
        existing.setId("existing-id");
        when(companyRepository.findByCompanyCode("DUP-CO")).thenReturn(Optional.of(existing));

        Company newCompany = Company.builder().companyCode("DUP-CO").name("Duplicate").build();

        DuplicateEntityException ex = assertThrows(DuplicateEntityException.class,
                () -> companyService.create(newCompany));
        assertTrue(ex.getMessage().contains("DUP-CO"));
        verify(companyRepository, never()).save(any());
    }

    @Test
    void create_shouldSucceedWhenCompanyCodeIsNull() {
        Company company = Company.builder().name("No Code Co").build();
        when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

        Company result = companyService.create(company);

        assertNotNull(result.getId());
        verify(companyRepository).save(company);
    }

    @Test
    void update_shouldThrowDuplicateExceptionWhenCompanyCodeBelongsToAnotherEntity() {
        Company existing = Company.builder().companyCode("CO-A").name("Company A").build();
        existing.setId("id-a");
        when(companyRepository.findById("id-a")).thenReturn(Optional.of(existing));

        Company other = Company.builder().companyCode("CO-B").name("Company B").build();
        other.setId("id-b");
        when(companyRepository.findByCompanyCode("CO-B")).thenReturn(Optional.of(other));

        Company updates = Company.builder().companyCode("CO-B").name("Updated Name").build();

        DuplicateEntityException ex = assertThrows(DuplicateEntityException.class,
                () -> companyService.update("id-a", updates));
        assertTrue(ex.getMessage().contains("CO-B"));
    }

    @Test
    void update_shouldSucceedWhenCompanyCodeIsUnchanged() {
        Company existing = Company.builder().companyCode("CO-A").name("Company A").build();
        existing.setId("id-a");
        when(companyRepository.findById("id-a")).thenReturn(Optional.of(existing));
        when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

        Company updates = Company.builder().companyCode("CO-A").name("Updated Name").build();
        Company result = companyService.update("id-a", updates);

        assertEquals("Updated Name", result.getName());
        verify(companyRepository).save(existing);
    }

    @Test
    void update_shouldThrowEntityNotFoundWhenIdDoesNotExist() {
        when(companyRepository.findById("missing-id")).thenReturn(Optional.empty());

        Company updates = Company.builder().name("Anything").build();

        assertThrows(EntityNotFoundException.class,
                () -> companyService.update("missing-id", updates));
    }
}
