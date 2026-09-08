package com.psystem.service.metadata;

import com.psystem.config.AgentProperties;
import com.psystem.model.domain.MachineMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Resolves a stable Machine ID and packages it with the configured Location and
 * Destination Path into a {@link MachineMetadata} object attached to every upload.
 * <p>
 * Three strategies are supported (see {@code psystem.machine.id-strategy}):
 * <ul>
 *   <li>{@code MANUAL} - operator supplies {@code psystem.machine.id} directly.</li>
 *   <li>{@code GENERATED_PERSISTED} (default) - a random UUID is generated once and written
 *       to a local marker file so it survives application restarts and JVM/host reboots.</li>
 *   <li>{@code OS_IDENTIFIER} - derived from an OS-provided identifier (e.g. Windows
 *       {@code MachineGuid} from the registry, or {@code /etc/machine-id} on Linux). This
 *       keeps the value stable without generating our own file, at the cost of an
 *       OS-specific lookup.</li>
 * </ul>
 * All strategies avoid collecting anything beyond a single opaque identifier - no hostname,
 * username, MAC address, or hardware serial is gathered.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MachineMetadataService {

    private final AgentProperties properties;

    private volatile String cachedMachineId;

    public MachineMetadata resolve() {
        return MachineMetadata.builder()
                .machineId(resolveMachineId())
                .location(properties.getMachine().getLocation())
                .destinationPath(properties.getFile().getSourceDirectory())
                .customerPrefix(properties.getCustomer().getPrefix())
                .build();
    }

    private String resolveMachineId() {
        if (cachedMachineId != null) {
            return cachedMachineId;
        }

        synchronized (this) {
            if (cachedMachineId != null) {
                return cachedMachineId;
            }

            AgentProperties.MachineIdStrategy strategy = properties.getMachine().getIdStrategy();
            String resolved = switch (strategy) {
                case MANUAL -> resolveManual();
                case GENERATED_PERSISTED -> resolveGeneratedPersisted();
                case OS_IDENTIFIER -> resolveOsIdentifier();
            };

            log.info("Resolved machine id using strategy={} -> machineId={}", strategy, resolved);
            cachedMachineId = resolved;
            return resolved;
        }
    }

    private String resolveManual() {
        String configured = properties.getMachine().getId();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "psystem.machine.id-strategy=MANUAL requires psystem.machine.id to be set");
        }
        return configured;
    }

    private String resolveGeneratedPersisted() {
        Path idFile = Paths.get(properties.getMachine().getIdFilePath()).toAbsolutePath().normalize();

        try {
            if (Files.exists(idFile)) {
                String existing = Files.readString(idFile, StandardCharsets.UTF_8).trim();
                if (!existing.isBlank()) {
                    return existing;
                }
            }

            Files.createDirectories(idFile.getParent());
            String generated = "MC-" + UUID.randomUUID();
            Files.writeString(idFile, generated, StandardCharsets.UTF_8);
            log.info("Generated new persistent machine id and stored it at {}", idFile);
            return generated;

        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Unable to read/persist machine id file at " + idFile, e);
        }
    }

    private String resolveOsIdentifier() {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                return readWindowsMachineGuid();
            } else if (os.contains("linux")) {
                return readLinuxMachineId();
            } else if (os.contains("mac")) {
                return readMacHardwareUuid();
            }
        } catch (Exception e) {
            log.warn("Failed to read OS-provided machine identifier ({}). " +
                    "Falling back to a generated/persisted id instead.", e.getMessage());
        }
        // Graceful fallback keeps the scheduler running even on an unsupported OS or
        // sandboxed environment where the OS identifier is not accessible.
        return resolveGeneratedPersisted();
    }

    private String readWindowsMachineGuid() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("reg", "query",
                "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid");
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        for (String line : output.split("\\R")) {
            if (line.contains("MachineGuid")) {
                String[] parts = line.trim().split("\\s+");
                return "OS-" + parts[parts.length - 1];
            }
        }
        throw new IOException("MachineGuid not found in registry output");
    }

    private String readLinuxMachineId() throws IOException {
        Path path = Paths.get("/etc/machine-id");
        return "OS-" + Files.readString(path, StandardCharsets.UTF_8).trim();
    }

    private String readMacHardwareUuid() throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("ioreg", "-rd1", "-c", "IOPlatformExpertDevice");
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        for (String line : output.split("\\R")) {
            if (line.contains("IOPlatformUUID")) {
                String[] parts = line.split("\"");
                return "OS-" + parts[parts.length - 2];
            }
        }
        throw new IOException("IOPlatformUUID not found in ioreg output");
    }
}
