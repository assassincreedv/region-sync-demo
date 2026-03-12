package com.example.regionsync.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class SyncMetrics {

    private final MeterRegistry meterRegistry;

    private Counter syncEventsReceived;
    private Counter syncEventsApplied;
    private Counter syncEventsRejected;
    private Counter syncEventsSkipped;
    private Counter syncEventsFailed;
    private Counter syncConflictsTotal;
    private Counter syncConflictsAutoResolved;
    private Counter syncDeadLetterTotal;
    private Timer syncEventProcessingLatency;

    @PostConstruct
    public void init() {
        syncEventsReceived = Counter.builder("sync.events.received")
                .description("Total sync events received")
                .register(meterRegistry);

        syncEventsApplied = Counter.builder("sync.events.applied")
                .description("Total sync events applied")
                .register(meterRegistry);

        syncEventsRejected = Counter.builder("sync.events.rejected")
                .description("Total sync events rejected")
                .register(meterRegistry);

        syncEventsSkipped = Counter.builder("sync.events.skipped")
                .description("Total sync events skipped")
                .register(meterRegistry);

        syncEventsFailed = Counter.builder("sync.events.failed")
                .description("Total sync events failed")
                .register(meterRegistry);

        syncConflictsTotal = Counter.builder("sync.conflicts.total")
                .description("Total sync conflicts detected")
                .register(meterRegistry);

        syncConflictsAutoResolved = Counter.builder("sync.conflicts.auto_resolved")
                .description("Total sync conflicts auto-resolved")
                .register(meterRegistry);

        syncDeadLetterTotal = Counter.builder("sync.dead_letter.total")
                .description("Total messages sent to dead letter topic")
                .register(meterRegistry);

        syncEventProcessingLatency = Timer.builder("sync.event.processing.latency")
                .description("Sync event processing latency")
                .register(meterRegistry);
    }

    public void incrementReceived() {
        syncEventsReceived.increment();
    }

    public void incrementApplied() {
        syncEventsApplied.increment();
    }

    public void incrementRejected() {
        syncEventsRejected.increment();
    }

    public void incrementSkipped() {
        syncEventsSkipped.increment();
    }

    public void incrementFailed() {
        syncEventsFailed.increment();
    }

    public void incrementConflicts() {
        syncConflictsTotal.increment();
    }

    public void incrementAutoResolved() {
        syncConflictsAutoResolved.increment();
    }

    public void incrementDeadLetter() {
        syncDeadLetterTotal.increment();
    }

    public void recordLatency(long ms) {
        syncEventProcessingLatency.record(ms, TimeUnit.MILLISECONDS);
    }
}
