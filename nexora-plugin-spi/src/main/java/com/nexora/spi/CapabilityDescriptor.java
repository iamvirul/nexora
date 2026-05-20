package com.nexora.spi;

import com.nexora.core.capability.CapabilityContract;

import java.util.List;
import java.util.Objects;

public record CapabilityDescriptor(
        String id,
        String description,
        List<ParameterDescriptor> inputParameters,
        List<ParameterDescriptor> outputParameters,
        boolean idempotent,
        boolean async,
        CapabilityContract contract
) {
    public CapabilityDescriptor {
        Objects.requireNonNull(id, "id must not be null");
        inputParameters = inputParameters == null ? List.of() : List.copyOf(inputParameters);
        outputParameters = outputParameters == null ? List.of() : List.copyOf(outputParameters);
        contract = contract == null ? CapabilityContract.none() : contract;
    }

    /** Backward-compatible constructor — defaults to no contract. */
    public CapabilityDescriptor(
            String id,
            String description,
            List<ParameterDescriptor> inputParameters,
            List<ParameterDescriptor> outputParameters,
            boolean idempotent,
            boolean async) {
        this(id, description, inputParameters, outputParameters, idempotent, async, CapabilityContract.none());
    }
}
