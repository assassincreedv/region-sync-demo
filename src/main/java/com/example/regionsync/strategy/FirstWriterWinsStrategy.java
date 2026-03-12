package com.example.regionsync.strategy;

import com.example.regionsync.model.event.SyncEvent;
import org.springframework.stereotype.Component;

@Component
public class FirstWriterWinsStrategy implements ConflictStrategy {

    @Override
    public String getStrategyName() {
        return "FIRST_WRITER_WINS";
    }

    @Override
    public boolean shouldAcceptCreate(SyncEvent event, boolean localExists) {
        // Reject if a local record already exists (first writer wins)
        return !localExists;
    }

    @Override
    public boolean shouldAcceptUpdate(SyncEvent event, int localVersion) {
        // Accept if remote version is greater than or equal to local version
        Integer remoteVersion = event.getRemoteVersion();
        return remoteVersion != null && remoteVersion >= localVersion;
    }
}
