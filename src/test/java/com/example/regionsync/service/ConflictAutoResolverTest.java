package com.example.regionsync.service;

import com.example.regionsync.config.SyncProperties;
import com.example.regionsync.model.entity.Company;
import com.example.regionsync.model.entity.SyncConflictLog;
import com.example.regionsync.model.enums.ConflictResolutionAction;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

        // NA should yield: delete the local duplicate
        verify(companyRepository).delete(company);
        assertTrue(conflict.isResolved());
        assertEquals(ConflictResolutionAction.AUTO_YIELD.name(), conflict.getResolutionAction());
    }

    @Test
    void autoResolve_euWinsOverNa_keepsLocalEntity() {
        // Scenario: EU receives a conflict where remoteRegion=NA, localRegion=EU
        // Since EU < NA lexicographically, EU wins, so EU should keep its data
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

        conflictAutoResolver.autoResolve();

        // EU should win: no deletion
        verify(companyRepository, never()).delete(any());
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
