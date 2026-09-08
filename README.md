# Psystem Scheduler Agent

Production-ready Spring Boot agent that runs on a customer/local PC, scans a configured
folder on a cron schedule, selects the single largest ZIP/RAR file, and uploads it directly
to S3 via a pre-signed URL. Package base: `com.psystem`.

---

## 1. Recommended versions

| Component     | Version |
|---------------|---------|
| Java          | 17 (LTS) |
| Spring Boot   | 3.3.4 |
| Build tool    | Maven |
| Ledger        | Plain JSON file (local only, no DB/JDBC) |

Java 17 + Spring Boot 3.x was chosen because it's the current LTS combination, supports
records (used for `WorkflowResult`), and `RestClient` (Spring's modern synchronous HTTP
client, GA since Boot 3.2) is used instead of the older `RestTemplate`.

---

## 2. Architecture diagram

```text
Customer / Local PC
        |
        | Spring Boot Scheduler Agent (this project)
        |
        | 1. @Scheduled cron trigger (psystem.scheduler.cron)
        v
Scan Local Folder  (FileScannerService)
        |
        | Filter .zip/.rar, select largest (single file)
        v
Stability Check    (FileStabilityService)
        |
        | Confirm file is not still being written
        v
Idempotency Check  (IdempotencyService + local JSON ledger)
        |
        | Skip if fingerprint already SUCCESS
        v
Collect Machine Metadata (MachineMetadataService)
        |
        v
Request Pre-Signed S3 URL (PresignedUrlClient)
        |
        | POST machineId, location, destinationPath, file info
        v
Backend API / Python Lambda
        |
        | Returns pre-signed PUT URL + object key
        v
Customer PC
        |
        | Streamed HTTP PUT (S3UploadService, fixed-length streaming,
        | never loads the whole file into memory)
        v
AWS S3 Bucket
        |
        | ObjectCreated event (object key + metadata carried on the object)
        v
Python Lambda
        |
        | Process / validate ZIP or RAR (OUT OF SCOPE for this Java agent)
        v
S3 / Database / Destination
```

## 3. Component diagram (package responsibilities)

```text
com.psystem
├── PsystemApplication          entry point, @EnableScheduling, @EnableRetry
├── config
│   ├── AgentProperties          typed binding of every psystem.* setting (no hard-coding)
│   └── AppConfig                RestClient bean, agentProperties bean alias for SpEL
├── scheduler
│   ├── BackupScheduler          cron entry point, overlap guard, error classification
│   ├── SchedulerLockService     in-process lock preventing concurrent executions
│   └── GracefulShutdownHandler  waits for in-flight upload on ContextClosedEvent
├── batch
│   └── BackupWorkflow           orchestrates the 6 logical steps (see section 11)
├── controller
│   └── AdminController          localhost-only manual trigger / status endpoint
├── service
│   ├── file
│   │   ├── FileScannerService    directory scan + largest-file selection
│   │   └── FileStabilityService  "is this file still being written?" check
│   ├── metadata
│   │   └── MachineMetadataService  Machine ID / Location / Destination Path resolution
│   ├── presignedurl
│   │   └── PresignedUrlClient    REST client for the pre-signed URL backend, with retry
│   ├── upload
│   │   └── S3UploadService       streamed HTTP PUT to S3, retry, progress logging
│   └── idempotency
│       └── IdempotencyService    fingerprinting + local upload ledger
├── model
│   ├── request / response        API DTOs
│   └── domain                    CandidateFile, MachineMetadata, UploadRecord
├── exception                     one exception type per failure category (section 13)
├── repository
│   └── UploadLedgerStore         Plain JSON-file store over the local ledger (no JDBC)
└── util                          reserved for shared helpers (currently empty - see note below)
```

> **Note on `util`**: no shared string/IO helper classes were needed once retry logic moved
> to Spring Retry annotations and logging stayed inline; the package is kept as a placeholder
> per the requested structure rather than populated with speculative code.

## 4. Sequence diagram (happy path)

```text
BackupScheduler -> BackupWorkflow.run(executionId)
BackupWorkflow -> FileScannerService.findLargest()          : CandidateFile
BackupWorkflow -> MachineMetadataService.resolve()           : MachineMetadata
BackupWorkflow -> IdempotencyService.fingerprint()/isAlreadyUploaded()
BackupWorkflow -> FileStabilityService.verifyStable(file)
BackupWorkflow -> IdempotencyService.markInProgress()         : UploadRecord
BackupWorkflow -> PresignedUrlClient.requestPresignedUrl()    : PresignedUrlResponse
BackupWorkflow -> S3UploadService.upload(file, response)      : streamed HTTP PUT
S3UploadService -> AWS S3                                     : 200 OK
BackupWorkflow -> IdempotencyService.markSuccess(record, objectKey)
AWS S3 -> Python Lambda                                       : ObjectCreated event
```

---

## 5. Why `@Scheduled` instead of Spring Batch

See the full rationale as Javadoc on `BackupWorkflow`, summarized here:

Spring Batch earns its complexity (JobRepository, chunked reader/processor/writer, step
restart metadata, partitioning) when a job processes **many items per run** with
chunk-level commit/rollback. This agent selects and uploads **exactly one file per
execution** - there is nothing to chunk and nothing to partition. Introducing Spring Batch
would mean standing up a JobRepository schema and a job-configuration layer purely to
sequence six method calls.

Instead:
- `@Scheduled(cron = "${psystem.scheduler.cron}")` provides the trigger.
- `IdempotencyService` + the local JSON ledger provide the restart-safety Batch's
  JobRepository would otherwise provide, scoped to exactly what's needed here.
- `BackupWorkflow` still expresses the same six logical steps from the spec
  (Scan → Select → Metadata → Presign → Upload → Record) as clearly separated,
  independently unit-tested method calls - just without the extra framework.

If a future requirement introduces **multi-file** batch uploads with chunked processing,
revisit this decision.

---

## 6. Idempotency strategy

**Fingerprint** = `fileName + fileSizeBytes + lastModified(epoch ms) + machineId`, stored in
a local JSON ledger file (`UploadRecord`, one entry per fingerprint) alongside a status:
`IN_PROGRESS` → `SUCCESS` / `FAILED`. No database or JDBC connection is involved - see
`UploadLedgerStore`.

- Before uploading, the workflow checks whether a `SUCCESS` record already exists for the
  fingerprint; if so, the file is skipped.
- A record is written as `IN_PROGRESS` **before** the pre-signed URL is even requested. If
  the JVM crashes or is killed mid-upload, the next scheduled run finds a stale
  `IN_PROGRESS` record - which is not `SUCCESS` - so the file is correctly retried rather
  than silently skipped or silently duplicated.

**Checksum trade-off**: a full SHA-256 of a 500MB+ file adds meaningful I/O/CPU cost on a
customer machine that may be modest hardware, for a duplicate-detection benefit that
name + size + mtime already provides in practice (a genuinely different backup file will
differ in at least one of those three attributes). Checksum-based fingerprinting is
therefore **not** enabled by default; it's a natural extension point
(`IdempotencyService.fingerprint`) if a customer needs cryptographic certainty and accepts
the extra scan time.

---

## 7. Preserving Machine ID / Location / Destination Path through the S3 event

The S3 `ObjectCreated` event payload itself is intentionally minimal (bucket + key + size) -
it does **not** carry custom metadata inline. Two complementary mechanisms preserve the
context for the Lambda:

1. **S3 object key structure** (always available, zero extra API calls):
   ```
   customer1/MACHINE-001/2026/08/13/backup3.zip
   ```
   Encodes Machine ID and upload date directly in the key, so the Lambda can extract them
   by parsing the key it already receives in the event - no extra round trip needed for the
   common case.

2. **S3 object user metadata** (`x-amz-meta-*` headers), set by including them as
   `requiredHeaders` in the pre-signed URL response and sent by `S3UploadService` on the PUT
   request. These are stored as first-class S3 object metadata and are retrievable via a
   `HeadObject`/`GetObject` call using the bucket+key from the event - giving the Lambda the
   full `machineId` / `location` / `destinationPath` triple without relying on key-parsing
   conventions.

   Example headers the backend can request:
   ```
   x-amz-meta-machine-id: MACHINE-001
   x-amz-meta-location: Kolkata
   x-amz-meta-destination-path: /customer/backup
   ```

**Recommended approach**: use the object-key convention for fast, event-only routing, and
S3 object metadata as the authoritative source of truth the Lambda reads via `HeadObject`
before processing - this avoids relying on a database lookup keyed by object name and keeps
the agent, the event, and the object self-describing.

---

## 8. API contract

### Request — `POST {psystem.upload.presigned-url-api}`

```json
{
  "machineId": "MACHINE-001",
  "customerPrefix": "customer1",
  "location": "Kolkata",
  "destinationPath": "/customer/backup",
  "fileName": "backup3.zip",
  "fileSize": 524288000,
  "fileType": "ZIP",
  "executionId": "8f123abc"
}
```

Headers: `Content-Type: application/json`, `Authorization: Bearer <psystem.upload.api-key>`
(sent only if configured).

`customerPrefix` (from `psystem.customer.prefix`, default `customer1`) is the top-level S3
key prefix and is entirely agent-configured - the backend no longer decides it via its own
env var. This lets one central backend/Lambda serve many customer agents, each landing
uploads under its own prefix. See `lambda/presigned_url_lambda.py` for the fallback the
backend uses if an older agent build omits this field.

### Response — `200 OK`

```json
{
  "uploadUrl": "https://s3-presigned-url...",
  "bucket": "my-backup-bucket",
  "objectKey": "customer1/MACHINE-001/2026/08/13/backup3.zip",
  "expiresIn": 900,
  "requiredHeaders": {
    "x-amz-meta-machine-id": "MACHINE-001",
    "x-amz-meta-location": "Kolkata",
    "x-amz-meta-destination-path": "/customer/backup"
  }
}
```

### HTTP status / error handling

| Status | Meaning | Agent behavior |
|---|---|---|
| 200 | Success | Proceed to upload |
| 400 | Validation error | Fail fast, **no retry** (`PresignedUrlException`) |
| 401/403 | Auth failure | Fail fast, **no retry** |
| 5xx | Backend transient failure | Retried with exponential backoff, up to `psystem.upload.max-retries` |
| Connection/timeout | Network issue | Retried with exponential backoff |

Error response body format expected from the backend (surfaced in agent logs, never in
system-wide alerts with sensitive data):
```json
{ "error": "VALIDATION_ERROR", "message": "fileSize must be > 0" }
```

### S3 PUT upload

- Method: `PUT {uploadUrl}`
- Headers: `Content-Type: application/octet-stream`, plus any `requiredHeaders` from the
  presigned-URL response, sent verbatim so they match what the backend signed.
- Body: raw file bytes, streamed with `Content-Length` fixed to the exact known file size.
- `200`/`204` → success. `403` → expired/invalid signature (fresh URL requested, not a raw
  retry against the same URL). Any other non-2xx → `S3UploadException`.

---

## 9. Error handling matrix

| Scenario | Behavior |
|---|---|
| No ZIP/RAR files found | Logged as INFO, execution completes successfully (no-op) |
| Directory missing/unreadable | `DirectoryUnavailableException`, execution marked failed, logged as ERROR |
| File deleted/moved mid-processing | `FileVanishedException` at stability-check or upload time |
| File still being written | `FileNotStableException` after N unstable size checks; retried on the *next* scheduled run, not within the same run |
| Pre-signed URL API down/5xx | Exponential backoff retry, then `PresignedUrlException` |
| Pre-signed URL expired (403 on PUT) | Detected, not blindly retried against the same URL - the design supports requesting a new one on the next attempt via the workflow's retry envelope |
| S3 upload failure | Retried per `psystem.upload.max-retries`, then `S3UploadException`; `UploadRecord` marked `FAILED` for operator visibility |
| Application restart mid-upload | `IN_PROGRESS` record left behind is not `SUCCESS`, so the file is safely retried; no corrupted state |

---

## 10. Security

- No AWS access/secret keys are ever present on the customer PC - only short-lived
  pre-signed URLs (`expiresIn`, typically 900s) are used.
- Pre-signed URLs and `Authorization` headers are **never logged** (`PresignedUrlClient`,
  `S3UploadService` log only object keys, sizes, and HTTP status codes).
- File contents/bytes are never logged.
- The pre-signed-URL API is called over HTTPS (`psystem.upload.presigned-url-api` should
  always be an `https://` URL; this is an operator/config responsibility).
- `psystem.upload.api-key` should be supplied via environment variable
  (`PSYSTEM_API_KEY`), never committed to a config file in source control.
- The local admin HTTP endpoint (`AdminController`) binds to `127.0.0.1` only
  (`server.address` in `application.yml`) - not reachable from the network by default.
- Recommended S3-side controls (bucket policy, not implemented in this Java agent):
  least-privilege IAM policy scoped to `s3:PutObject` on the specific prefix per
  pre-signed-URL-issuing role, default SSE-S3 or SSE-KMS server-side encryption on the
  bucket, and a bucket policy denying unencrypted uploads.

---

## 11. Idempotency, retry, and logging - quick reference

Already detailed in sections 6, 9, and inline Javadoc on `IdempotencyService`,
`PresignedUrlClient`, and `S3UploadService`. Structured JSON logs are written to
`./.psystem/logs/psystem-agent.log` (see `logback-spring.xml`) in addition to console
output, with a correlation `executionId` on every log line for a given run - see the sample
log walkthrough below.

```text
Scheduler started
Execution ID: 8f123abc
Source directory: /customer/backup
ZIP/RAR files found: 4
Selected file: backup3.zip (524288000 bytes)
Machine ID: MACHINE-001, Location: Kolkata
Requesting pre-signed URL... received.
Starting S3 upload... Upload successful.
S3 Object Key: customer1/MACHINE-001/2026/08/13/backup3.zip
Scheduler completed successfully
```

---

## 12. Testing

Run: `mvn test`

| Test class | Covers |
|---|---|
| `FileScannerServiceTest` | largest-file selection, case-insensitive extensions, empty dir, unsupported files, missing/invalid directory, subdirectories ignored |
| `FileStabilityServiceTest` | stable file passes, growing file rejected, vanished file detected |
| `MachineMetadataServiceTest` | manual strategy, missing manual id, generated-persisted stability across restarts |
| `IdempotencyServiceTest` | fingerprint determinism, fingerprint sensitivity to size, duplicate detection, success/failure record persistence |
| `PresignedUrlClientTest` (WireMock) | success, 4xx fail-fast (no retry), malformed response, request-body contract |
| `S3UploadServiceTest` (WireMock) | success PUT, 403 expired-URL handling, 5xx handling, vanished-file guard |
| `BackupSchedulerTest` | overlap prevention, lock release on success/failure, no-op on `NoCandidateFileException`, disabled-scheduler no-op |
| `PsystemApplicationIntegrationTest` | full Spring context wiring (retry aspect, scheduling, property binding) |

All AWS/API interactions are mocked (WireMock) or unit-tested with Mockito - **no real AWS
account or credentials are required** to run the suite. For pre-release confidence beyond
unit tests, run against a staging S3 bucket + disposable pre-signed-URL endpoint manually or
in a nightly pipeline (not on every commit).

---

## 13. Deployment instructions (customer PC)

### Prerequisites
- Java 17+ runtime installed.
- Network egress to the pre-signed-URL API and to AWS S3 (HTTPS, 443).
- A local directory for the agent's working data (`./.psystem/` - ledger DB, logs, generated
  machine-id file) with write permission for the service account.

### Steps
1. Build: `mvn clean package` → produces `target/psystem-scheduler-agent.jar`.
2. Copy the JAR and a customer-specific override file (see
   `deploy/application-customer-example.yml`) to the target machine, e.g.
   `C:\psystem-agent\` or `/opt/psystem-agent/`.
3. Set the customer-specific environment variables (or edit the override YAML directly):
   `PSYSTEM_SOURCE_DIR`, `PSYSTEM_LOCATION`, `PSYSTEM_PRESIGNED_URL_API`, `PSYSTEM_API_KEY`,
   `PSYSTEM_AWS_REGION`, `PSYSTEM_AWS_BUCKET`, `PSYSTEM_CRON`.
4. **Linux**: install `deploy/psystem-scheduler-agent.service` under
   `/etc/systemd/system/`, then `systemctl enable --now psystem-scheduler-agent`.
5. **Windows**: use `deploy/run-windows.bat` wrapped with NSSM as a Windows Service, or a
   Task Scheduler entry with trigger "At startup".
6. Verify: `curl http://127.0.0.1:8085/actuator/health` and
   `curl http://127.0.0.1:8085/admin/status`.

---

## 14. Operational monitoring & troubleshooting

- **Health**: `GET /actuator/health` (Spring Boot Actuator).
- **Manual trigger** (for testing without waiting for the cron tick):
  `POST /admin/trigger` → `202 Accepted`, or `409 Conflict` if a run is already in progress.
- **Status**: `GET /admin/status` → `{"executionInProgress": false}`.
- **Logs**: `./.psystem/logs/psystem-agent.log` (structured JSON, rotated daily, capped at
  1GB total) plus console/stdout.
- **Upload history**: open the local JSON ledger directly
  (`./.psystem/ledger.json` by default, or wherever `psystem.ledger.file-path` points) to
  see every `UploadRecord` with its status and `s3ObjectKey` - useful for confirming exactly
  which file was uploaded and when without needing AWS console access or a database client.
- **Common issues**:
  - *No uploads happening*: check `psystem.scheduler.enabled`, confirm the cron expression
    with `/admin/status` logs at startup, and confirm the source directory has eligible
    `.zip`/`.rar` files.
  - *Repeated `FileNotStableException`*: the source file is still being written when the
    cron fires - either the backup job upstream runs too close to the cron time, or
    `psystem.file.stability-check-attempts`/`-interval-seconds` need to be increased.
  - *`PresignedUrlException` on every run*: verify `psystem.upload.presigned-url-api` and
    `PSYSTEM_API_KEY`; check for 4xx vs 5xx in the logs (4xx = config/auth problem, not
    retried).
  - *Same file uploads repeatedly*: check the JSON ledger for a fingerprint that never
    reaches `SUCCESS` - typically indicates a crash loop during upload; check
    `failureReason` on the latest `FAILED` record.

---

## 15. Configuration reference

See `src/main/resources/application.yml` for the full set of `psystem.*` properties and
their environment-variable overrides, and `deploy/application-customer-example.yml` for a
filled-in customer example. Nothing customer-specific is hard-coded in Java source - every
value in section 16 of the original spec is bound via `AgentProperties`.
