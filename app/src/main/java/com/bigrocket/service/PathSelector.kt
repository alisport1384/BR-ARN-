package com.bigrocket.service

import android.net.Network

/**
 * The only contract the Final Stream Layer (TunPacketRouter: reads the TUN, parses packets,
 * hands bytes down) needs from path/route decision-making. It should not need to know
 * Path3Router exists, let alone anything about weights, health checks, or that "the two
 * paths" happen to be Wi-Fi and Cellular - see learnings-and-working-style's architecture
 * test: "if I swapped the physical transports for a local test transport, would this
 * component need to change?". Before this interface existed, TunPacketRouter depended on
 * the concrete Path3Router class directly; the answer would have been yes. Now it depends
 * on this, and the answer is no.
 *
 * Path3Router already documented this exact boundary in its own class comment ("callers
 * above this class only ask Path 3 for the physical Network to use") - this interface makes
 * that existing intent explicit in the type system instead of just in a comment.
 */
interface PathSelector {
    fun selectNetwork(slot: Int? = null): Network?
}
