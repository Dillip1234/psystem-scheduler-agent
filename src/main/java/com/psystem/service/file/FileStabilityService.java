package com.psystem.service.file;

import com.psystem.config.AgentProperties;
import com.psystem.exception.FileNotStableException;
import com.psystem.exception.FileVanishedException;
import com.psystem.model.domain.CandidateFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Verifies that a selected file is no longer being written to before it is uploaded, to
 * avoid shipping a partial backup that is still mid-copy on the customer machine.
 * <p>
 * Strategy: sample the file size N times (configurable), sleeping between samples. The file
 * is considered stable only if every consecutive sample reports the identical size.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStabilityService {

    private final AgentProperties properties;

    public void verifyStable(CandidateFile candidate) {
        Path path = candidate.getPath();
        int attempts = properties.getFile().getStabilityCheckAttempts();
        long intervalMs = properties.getFile().getStabilityCheckIntervalSeconds() * 1000L;

        long previousSize = candidate.getSizeBytes();

        for (int attempt = 1; attempt <= attempts; attempt++) {
            sleep(intervalMs);

            long currentSize = readCurrentSize(path);

            if (currentSize != previousSize) {
                throw new FileNotStableException(String.format(
                        "File '%s' is still being written (size changed from %d to %d bytes on check %d/%d)",
                        candidate.getFileName(), previousSize, currentSize, attempt, attempts));
            }
            previousSize = currentSize;
            log.debug("Stability check {}/{} passed for {} (size={} bytes)",
                    attempt, attempts, candidate.getFileName(), currentSize);
        }

        log.info("File '{}' confirmed stable after {} checks", candidate.getFileName(), attempts);
    }

    private long readCurrentSize(Path path) {
        try {
            if (!Files.exists(path)) {
                throw new FileVanishedException("File disappeared during stability check: " + path.getFileName());
            }
            return Files.size(path);
        } catch (IOException e) {
            throw new FileVanishedException("Unable to read file size during stability check: " + path.getFileName());
        }
    }

    private void sleep(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FileNotStableException("Interrupted while waiting during file stability check");
        }
    }
}
