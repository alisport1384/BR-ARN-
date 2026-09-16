package com.bigrocket.bonding.path3

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue

/**
 * Section 70/213/214/215/216: TUN-based Path3 implementation
 *
 * Real production Path3 that uses Android's VPN TUN interface.
 * This is the network-capable endpoint that Android can actually route through.
 *
 * Contract:
 * Android Apps -> TUN read -> Path3 -> Bonding Engine -> P1/P2 -> Internet
 * Internet -> P1/P2 -> Bonding Engine -> Path3 -> TUN write -> Android
 *
 * Ownership: TunPath3Endpoint owns TUN FD read/write (Section 215)
 * Lifecycle: Create -> Configure -> Validate -> Attach Routing -> ACTIVE (Section 216)
 *
 * This class does NOT contain bonding logic (Section 71: TUN != Bonding)
 * It only provides packet boundary.
 */
class TunPath3Endpoint(
    private val vpnInterface: ParcelFileDescriptor,
    override val mtu: Int = 1400
) : Path3Endpoint {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var readJob: Job? = null

    private val androidToBondingQueue = LinkedBlockingQueue<ByteArray>(1024)
    private val bondingToAndroidQueue = LinkedBlockingQueue<ByteArray>(1024)

    @Volatile
    private var currentState: Path3State = Path3State.CREATED

    override val counters = Path3Counters()

    @Volatile
    private var inputStream: FileInputStream? = null

    @Volatile
    private var outputStream: FileOutputStream? = null

    override fun start() {
        if (!currentState.canTransitionTo(Path3State.INITIALIZING)) return
        currentState = Path3State.INITIALIZING

        try {
            inputStream = FileInputStream(vpnInterface.fileDescriptor)
            outputStream = FileOutputStream(vpnInterface.fileDescriptor)

            // Section 282: Verify interface exists and is configured
            if (vpnInterface.fileDescriptor.valid().not()) {
                currentState = Path3State.FAILED
                return
            }

            currentState = Path3State.READY

            // Start TUN read loop (Android -> Bonding)
            readJob = scope.launch {
                val buffer = ByteBuffer.allocate(32767)
                while (isActive && currentState != Path3State.STOPPED) {
                    try {
                        val readBytes = inputStream?.read(buffer.array()) ?: -1
                        if (readBytes > 0) {
                            buffer.limit(readBytes)
                            val packet = ByteArray(readBytes)
                            System.arraycopy(buffer.array(), 0, packet, 0, readBytes)
                            // Section 47: Capture boundary - this is where Android traffic enters Path3
                            if (!androidToBondingQueue.offer(packet)) {
                                // Bounded queue full - drop to avoid memory exhaustion
                            } else {
                                counters.incP3Tx(packet.size)
                            }
                        }
                    } catch (_: Exception) {
                        // Transient TUN read error - keep loop alive unless stopped
                        if (currentState == Path3State.STOPPED) break
                    } finally {
                        buffer.clear()
                    }
                }
            }

            currentState = Path3State.ACTIVE
        } catch (e: Exception) {
            currentState = Path3State.FAILED
        }
    }

    override fun stop() {
        if (currentState == Path3State.STOPPED) return
        currentState = Path3State.STOPPING

        readJob?.cancel()
        readJob = null
        scope.cancel()

        androidToBondingQueue.clear()
        bondingToAndroidQueue.clear()

        try {
            inputStream?.close()
        } catch (_: Exception) {}
        try {
            outputStream?.close()
        } catch (_: Exception) {}

        inputStream = null
        outputStream = null
        currentState = Path3State.STOPPED
    }

    override fun state(): Path3State = currentState
    override fun isActive(): Boolean = currentState == Path3State.ACTIVE

    override fun injectFromAndroid(packet: ByteArray): Boolean {
        // For TUN endpoint, injection from Android happens via TUN FD read loop,
        // not via this method. This method is for virtual/testing only.
        // But we support it for uniformity.
        if (!isActive()) return false
        return androidToBondingQueue.offer(packet.copyOf()).also {
            if (it) counters.incP3Tx(packet.size)
        }
    }

    override fun receiveFromAndroid(): ByteArray? = androidToBondingQueue.poll()

    override fun sendToAndroid(packet: ByteArray): Boolean {
        if (!isActive()) return false
        if (packet.isEmpty() || packet.size > mtu) return false

        // Write directly to TUN (Bonding -> Android)
        return try {
            outputStream?.write(packet)
            outputStream?.flush()
            counters.incP3Rx(packet.size)
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun readForAndroid(): ByteArray? {
        // For TUN endpoint, Android reads via kernel TUN, not via this queue.
        // This is for virtual endpoint compatibility.
        return bondingToAndroidQueue.poll()
    }

    override fun isInputQueueFull(): Boolean = androidToBondingQueue.remainingCapacity() == 0
    override fun isOutputQueueFull(): Boolean = bondingToAndroidQueue.remainingCapacity() == 0
}
