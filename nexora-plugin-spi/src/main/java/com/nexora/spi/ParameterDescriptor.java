package com.nexora.spi;

public record ParameterDescriptor(
        String name,
        String type, // simple type hint: "string", "number", "boolean", "object"
        boolean required,
        String description) {}
