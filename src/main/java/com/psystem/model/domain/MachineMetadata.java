package com.psystem.model.domain;

import lombok.Builder;
import lombok.Value;

/**
 * Minimal, non-personally-identifiable machine context attached to every upload:
 * a stable Machine ID, a human-configured Location label, the configured
 * Destination Path, and the configured Customer Prefix. No hostname, username,
 * IP address, or hardware serials are collected beyond what the chosen
 * {@code idStrategy} explicitly requires.
 */
@Value
@Builder
public class MachineMetadata {
    String machineId;
    String location;
    String destinationPath;
    String customerPrefix;
}
