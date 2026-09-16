package com.bigrocket.bonding.transport

/** Per-path lifecycle state (Section 317, 409). Valid transitions (Section 56, 409, 462):
 *  INIT -> ACTIVE -> DEGRADED -> FAILED -> RECOVERING -> ACTIVE (or CLOSED from any state on
 *  shutdown). DEGRADED -> ACTIVE is also allowed directly if quality recovers before a hard
 *  failure is declared. FAILED -> ACTIVE directly (skipping RECOVERING) is invalid (Section 461
 *  "Invalid State Protection": FAILED -> ACTIVE without Recovery is forbidden). */
enum class PathState {
    INIT,
    ACTIVE,
    DEGRADED,
    FAILED,
    RECOVERING,
    CLOSED;

    /** Section 462: the only state-machine-legal next states from this one. Enforced by
     *  [com.bigrocket.bonding.transport.PathRuntimeImpl.transitionTo]. */
    fun canTransitionTo(next: PathState): Boolean = when (this) {
        INIT -> next == ACTIVE || next == FAILED || next == CLOSED
        ACTIVE -> next == DEGRADED || next == FAILED || next == CLOSED
        DEGRADED -> next == ACTIVE || next == FAILED || next == CLOSED
        FAILED -> next == RECOVERING || next == CLOSED
        RECOVERING -> next == ACTIVE || next == FAILED || next == CLOSED
        CLOSED -> false
    }
}
