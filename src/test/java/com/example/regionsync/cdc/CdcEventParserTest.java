package com.example.regionsync.cdc;

import com.example.regionsync.model.enums.OperationType;
import com.example.regionsync.model.event.SyncEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CdcEventParserTest {

    private CdcEventParser parser;

    @BeforeEach
    void setUp() {
        parser = new CdcEventParser(new ObjectMapper());
    }

    @Test
    void shouldParseSourceRegionFromUnwrappedEvent() {
        // Simulates a Debezium event after the correct transform order: unwrap, then addSourceRegion.
        // The __source_region field is present in the flattened record.
        String json = """
                {
                    "id": "abc-123",
                    "company_code": "ACME-001",
                    "name": "Acme Corporation",
                    "address": "123 Main St",
                    "contact_email": "info@acme.com",
                    "status": "ACTIVE",
                    "source_region": "NA",
                    "synced_from_remote": 0,
                    "version": 1,
                    "__op": "c",
                    "__table": "companies",
                    "__source_ts_ms": 1700000000000,
                    "__source_region": "NA"
                }
                """;

        Optional<SyncEvent> result = parser.parse(json, "companies");

        assertThat(result).isPresent();
        SyncEvent event = result.get();
        assertThat(event.getSourceRegion()).isEqualTo("NA");
        assertThat(event.getOperationType()).isEqualTo(OperationType.CREATE);
        assertThat(event.getBusinessKey()).isEqualTo("ACME-001");
        assertThat(event.getRemoteVersion()).isEqualTo(1);
        assertThat(event.getSourceTimestampMs()).isEqualTo(1700000000000L);
    }

    @Test
    void shouldReturnNullSourceRegionWhenFieldMissing() {
        // Simulates the broken transform order (addSourceRegion before unwrap) where
        // __source_region is lost during ExtractNewRecordState.
        String json = """
                {
                    "id": "abc-123",
                    "company_code": "ACME-001",
                    "name": "Acme Corporation",
                    "source_region": "NA",
                    "version": 0,
                    "__op": "c",
                    "__table": "companies",
                    "__source_ts_ms": 1700000000000
                }
                """;

        Optional<SyncEvent> result = parser.parse(json, "companies");

        assertThat(result).isPresent();
        assertThat(result.get().getSourceRegion()).isNull();
    }

    @Test
    void shouldParseUpdateOperation() {
        String json = """
                {
                    "company_code": "ACME-001",
                    "name": "Acme Updated",
                    "version": 2,
                    "__op": "u",
                    "__table": "companies",
                    "__source_ts_ms": 1700000001000,
                    "__source_region": "EU"
                }
                """;

        Optional<SyncEvent> result = parser.parse(json, "companies");

        assertThat(result).isPresent();
        SyncEvent event = result.get();
        assertThat(event.getOperationType()).isEqualTo(OperationType.UPDATE);
        assertThat(event.getSourceRegion()).isEqualTo("EU");
        assertThat(event.getRemoteVersion()).isEqualTo(2);
    }

    @Test
    void shouldReturnEmptyForUnknownOperation() {
        String json = """
                {
                    "company_code": "ACME-001",
                    "__op": "x",
                    "__source_region": "NA"
                }
                """;

        Optional<SyncEvent> result = parser.parse(json, "companies");

        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyForInvalidJson() {
        Optional<SyncEvent> result = parser.parse("not-json", "companies");

        assertThat(result).isEmpty();
    }
}
