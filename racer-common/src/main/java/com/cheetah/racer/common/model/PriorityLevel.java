package com.cheetah.racer.common.model;

/**
 * Built-in message priority levels used by the Racer priority-channel feature (R-10).
 *
 * <p>The numeric {@link #weight} determines processing order: lower weight = higher priority.
 * Consumer implementations poll channels in ascending weight order.
 *
 * <h3>Custom levels</h3>
 * These constants represent the default three-tier hierarchy.
 * Additional levels can be declared via {@code racer.priority.levels} configuration;
 * they are resolved by name at runtime.
 */
public enum PriorityLevel {

    HIGH(0),
    NORMAL(1),
    LOW(2);

    /** Lower weight = processed first. */
    private final int weight;

    PriorityLevel(int weight) {
        this.weight = weight;
    }

    public int getWeight() {
        return weight;
    }

    /**
     * Resolves a priority level by name (case-insensitive), defaulting to {@link #NORMAL}
     * when the name cannot be matched.
     */
    public static PriorityLevel of(String name) {
        if (name == null || name.isBlank()) {
            return NORMAL;
        }
        try {
            return PriorityLevel.valueOf(name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NORMAL;
        }
    }
}
