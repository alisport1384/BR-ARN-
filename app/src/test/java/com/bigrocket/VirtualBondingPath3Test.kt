package com.bigrocket

import com.bigrocket.bonding.path3.Path3State
import com.bigrocket.bonding.path3.VirtualPath3Endpoint
import org.junit.Assert.*
import org.junit.Test

/**
 * Section 180-183: Proof Level 0 - Path3 independent verification
 * Unit test for VirtualPath3Endpoint without Android dependencies
 */
class VirtualBondingPath3Test {

    @Test
    fun testPath3Lifecycle() {
        val endpoint = VirtualPath3Endpoint(mtu = 1400)
        assertEquals(Path3State.CREATED, endpoint.state())

        endpoint.start()
        assertEquals(Path3State.ACTIVE, endpoint.state())
        assertTrue(endpoint.isActive())
        assertTrue(endpoint.verifyBidirectional())

        endpoint.stop()
        assertEquals(Path3State.STOPPED, endpoint.state())
    }

    @Test
    fun testPath3Bidirectional() {
        val endpoint = VirtualPath3Endpoint()
        endpoint.start()

        // Android -> Bonding
        val packetOut = "Hello from Android".toByteArray()
        assertTrue(endpoint.injectFromAndroid(packetOut))
        assertEquals(1, endpoint.counters.snapshot().p3TxPackets.toInt())

        val receivedByBonding = endpoint.receiveFromAndroid()
        assertNotNull(receivedByBonding)
        assertArrayEquals(packetOut, receivedByBonding)

        // Bonding -> Android
        val packetIn = "Hello from Bonding via VPS".toByteArray()
        assertTrue(endpoint.sendToAndroid(packetIn))
        assertEquals(1, endpoint.counters.snapshot().p3RxPackets.toInt())

        val receivedByAndroid = endpoint.readForAndroid()
        assertNotNull(receivedByAndroid)
        assertArrayEquals(packetIn, receivedByAndroid)

        endpoint.stop()
    }

    @Test
    fun testPath3BoundedQueue() {
        val endpoint = VirtualPath3Endpoint(inputCapacity = 2, outputCapacity = 2)
        endpoint.start()

        assertTrue(endpoint.injectFromAndroid("pkt1".toByteArray()))
        assertTrue(endpoint.injectFromAndroid("pkt2".toByteArray()))
        // Third should fail - bounded queue
        assertFalse(endpoint.injectFromAndroid("pkt3".toByteArray()))
        assertTrue(endpoint.isInputQueueFull())

        endpoint.stop()
    }

    @Test
    fun testPath3Counters() {
        val endpoint = VirtualPath3Endpoint()
        endpoint.start()

        endpoint.injectFromAndroid(ByteArray(100))
        endpoint.injectFromAndroid(ByteArray(200))
        endpoint.sendToAndroid(ByteArray(150))

        val snap = endpoint.counters.snapshot()
        assertEquals(2, snap.p3TxPackets)
        assertEquals(300, snap.p3TxBytes)
        assertEquals(1, snap.p3RxPackets)
        assertEquals(150, snap.p3RxBytes)

        endpoint.stop()
    }

    @Test
    fun testPath3MtuContract() {
        val endpoint = VirtualPath3Endpoint(mtu = 100)
        endpoint.start()

        // Packet larger than MTU should be rejected
        assertFalse(endpoint.injectFromAndroid(ByteArray(101)))
        assertFalse(endpoint.sendToAndroid(ByteArray(101)))

        // Packet within MTU should be accepted
        assertTrue(endpoint.injectFromAndroid(ByteArray(100)))
        assertTrue(endpoint.sendToAndroid(ByteArray(100)))

        endpoint.stop()
    }
}
