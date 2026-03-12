package com.example.regionsync.strategy;

import com.example.regionsync.model.event.SyncEvent;

public interface ConflictStrategy {
    String getStrategyName();
    boolean shouldAcceptCreate(SyncEvent event, boolean localExists);
    boolean shouldAcceptUpdate(SyncEvent event, int localVersion);
}
