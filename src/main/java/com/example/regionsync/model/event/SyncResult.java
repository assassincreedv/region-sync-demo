package com.example.regionsync.model.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncResult {

    private String eventId;
    private boolean success;
    private String action;
    private String reason;
}
