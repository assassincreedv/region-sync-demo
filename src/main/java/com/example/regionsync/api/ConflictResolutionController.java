package com.example.regionsync.api;

import com.example.regionsync.model.entity.SyncConflictLog;
import com.example.regionsync.model.enums.ConflictResolutionAction;
import com.example.regionsync.service.ConflictRecordService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sync/conflicts")
@RequiredArgsConstructor
public class ConflictResolutionController {

    private final ConflictRecordService conflictRecordService;

    @GetMapping("/unresolved")
    public ResponseEntity<List<SyncConflictLog>> getUnresolved() {
        return ResponseEntity.ok(conflictRecordService.findUnresolved());
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<SyncConflictLog> resolve(
            @PathVariable Long id,
            @RequestParam ConflictResolutionAction action) {
        SyncConflictLog resolved = conflictRecordService.markResolved(id, action);
        return ResponseEntity.ok(resolved);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        List<SyncConflictLog> unresolved = conflictRecordService.findUnresolved();
        return ResponseEntity.ok(Map.of(
                "unresolvedCount", unresolved.size(),
                "conflicts", unresolved
        ));
    }
}
