package com.example.regionsync.service;

import com.example.regionsync.api.DuplicateEntityException;
import com.example.regionsync.config.SyncProperties;
import com.example.regionsync.dedup.EntityDeduplicationService;
import com.example.regionsync.model.entity.Company;
import com.example.regionsync.model.enums.ConflictResolutionAction;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompanyServiceTest {

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private SyncProperties syncProperties;

    @Mock
    private ConflictRecordService conflictRecordService;

    @Mock
    private EntityDeduplicationService entityDeduplicationService;

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
        when(entityDeduplicationService.tryRegister("companies", "NEW-CO", "NA")).thenReturn(true);
        when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

        Company result = companyService.create(company);

        assertNotNull(result.getId());
        assertEquals("NA", result.getSourceRegion());
        assertFalse(result.isSyncedFromRemote());
        verify(companyRepository).save(company);
        verify(entityDeduplicationService).confirm("companies", "NEW-CO");
    }

    @Test
    void create_shouldThrowDuplicateEntityExceptionWhenCompanyCodeExists() {
        Company existing = Company.builder().companyCode("DUP-CO").name("Existing").build();
        existing.setId("existing-id");
        existing.setSourceRegion("NA");
        when(companyRepository.findByCompanyCode("DUP-CO")).thenReturn(Optional.of(existing));

        Company newCompany = Company.builder().companyCode("DUP-CO").name("Duplicate").build();

        DuplicateEntityException ex = assertThrows(DuplicateEntityException.class,
                () -> companyService.create(newCompany));
        assertTrue(ex.getMessage().contains("DUP-CO"));
        verify(companyRepository, never()).save(any());
        // Conflict and event should be recorded
        verify(conflictRecordService).recordResolvedConflict(
                eq("companies"), eq("DUP-CO"), eq("NA"), eq("NA"),
                eq("DUPLICATE_ENTITY"), any(), eq(ConflictResolutionAction.AUTO_WIN));
        verify(conflictRecordService).recordEventDirect(
                eq("companies"), eq("CREATE"), eq("DUP-CO"), eq("NA"),
                eq("REJECTED"), any());
    }

    @Test
    void create_shouldIncludeRemoteRegionInErrorWhenDuplicateFromRemote() {
        // Scenario: EU synced a company to NA; user on NA tries to create
        // the same companyCode.  The error should mention the EU region.
        Company existing = Company.builder().companyCode("REMOTE-CO").name("Remote Co").build();
        existing.setId("existing-id");
        existing.setSourceRegion("EU");
        when(companyRepository.findByCompanyCode("REMOTE-CO")).thenReturn(Optional.of(existing));

        Company newCompany = Company.builder().companyCode("REMOTE-CO").name("New Remote Co").build();

        DuplicateEntityException ex = assertThrows(DuplicateEntityException.class,
                () -> companyService.create(newCompany));
        assertTrue(ex.getMessage().contains("EU region"),
                "Error must mention the remote region; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("REMOTE-CO"));
        verify(companyRepository, never()).save(any());
        // Conflict and event should be recorded with EU as remoteRegion
        verify(conflictRecordService).recordResolvedConflict(
                eq("companies"), eq("REMOTE-CO"), eq("NA"), eq("EU"),
                eq("DUPLICATE_ENTITY"), any(), eq(ConflictResolutionAction.AUTO_WIN));
        verify(conflictRecordService).recordEventDirect(
                eq("companies"), eq("CREATE"), eq("REMOTE-CO"), eq("NA"),
                eq("REJECTED"), any());
    }

    @Test
    void create_shouldSucceedWhenCompanyCodeIsNull() {
        Company company = Company.builder().name("No Code Co").build();
        when(companyRepository.save(any(Company.class))).thenAnswer(inv -> inv.getArgument(0));

        Company result = companyService.create(company);

        assertNotNull(result.getId());
        verify(companyRepository).save(company);
        // Redis registration should not be attempted when companyCode is null
        verify(entityDeduplicationService, never()).tryRegister(any(), any(), any());
    }

    @Test
    void create_shouldRejectWhenAnotherRegionAlreadyRegistered() {
        // Scenario: EU already registered CONFLICT-CO in Redis; NA tries to create.
        Company company = Company.builder().companyCode("CONFLICT-CO").name("Conflict Co (NA)").build();
        when(companyRepository.findByCompanyCode("CONFLICT-CO")).thenReturn(Optional.empty());
        when(entityDeduplicationService.tryRegister("companies", "CONFLICT-CO", "NA")).thenReturn(false);
        when(entityDeduplicationService.getRegisteredRegion("companies", "CONFLICT-CO")).thenReturn("EU");

        DuplicateEntityException ex = assertThrows(DuplicateEntityException.class,
                () -> companyService.create(company));
        assertTrue(ex.getMessage().contains("EU region"),
                "Error must mention the remote region; got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("CONFLICT-CO"));
        verify(companyRepository, never()).save(any());
        verify(conflictRecordService).recordResolvedConflict(
                eq("companies"), eq("CONFLICT-CO"), eq("NA"), eq("EU"),
                eq("DUPLICATE_ENTITY"), any(), eq(ConflictResolutionAction.AUTO_WIN));
        verify(conflictRecordService).recordEventDirect(
                eq("companies"), eq("CREATE"), eq("CONFLICT-CO"), eq("NA"),
                eq("REJECTED"), any());
    }

    @Test
    void create_shouldReleaseRegistrationWhenDbSaveFails() {
        Company company = Company.builder().companyCode("FAIL-CO").name("Fail Co").build();
        when(companyRepository.findByCompanyCode("FAIL-CO")).thenReturn(Optional.empty());
        when(entityDeduplicationService.tryRegister("companies", "FAIL-CO", "NA")).thenReturn(true);
        when(companyRepository.save(any(Company.class)))
                .thenThrow(new RuntimeException("DB write error"));

        assertThrows(RuntimeException.class, () -> companyService.create(company));
        verify(entityDeduplicationService).release("companies", "FAIL-CO");
        verify(entityDeduplicationService, never()).confirm(any(), any());
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
