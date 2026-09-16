package com.bigrocket.bonding.path3

import java.util.concurrent.atomic.AtomicLong

/**
 * Section 64/195/924: Observability contract for Path3 + P1/P2.
 * Required for proof: P1 traffic >0 AND P2 traffic >0 AND P3 traffic >0
 */
data class Path3CountersSnapshot(
    val p3TxPackets: Long,
    val p3RxPackets: Long,
    val p3TxBytes: Long,
    val p3RxBytes: Long,
    val p1TxPackets: Long,
    val p1RxPackets: Long,
    val p1TxBytes: Long,
    val p1RxBytes: Long,
    val p2TxPackets: Long,
    val p2RxPackets: Long,
    val p2TxBytes: Long,
    val p2RxBytes: Long,
    val bondedTxPackets: Long,
    val bondedRxPackets: Long,
    val timestampMs: Long
)

class Path3Counters {
    private val _p3TxPackets = AtomicLong(0)
    private val _p3RxPackets = AtomicLong(0)
    private val _p3TxBytes = AtomicLong(0)
    private val _p3RxBytes = AtomicLong(0)

    private val _p1TxPackets = AtomicLong(0)
    private val _p1RxPackets = AtomicLong(0)
    private val _p1TxBytes = AtomicLong(0)
    private val _p1RxBytes = AtomicLong(0)

    private val _p2TxPackets = AtomicLong(0)
    private val _p2RxPackets = AtomicLong(0)
    private val _p2TxBytes = AtomicLong(0)
    private val _p2RxBytes = AtomicLong(0)

    private val _bondedTxPackets = AtomicLong(0)
    private val _bondedRxPackets = AtomicLong(0)

    fun incP3Tx(bytes: Int) { _p3TxPackets.incrementAndGet(); _p3TxBytes.addAndGet(bytes.toLong()) }
    fun incP3Rx(bytes: Int) { _p3RxPackets.incrementAndGet(); _p3RxBytes.addAndGet(bytes.toLong()) }

    fun incP1Tx(bytes: Int) { _p1TxPackets.incrementAndGet(); _p1TxBytes.addAndGet(bytes.toLong()) }
    fun incP1Rx(bytes: Int) { _p1RxPackets.incrementAndGet(); _p1RxBytes.addAndGet(bytes.toLong()) }

    fun incP2Tx(bytes: Int) { _p2TxPackets.incrementAndGet(); _p2TxBytes.addAndGet(bytes.toLong()) }
    fun incP2Rx(bytes: Int) { _p2RxPackets.incrementAndGet(); _p2RxBytes.addAndGet(bytes.toLong()) }

    fun incBondedTx() { _bondedTxPackets.incrementAndGet() }
    fun incBondedRx() { _bondedRxPackets.incrementAndGet() }

    fun snapshot(): Path3CountersSnapshot = Path3CountersSnapshot(
        p3TxPackets = _p3TxPackets.get(),
        p3RxPackets = _p3RxPackets.get(),
        p3TxBytes = _p3TxBytes.get(),
        p3RxBytes = _p3RxBytes.get(),
        p1TxPackets = _p1TxPackets.get(),
        p1RxPackets = _p1RxPackets.get(),
        p1TxBytes = _p1TxBytes.get(),
        p1RxBytes = _p1RxBytes.get(),
        p2TxPackets = _p2TxPackets.get(),
        p2RxPackets = _p2RxPackets.get(),
        p2TxBytes = _p2TxBytes.get(),
        p2RxBytes = _p2RxBytes.get(),
        bondedTxPackets = _bondedTxPackets.get(),
        bondedRxPackets = _bondedRxPackets.get(),
        timestampMs = System.nanoTime() / 1_000_000L
    )

    fun reset() {
        _p3TxPackets.set(0); _p3RxPackets.set(0); _p3TxBytes.set(0); _p3RxBytes.set(0)
        _p1TxPackets.set(0); _p1RxPackets.set(0); _p1TxBytes.set(0); _p1RxBytes.set(0)
        _p2TxPackets.set(0); _p2RxPackets.set(0); _p2TxBytes.set(0); _p2RxBytes.set(0)
        _bondedTxPackets.set(0); _bondedRxPackets.set(0)
    }
}
