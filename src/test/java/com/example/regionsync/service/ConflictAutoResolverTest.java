package com.example.regionsync.service;

import com.example.regionsync.config.SyncProperties;
import com.example.regionsync.model.entity.Company;
import com.example.regionsync.model.entity.SyncConflictLog;
import com.example.regionsync.model.enums.ConflictResolutionAction;
import com.example.regionsync.model.enums.SyncStatus;
import com.example.regionsync.repository.CompanyRepository;
import com.example.regionsync.repository.SyncConflictLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConflictAutoResolverTest {

    @Mock
    private CompanyRepository companyRepository;

    @Mock
    private SyncConflictLogRepository syncConflictLogRepository;

    @Mock
    private SyncProperties syncProperties;

    @Mock
    private ConflictRecordService conflictRecordService;

    @InjectMocks
    private ConflictAutoResolver conflictAutoResolver;

    private SyncProperties.ConflictConfig conflictConfig;

    @BeforeEach
    void setUp() {
        conflictConfig = new SyncProperties.ConflictConfig();
        conflictConfig.setAutoResolveEnabled(true);
        lenient().when(syncProperties.getConflict()).thenReturn(conflictConfig);
    }

    @Test
    void autoResolve_naYieldsToEu_deletesLocalDuplicate() {
        // Scenario: NA receives a conflict where remoteRegion=EU, localRegion=NA
        // Since EU < NA lexicographically, EU wins, so NA should yield and delete
        when(syncProperties.getCurrentRegion()).thenReturn("NA");

        SyncConflictLog conflict = SyncConflictLog.builder()
                .id(1L)
                .businessKey("CONFLICT-CO")
                .localRegion("NA")
                .remoteRegion("EU")   // EU is the remote region that rejected
                .rejectionReason("DUPLICATE_ENTITY")
                .resolved(false)
                .createdAt(LocalDateTime.now().minusMinutes(10))
                .build();

        when(syncConflictLogRepository.findUnresolvedByReasonBefore(eq("DUPLICATE_ENTITY"), any()))
                .thenReturn(List.of(conflict));

        Company company = Company.builder().companyCode("CONFLICT-CO").build();
        when(companyRepository.findByCompanyCode("CONFLICT-CO")).thenReturn(Optional.of(company));

        conflictAutoResolver.autoResolve();

        // NA should yield: mark synced_from_remote=true, flush, then delete
        assertTrue(company.isSyncedFromRemote(),
                "Entity must be marked synced_from_remote before deletion to prevent CDC echo");
        var inOrder = inOrder(companyRepository);
        inOrder.verify(companyRepository).saveAndFlush(company);
        inOrder.verify(companyRepository).delete(company);
        assertTrue(conflict.isResolved());
        assertEquals(ConflictResolutionAction.AUTO_YIELD.name(), conflict.getResolutionAction());
    }

    @Test
    void autoResolve_euWinsOverNa_clearsConflictStatusAndResyncs() {
        // Scenario: EU receives a conflict where remoteRegion=NA, localRegion=EU
        // Since EU < NA lexicographically, EU wins, so EU should keep its data,
        // clear the CONFLICT status, and set syncedFromRemote=false so that
        // the CDC event re-syncs the entity to the losing region (NA).
        when(syncProperties.getCurrentRegion()).thenReturn("EU");

        SyncConflictLog conflict = SyncConflictLog.builder()
                .id(2L)
                .businessKey("CONFLICT-CO")
                .localRegion("EU")
                .remoteRegion("NA")   // NA is the remote region that rejected
                .rejectionReason("DUPLICATE_ENTITY")
                .resolved(false)
                .createdAt(LocalDateTime.now().minusMinutes(10))
                .build();

        when(syncConflictLogRepository.findUnresolvedByReasonBefore(eq("DUPLICATE_ENTITY"), any()))
                .thenReturn(List.of(conflict));

        Company company = Company.builder().companyCode("CONFLICT-CO").build();
        company.setSyncStatus(SyncStatus.CONFLICT);
        company.setSyncConflictDetail("Local entity already exists");
        company.setSyncedFromRemote(true);
        when(companyRepository.findByCompanyCode("CONFLICT-CO")).thenReturn(Optional.of(company));

        conflictAutoResolver.autoResolve();

        // EU should win: no deletion, but entity CONFLICT status must be cleared
        verify(companyRepository, never()).delete(any());
        verify(companyRepository).save(company);
        assertEquals(SyncStatus.NORMAL, company.getSyncStatus(),
                "Winning entity must have syncStatus cleared to NORMAL");
        assertNull(company.getSyncConflictDetail(),
                "Winning entity must have syncConflictDetail cleared");
        assertFalse(company.isSyncedFromRemote(),
                "Winning entity must set syncedFromRemote=false to trigger CDC re-sync");
        assertEquals("EU", company.getSourceRegion(),
                "Winning entity must have sourceRegion set to the current (winning) region");
        assertTrue(conflict.isResolved());
        assertEquals(ConflictResolutionAction.AUTO_WIN.name(), conflict.getResolutionAction());
    }

    @Test
    void autoResolve_neverYieldsToSelf() {
        // This test verifies the defensive guard: if remoteRegion == currentRegion,
        // the resolver must NOT delete the local entity and must skip the conflict.
        when(syncProperties.getCurrentRegion()).thenReturn("EU");

        SyncConflictLog conflict = SyncConflictLog.builder()
                .id(3L)
                .businessKey("CONFLICT-CO")
                .localRegion("EU")
                .remoteRegion("EU")   // Same region — should not happen after fix
                .rejectionReason("DUPLICATE_ENTITY")
                .resolved(false)
                .createdAt(LocalDateTime.now().minusMinutes(10))
                .build();

        when(syncConflictLogRepository.findUnresolvedByReasonBefore(eq("DUPLICATE_ENTITY"), any()))
                .thenReturn(List.of(conflict));

        conflictAutoResolver.autoResolve();

        // When regions are equal, the resolver should skip — no deletion, no resolution
        verify(companyRepository, never()).findByCompanyCode(any());
        verify(companyRepository, never()).delete(any());
        assertEquals(false, conflict.isResolved(),
                "Conflict should remain unresolved when regions are equal");
    }
}
