package com.cheetah.racer.common.schema;

/**
 * Describes a single JSON Schema constraint violation.
 *
 * @param type     Short constraint type, e.g. {@code required}, {@code type}, {@code minimum}.
 * @param path     JSON Pointer path to the offending location, e.g. {@code /amount}.
 * @param message  Human-readable description of the violation.
 */
public record SchemaViolation(String type, String path, String message) {}
