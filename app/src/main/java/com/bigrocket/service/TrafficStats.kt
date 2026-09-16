package com.bigrocket.service

import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks real bytes forwarded by [TcpRelayEngine] and [UdpRelayEngine] in both directions and
 * reports throughput (Mbps) measured between calls to [sampleThroughputMbps]. This is a passive
 * measurement of actual traffic - not an active bandwidth probe - so it costs no extra data and
 * always reflects what the bonding engine is genuinely doing right now (it reads 0 when idle).
 */
object TrafficStats {

    private val totalBytes = AtomicLong(0)
    private var lastSampleBytes = 0L
    private var lastSampleTimeMs = System.currentTimeMillis()

    fun recordBytes(count: Int) {
        if (count > 0) totalBytes.addAndGet(count.toLong())
    }

    /**
     * Returns the combined throughput (Mbps) observed since the previous call.
     * Safe to call from any thread; not reentrant-precise under concurrent callers,
     * but callers are expected to be a single periodic sampler.
     */
    fun sampleThroughputMbps(): Double {
        val now = System.currentTimeMillis()
        val currentTotal = totalBytes.get()

        val elapsedMs = (now - lastSampleTimeMs).coerceAtLeast(1)
        val deltaBytes = (currentTotal - lastSampleBytes).coerceAtLeast(0)

        lastSampleBytes = currentTotal
        lastSampleTimeMs = now

        val bytesPerSecond = deltaBytes * 1000.0 / elapsedMs
        return (bytesPerSecond * 8.0) / 1_000_000.0
    }

    fun reset() {
        totalBytes.set(0)
        lastSampleBytes = 0
        lastSampleTimeMs = System.currentTimeMillis()
    }
}
