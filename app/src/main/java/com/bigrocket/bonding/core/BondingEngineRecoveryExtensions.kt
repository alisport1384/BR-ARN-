package com.bigrocket.bonding.core

import com.bigrocket.bonding.frame.Frame
import com.bigrocket.bonding.recovery.RecoveryManager
import com.bigrocket.bonding.recovery.SelectiveRecoveryManager

/** Section 216/328: exposes just enough for a peer engine (or, today, the sandbox harness
 *  standing in for one - see [com.bigrocket.bonding.recovery.RecoveryManager]'s class doc) to
 *  build a [RecoveryManager] that can satisfy resend requests from this engine's own recently
 *  sent frames, without exposing the [RetransmitCache][com.bigrocket.bonding.recovery.RetransmitCache]
 *  object itself. */
fun BondingEngineImpl.createRecoveryManagerFor(onResend: (Frame) -> Unit): RecoveryManager =
    SelectiveRecoveryManager(retransmitCacheForRecovery(), onResend)
