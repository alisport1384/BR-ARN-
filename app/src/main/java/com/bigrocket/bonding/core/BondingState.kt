package com.bigrocket.bonding.core

/** Engine-wide lifecycle state (Section 317, 523). Valid transitions:
 *  STOPPED -> STARTING -> ACTIVE -> DEGRADED -> STOPPING -> STOPPED
 *  Recovery sub-cycle while running: ACTIVE -> DEGRADED -> ACTIVE (both paths never actually
 *  reach FAILED at the engine level; that is tracked per-path in [com.bigrocket.bonding.transport.PathState] -
 *  the engine only goes to ERROR on an unrecoverable runtime fault, Section 412/417). */
enum class BondingState {
    STOPPED,
    STARTING,
    ACTIVE,
    DEGRADED,
    STOPPING,
    ERROR;

    fun canTransitionTo(next: BondingState): Boolean = when (this) {
        STOPPED -> next == STARTING
        STARTING -> next == ACTIVE || next == ERROR || next == STOPPING
        ACTIVE -> next == DEGRADED || next == STOPPING || next == ERROR
        DEGRADED -> next == ACTIVE || next == STOPPING || next == ERROR
        STOPPING -> next == STOPPED
        ERROR -> next == STOPPING || next == STOPPED
    }
}
