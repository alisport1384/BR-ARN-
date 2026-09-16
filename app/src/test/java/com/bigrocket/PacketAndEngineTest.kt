package com.bigrocket

import com.bigrocket.service.SmartTrafficEngine
import org.junit.Assert.assertNotNull
import org.junit.Test

class PacketAndEngineTest {

    @Test
    fun testSmartTrafficEngine_initialization() {
        // Verifies the singleton engine object initializes correctly
        assertNotNull("SmartTrafficEngine should be initialized", SmartTrafficEngine)
    }
}