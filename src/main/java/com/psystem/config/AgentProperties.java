package com.psystem.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Root binding for every customer/environment specific setting under the {@code psystem}
 * prefix in application.yml. Nothing here is hard-coded in Java - all values are supplied
 * through configuration (application.yml, environment variables, or a mounted secrets file),
 * so the same JAR can be deployed unmodified to any customer machine.
 */
@Data
@Validated
@ConfigurationProperties(prefix = "psystem")
public class AgentProperties {

    @Valid
    @NestedConfigurationProperty
    private Scheduler scheduler = new Scheduler();

    @Valid
    @NestedConfigurationProperty
    private Machine machine = new Machine();

    @Valid
    @NestedConfigurationProperty
    private Customer customer = new Customer();

    @Valid
    @NestedConfigurationProperty
    private FileConfig file = new FileConfig();

    @Valid
    @NestedConfigurationProperty
    private Upload upload = new Upload();

    @Valid
    @NestedConfigurationProperty
    private Aws aws = new Aws();

    @Valid
    @NestedConfigurationProperty
    private Ledger ledger = new Ledger();

    @Data
    public static class Scheduler {
        /** Standard 6-field Spring cron expression. Read at startup; no code change required to alter timing. */
        @NotBlank
        private String cron = "0 5 13 * * ?";

        /** Master on/off switch - useful for maintenance windows without undeploying the agent. */
        private boolean enabled = true;

        /** Guards against overlapping executions if a run takes longer than the cron interval. */
        private boolean preventOverlap = true;
    }

    @Data
    public static class Machine {
        /**
         * Strategy for obtaining the Machine ID:
         * MANUAL - use {@link #id} as-is.
         * GENERATED_PERSISTED - generate a UUID once and persist it to {@link #idFilePath}.
         * OS_IDENTIFIER - derive from an OS-provided identifier (e.g. Windows MachineGuid).
         */
        private MachineIdStrategy idStrategy = MachineIdStrategy.GENERATED_PERSISTED;

        /** Used only when idStrategy = MANUAL. */
        private String id;

        /** Where a generated machine id is persisted so it survives restarts. */
        private String idFilePath = "./.psystem/machine-id";

        /** Human-configured location label - not derived from GPS/IP geolocation. */
        @NotBlank
        private String location;
    }

    @Data
    public static class Customer {
        /**
         * Top-level S3 key prefix identifying which customer this deployment belongs to,
         * e.g. objects land at {@code <prefix>/<machineId>/<yyyy>/<MM>/<dd>/<fileName>}.
         * Sent on every pre-signed URL request so the backend no longer needs its own
         * per-deployment CUSTOMER_PREFIX env var - one central backend can serve many
         * customer agents, each configured with its own prefix here.
         * Must match the S3-key-safe pattern also enforced Lambda-side.
         */
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "must contain only letters, digits, '.', '_' or '-'")
        private String prefix = "customer1";
    }

    @Data
    public static class FileConfig {
        @NotBlank
        private String sourceDirectory;

        @NotEmpty
        private List<String> allowedExtensions = List.of(".zip", ".rar");

        /** Number of seconds to wait between size checks when verifying a file is no longer being written. */
        @Min(0)
        private int stabilityCheckIntervalSeconds = 5;

        /** Number of consecutive stable checks required before a file is considered upload-ready. */
        @Min(1)
        private int stabilityCheckAttempts = 3;
    }

    @Data
    public static class Upload {
        @NotBlank
        private String presignedUrlApi;

        /** Optional bearer token / API key for the pre-signed URL backend, injected via env var. */
        private String apiKey;

        @Min(1000)
        private int connectionTimeoutMs = 30000;

        @Min(1000)
        private int readTimeoutMs = 300000;

        @Min(0)
        private int maxRetries = 3;

        @Min(0)
        private long initialBackoffMs = 1000;

        @Min(1)
        private double backoffMultiplier = 2.0;

        /** Chunk size used when streaming the file body to S3. */
        private int uploadBufferSizeBytes = 8 * 1024 * 1024; // 8MB

        /** If false (default) the local source file is left completely untouched after a successful upload. */
        private boolean deleteAfterUpload = false;
    }

    @Data
    public static class Aws {
        @NotBlank
        private String region;

        @NotBlank
        private String bucket;
    }

    @Data
    public static class Ledger {
        /**
         * Where the upload-history / idempotency ledger is stored, as a single JSON file.
         * No database or JDBC connection is involved - see
         * {@code com.psystem.repository.UploadLedgerStore}. Kept alongside the machine-id
         * file under the same working directory the agent already writes to.
         */
        @NotBlank
        private String filePath = "./.psystem/ledger.json";
    }

    public enum MachineIdStrategy {
        MANUAL, GENERATED_PERSISTED, OS_IDENTIFIER
    }
}
