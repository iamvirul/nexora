package me.virul.nexora.spi;

/**
 * Describes one input or output field of a capability.
 *
 * @param name        the field name, as used in step inputs or read from the output
 * @param type        a simple type hint: {@code "string"}, {@code "number"}, {@code "boolean"} or
 *                    {@code "object"}
 * @param required    whether the capability needs the field to be set
 * @param description what the field means, in plain language
 */
public record ParameterDescriptor(String name, String type, boolean required, String description) {}
