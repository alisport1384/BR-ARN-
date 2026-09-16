package com.bigrocket.bonding.path3

/**
 * Section 158/1248: Path 3 state machine.
 * Immutable contract: Path3 must have explicit lifecycle, must not depend on single upstream.
 */
enum class Path3State {
    CREATED,
    INITIALIZING,
    READY,
    ACTIVE,
    DEGRADED,
    NO_UPSTREAM,
    STOPPING,
    STOPPED,
    FAILED;

    fun canTransitionTo(next: Path3State): Boolean = when (this) {
        CREATED -> next == INITIALIZING || next == STOPPED
        INITIALIZING -> next == READY || next == FAILED || next == STOPPING
        READY -> next == ACTIVE || next == DEGRADED || next == NO_UPSTREAM || next == STOPPING || next == FAILED
        ACTIVE -> next == DEGRADED || next == NO_UPSTREAM || next == STOPPING || next == FAILED
        DEGRADED -> next == ACTIVE || next == NO_UPSTREAM || next == STOPPING || next == FAILED
        NO_UPSTREAM -> next == ACTIVE || next == DEGRADED || next == STOPPING || next == FAILED
        STOPPING -> next == STOPPED || next == FAILED
        FAILED -> next == INITIALIZING || next == STOPPING || next == STOPPED
        STOPPED -> next == INITIALIZING || next == CREATED
    }
}
