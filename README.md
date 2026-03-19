# Region Sync Demo

A production-grade **CDC (Change Data Capture) + Kafka bidirectional cross-region sync** demo built with Spring Boot 3.3.x and Java 21. Demonstrates how to keep two independent regional MySQL databases in sync using Debezium, a single shared Apache Kafka cluster, and Redis-based distributed locking — with full conflict detection and resolution.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                              REGION: NA (North America)                          │
│                                                                                  │
│  ┌─────────────┐    CDC    ┌──────────────┐                                     │
│  │  MySQL NA   │──────────▶│  Debezium    │─── na.cdc.app_na.* ──────┐          │
│  │  (app_na)   │           │  Connector   │                          │          │
│  └──────┬──────┘           └──────────────┘                          │          │
│  ┌──────▼──────┐                                                     │          │
│  │  App NA     │◀── eu.cdc.app_eu.* ──────────────────────┐         │          │
│  │ :8080       │                                           │         │          │
│  │             │─── sync.rejection.eu ────────────┐       │         │          │
│  └─────────────┘◀── sync.rejection.na ──────┐     │       │         │          │
└──────────────────────────────────────────────│─────│───────│─────────│──────────┘
                                               │     │       │         │
                                          ┌────▼─────▼───────▼─────────▼────┐
                                          │       Apache Kafka (shared)      │
                                          │  • na.cdc.app_na.companies      │
                                          │  • eu.cdc.app_eu.companies      │
                                          │  • sync.rejection.na / .eu      │
                                          └────┬─────┬───────┬─────────┬────┘
                                               │     │       │         │
┌──────────────────────────────────────────────│─────│───────│─────────│──────────┐
│                              REGION: EU      │     │       │         │  (Europe)│
│                                              │     │       │         │          │
│  ┌─────────────┐    CDC    ┌──────────────┐  │     │       │         │          │
│  │  MySQL EU   │──────────▶│  Debezium    │──│─────│───────│── eu.cdc.app_eu.*  │
│  │  (app_eu)   │           │  Connector   │  │     │       │                    │
│  └──────┬──────┘           └──────────────┘  │     │       │                    │
│  ┌──────▼──────┐                             │     │       │                    │
│  │  App EU     │◀── na.cdc.app_na.* ─────────┘     │       │                    │
│  │ :8081       │                                    │       │                    │
│  │             │─── sync.rejection.na ──────────────┘       │                    │
│  └─────────────┘◀── sync.rejection.eu ──────────────────────┘                    │
└──────────────────────────────────────────────────────────────────────────────────┘

                    ┌──────────────────────┐
                    │   Redis (Shared)     │
                    │  • Event dedup       │
                    │  • Entity dedup      │
                    │  • Distributed lock  │
                    └──────────────────────┘
```

### Sync Flow

1. A record is written to MySQL (NA or EU)
2. Debezium CDC connector captures the change and publishes to Kafka (`na.cdc.*` / `eu.cdc.*`)
3. The remote app consumes the topic:
   - **Loop prevention**: skip if `__source_region` == own region
   - **Idempotency**: skip if `eventId` already in Redis (7-day TTL)
   - **Distributed lock**: acquire per-entity lock before CREATE
   - **Conflict strategy**: `FIRST_WRITER_WINS` — reject CREATE if local exists, reject UPDATE if remote version < local
4. On rejection: send `SyncRejection` to rejection outbox topic
5. The originating region consumes the rejection, marks entity as `CONFLICT`
6. `ConflictAutoResolver` (scheduled) auto-resolves DUPLICATE_ENTITY conflicts by region priority (EU wins)

---

## Tech Stack

| Component         | Technology                     |
|-------------------|--------------------------------|
| Framework         | Spring Boot 3.3.5, Java 21     |
| CDC               | Debezium 3.0 (MySQL connector) |
| Messaging         | Apache Kafka (Confluent 7.5.0) |
| Database          | MySQL 8.0 (per region)         |
| Distributed Cache | Redis 7, Redisson 3.27.2       |
| Schema Migration  | Flyway                         |
| Metrics           | Micrometer + Prometheus        |
| Build             | Maven 3.x                      |

---

## Quick Start

### Prerequisites
- Docker & Docker Compose
- Java 21 + Maven (for local build)

### 1. Build the application JAR

```bash
mvn clean package -DskipTests
```

### 2. Start all infrastructure + apps

```bash
cd docker
docker-compose up -d
```

Wait ~60 seconds for all services to be healthy.

### 3. Register Debezium CDC connectors

```bash
# Register NA connector
curl -X POST http://localhost:8083/connectors \
  --header "Content-Type: application/json" \
  -data-binary '@docker/debezium/register-na-connector.json'

# Register EU connector
curl -X POST http://localhost:8083/connectors \
  -header "Content-Type: application/json" \
  -data-binary '@docker/debezium/register-eu-connector.json'

# Verify connectors are running
curl 'http://localhost:8083/connectors?expand=status' | jq .
```

### 4. Verify connector status

```bash
curl 'http://localhost:8083/connectors/mysql-na-connector/status' | jq .
curl 'http://localhost:8083/connectors/mysql-eu-connector/status' | jq .
```

---

## API Usage

### Company CRUD (NA region — port 8080)

```bash
# Create a company in NA
curl -X POST http://localhost:8080/api/companies \
  -H "Content-Type: application/json" \
  -d '{
    "companyCode": "ACME-001",
    "name": "Acme Corporation",
    "address": "123 Main St, New York, NY",
    "contactEmail": "info@acme.com",
    "status": "ACTIVE"
  }'

# List all companies in NA
curl http://localhost:8080/api/companies | jq .

# Get company by ID
curl http://localhost:8080/api/companies/{id} | jq .

# Update a company
curl -X PUT http://localhost:8080/api/companies/{id} \
  -H "Content-Type: application/json" \
  -d '{"name": "Acme Corp Updated", "status": "ACTIVE"}'

# Delete a company
curl -X DELETE http://localhost:8080/api/companies/{id}
```

### Company CRUD (EU region — port 8081)

```bash
# Create a company in EU
curl -X POST http://localhost:8081/api/companies \
  -H "Content-Type: application/json" \
  -d '{
    "companyCode": "EU-BETA-001",
    "name": "Beta GmbH",
    "address": "Unter den Linden 1, Berlin",
    "contactEmail": "info@beta.de",
    "status": "ACTIVE"
  }'

# List all companies in EU (should include NA companies after sync)
curl http://localhost:8081/api/companies | jq .
```

### Conflict Management

```bash
# List unresolved conflicts (NA)
curl http://localhost:8080/api/sync/conflicts/unresolved | jq .

# Get conflict stats
curl http://localhost:8080/api/sync/conflicts/stats | jq .

# Manually resolve a conflict (accept local copy)
curl -X POST "http://localhost:8080/api/sync/conflicts/1/resolve?action=MANUAL_ACCEPT_LOCAL"

# Manually resolve a conflict (accept remote copy)
curl -X POST "http://localhost:8080/api/sync/conflicts/1/resolve?action=MANUAL_ACCEPT_REMOTE"
```

---

## Conflict Simulation Scenario

### Step 1: Create the same company code in both regions simultaneously

```bash
# NA region
curl -X POST http://localhost:8080/api/companies \
  -H "Content-Type: application/json" \
  -d '{"companyCode":"CONFLICT-CO","name":"Conflict Co (NA)","status":"ACTIVE"}'

# EU region (same company code!)
curl -X POST http://localhost:8081/api/companies \
  -H "Content-Type: application/json" \
  -d '{"companyCode":"CONFLICT-CO","name":"Conflict Co (EU)","status":"ACTIVE"}'
```

### Step 2: Observe conflict detection

After a few seconds, Debezium will capture both changes. When each region tries to apply the other's CREATE:
- The region that already has `CONFLICT-CO` will reject with `DUPLICATE_ENTITY`
- A `SyncRejection` is sent to the rejection topic
- The originating region marks the entity `sync_status = CONFLICT`

```bash
# Check conflicts in NA
curl http://localhost:8080/api/sync/conflicts/unresolved | jq .

# Check conflicts in EU
curl http://localhost:8081/api/sync/conflicts/unresolved | jq .
```

### Step 3: Auto-resolution

After 5 minutes (configurable), `ConflictAutoResolver` runs:
- **EU wins** (EU < NA lexicographically = REGION_PRIORITY)
- NA deletes its local duplicate
- Conflict is marked `AUTO_YIELD` in NA, `AUTO_WIN` in EU

---

## API Documentation

### Companies API

| Method | Endpoint              | Description              | Response        |
|--------|-----------------------|--------------------------|-----------------|
| POST   | `/api/companies`      | Create a company         | 201 Company     |
| GET    | `/api/companies`      | List all companies       | 200 Company[]   |
| GET    | `/api/companies/{id}` | Get company by ID        | 200/404 Company |
| PUT    | `/api/companies/{id}` | Update a company         | 200 Company     |
| DELETE | `/api/companies/{id}` | Delete a company         | 204             |

### Conflict Resolution API

| Method | Endpoint                          | Description                | Response              |
|--------|-----------------------------------|----------------------------|-----------------------|
| GET    | `/api/sync/conflicts/unresolved`  | List unresolved conflicts  | 200 SyncConflictLog[] |
| POST   | `/api/sync/conflicts/{id}/resolve`| Resolve a conflict         | 200 SyncConflictLog   |
| GET    | `/api/sync/conflicts/stats`       | Conflict statistics        | 200 Map               |

**Resolve action values:** `MANUAL_ACCEPT_LOCAL`, `MANUAL_ACCEPT_REMOTE`, `AUTO_YIELD`, `AUTO_WIN`, `PENDING`

---

## Configuration Reference

### `sync.*` properties

| Property                              | Description                           | Example           |
|---------------------------------------|---------------------------------------|-------------------|
| `sync.current-region`                 | This instance's region identifier     | `NA`              |
| `sync.remote-region`                  | The peer region                       | `EU`              |
| `sync.database-name`                  | MySQL database name                   | `app_na`          |
| `sync.tables.{name}.conflict-strategy`| Conflict resolution strategy          | `FIRST_WRITER_WINS`|
| `sync.conflict.auto-resolve-enabled`  | Enable scheduled auto-resolver        | `true`            |
| `sync.conflict.tie-breaker-strategy`  | How to break ties                     | `REGION_PRIORITY` |

### `sync-topics.*` properties

| Property                    | Description                    | NA Value                    |
|-----------------------------|--------------------------------|-----------------------------|
| `sync-topics.remote-cdc-topics` | Topic to consume from remote   | `eu.cdc.app_eu.companies`   |
| `sync-topics.rejection-inbox`   | Rejections sent to this region | `sync.rejection.na`         |
| `sync-topics.rejection-outbox`  | Rejections sent to remote      | `sync.rejection.eu`         |
| `sync-topics.dead-letter-topic` | DLT for failed processing      | `sync.dlt.na`               |

---

## Monitoring

### Prometheus Metrics

| Metric                           | Description                          |
|----------------------------------|--------------------------------------|
| `sync_events_received_total`     | Total CDC events received            |
| `sync_events_applied_total`      | Successfully applied events          |
| `sync_events_rejected_total`     | Rejected events (conflicts)          |
| `sync_events_skipped_total`      | Skipped (duplicates, loop prevention)|
| `sync_events_failed_total`       | Processing failures                  |
| `sync_conflicts_total`           | Total conflicts detected             |
| `sync_conflicts_auto_resolved_total` | Auto-resolved conflicts          |
| `sync_dead_letter_total`         | Messages in dead letter topic        |
| `sync_event_processing_latency`  | End-to-end processing latency        |

### Endpoints

```bash
# Health check
curl http://localhost:8080/actuator/health

# Prometheus metrics
curl http://localhost:8080/actuator/prometheus

# All metrics
curl http://localhost:8080/actuator/metrics
```

---

## Project Structure

```
src/main/java/com/example/regionsync/
├── RegionSyncApplication.java          # Main entry point
├── api/
│   ├── CompanyController.java          # REST CRUD for companies
│   └── ConflictResolutionController.java
├── cdc/
│   └── CdcEventParser.java             # Debezium JSON parser
├── config/
│   ├── KafkaConsumerConfig.java        # Kafka consumer with DLT + retry
│   ├── KafkaProducerConfig.java        # Idempotent Kafka producer
│   ├── SyncProperties.java             # @ConfigurationProperties: sync.*
│   └── SyncTopicsProperties.java       # @ConfigurationProperties: sync-topics.*
├── consumer/
│   ├── SyncEventConsumer.java          # Main CDC event processor
│   ├── RejectionEventConsumer.java     # Processes incoming rejections
│   └── DeadLetterConsumer.java         # Dead letter topic handler
├── dedup/
│   ├── EventDeduplicationService.java  # Redis event-level dedup (7-day TTL)
│   ├── EntityDeduplicationService.java # Redis entity registration
│   └── GlobalLockService.java          # Redisson distributed lock
├── mapper/
│   ├── EntityMapper.java               # Generic mapper interface
│   ├── CompanyMapper.java              # Company entity mapper
│   └── EntityMapperRegistry.java       # Mapper discovery by table name
├── metrics/
│   └── SyncMetrics.java               # Micrometer counters + timers
├── model/
│   ├── base/SyncableEntity.java        # Base @MappedSuperclass
│   ├── entity/
│   │   ├── Company.java
│   │   ├── SyncConflictLog.java
│   │   └── SyncEventLog.java
│   ├── enums/
│   │   ├── ConflictResolutionAction.java
│   │   ├── OperationType.java
│   │   ├── RejectionReason.java
│   │   └── SyncStatus.java
│   └── event/
│       ├── SyncEvent.java
│       ├── SyncRejection.java
│       └── SyncResult.java
├── repository/
│   ├── CompanyRepository.java
│   ├── SyncConflictLogRepository.java
│   └── SyncEventLogRepository.java
├── service/
│   ├── CompanyService.java
│   ├── ConflictAutoResolver.java       # Scheduled auto-resolver
│   ├── ConflictRecordService.java      # Conflict + event log persistence
│   ├── SyncApplyService.java           # Apply CREATE/UPDATE/DELETE
│   └── SyncRejectionService.java       # Send rejections to Kafka
└── strategy/
    ├── ConflictStrategy.java           # Strategy interface
    ├── ConflictStrategyFactory.java    # Strategy registry
    └── FirstWriterWinsStrategy.java    # Default strategy
```

---

## License

MIT
