package com.smartfreight.port_service.entity;

/**
 * Enumeration of container statuses in the port system.
 *
 * Lifecycle (in order):
 * 1. IN_TRANSIT  – container is travelling by ship or truck
 * 2. AT_PORT     – arrived at the port terminal and unloaded
 * 3. CUSTOMS     – undergoing customs inspection (held in customs warehouse)
 * 4. RELEASED    – customs has approved release (ready for collection)
 * 5. DELIVERED   – delivered to the final recipient
 *
 * After DELIVERED the cycle resets to IN_TRANSIT for demonstration purposes
 * (see {@link com.smartfreight.port_service.scheduler.StatusUpdateScheduler}).
 */
public enum ContainerStatus {
    IN_TRANSIT,
    AT_PORT,
    CUSTOMS,
    RELEASED,
    DELIVERED
}
