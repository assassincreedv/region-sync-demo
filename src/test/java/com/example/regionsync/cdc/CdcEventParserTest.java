package com.example.regionsync.cdc;

import com.example.regionsync.model.enums.OperationType;
import com.example.regionsync.model.event.SyncEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CdcEventParserTest {

    private final CdcEventParser parser = new CdcEventParser(new ObjectMapper());

    @Test
    void parse_prefersDebeziumSourceRegionOverDbColumn() {
        // When the Debezium InsertField transform adds __source_region and the
        // DB column source_region has a different value (e.g. after sync), the
        // parser must use __source_region because it reliably identifies which
        // region's database the CDC event was captured from.
        String json = """
                {
                  "company_code": "TEST-CO",
                  "name": "Test",
                  "source_region": "EU",
                  "__source_region": "NA",
                  "synced_from_remote": 0,
                  "__op": "u",
                  "__table": "companies",
                  "__source_ts_ms": 1710792000000,
                  "version": 2
                }
                """;

        Optional<SyncEvent> result = parser.parse(json, "companies");

        assertTrue(result.isPresent());
        assertEquals("NA", result.get().getSourceRegion(),
                "Must use __source_region (Debezium static field) over source_region (DB column)");
    }

    @Test
    void parse_fallsBackToDbColumnWhenDebeziumFieldMissing() {
        // If __source_region is not in the payload (e.g. older Debezium config),
        // fall back to the source_region database column.
        String json = """
                {
                  "company_code": "TEST-CO",
                  "name": "Test",
                  "source_region": "EU",
                  "synced_from_remote": 0,
                  "__op": "c",
                  "__table": "companies",
                  "__source_ts_ms": 1710792000000,
                  "version": 0
                }
                """;

        Optional<SyncEvent> result = parser.parse(json, "companies");

        assertTrue(result.isPresent());
        assertEquals("EU", result.get().getSourceRegion(),
                "Must fall back to source_region when __source_region is missing");
    }

    @Test
    void parse_handlesSchemaPayloadWrapper() {
        // Kafka Connect JsonConverter with schemas.enable=true wraps the value
        // in a {"schema":{...},"payload":{...}} structure.
        String json = """
                {
                  "schema": {
                    "type": "struct",
                    "fields": [
                      {"type":"string","optional":false,"field":"id"},
                      {"type":"string","optional":true,"field":"__table"}
                    ]
                  },
                  "payload": {
                    "id": "abc-123",
                    "company_code": "WRAP-CO",
                    "name": "Wrapped",
                    "source_region": "NA",
                    "__source_region": "NA",
                    "synced_from_remote": 0,
                    "__op": "c",
                    "__table": "companies",
                    "__source_ts_ms": 1710792000000,
                    "version": 0
                  }
                }
                """;

        Optional<SyncEvent> result = parser.parse(json, "fallback");

        assertTrue(result.isPresent());
        SyncEvent event = result.get();
        assertEquals("companies", event.getTableName());
        assertEquals("WRAP-CO", event.getBusinessKey());
        assertEquals("NA", event.getSourceRegion());
        assertEquals(OperationType.CREATE, event.getOperationType());
    }

    @Test
    void parse_extractsCorrectOperationTypes() {
        for (var entry : java.util.Map.of("c", OperationType.CREATE,
                "u", OperationType.UPDATE, "d", OperationType.DELETE).entrySet()) {
            String json = String.format("""
                    {"company_code":"OP-CO","name":"Op","source_region":"NA",
                     "__source_region":"NA","synced_from_remote":0,"__op":"%s",
                     "__table":"companies","__source_ts_ms":100,"version":0}
                    """, entry.getKey());

            Optional<SyncEvent> result = parser.parse(json, "companies");
            assertTrue(result.isPresent());
            assertEquals(entry.getValue(), result.get().getOperationType());
        }
    }

    @Test
    void parse_returnsEmptyForUnknownOp() {
        String json = """
                {"company_code":"X","name":"X","source_region":"NA",
                 "__op":"x","__table":"companies","__source_ts_ms":100,"version":0}
                """;

        Optional<SyncEvent> result = parser.parse(json, "companies");
        assertTrue(result.isEmpty());
    }
}
