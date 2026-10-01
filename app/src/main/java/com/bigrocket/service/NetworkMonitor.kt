package com.bigrocket.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

class NetworkMonitor(
    context: Context,
    private val listener: NetworkStateListener
) {
    interface NetworkStateListener {
        fun onNetworksUpdated(wifi: Network?, cellular: Network?)
    }

    companion object {
        /**
         * One-shot snapshot of the current physical Wi-Fi/Cellular Networks, without
         * registering any callback or requiring a running NetworkMonitor/VpnService instance.
         * Used by the identity/IP badge (see DynamicWeightCalculator.preferredIdentityPath):
         * the resting "your real IP" display needs live Network references even while the
         * VPN itself is idle/disconnected. Shares the same transport-detection rules as the
         * instance-based listener below so both paths agree on what counts as Wi-Fi/Cellular.
         */
        fun snapshotPhysicalNetworks(context: Context): Pair<Network?, Network?> {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            var wifi: Network? = null
            var cellular: Network? = null
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> wifi = network
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> cellular = network
                }
            }
            return wifi to cellular
        }
    }

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var wifiNetwork: Network? = null
    private var cellularNetwork: Network? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            AppLogger.log("NetworkMonitor", "onAvailable $network")
            refreshNetwork(network)
        }

        override fun onLost(network: Network) {
            AppLogger.log("NetworkMonitor", "onLost $network (was wifi=${network == wifiNetwork} cellular=${network == cellularNetwork})")
            if (network == wifiNetwork) wifiNetwork = null
            if (network == cellularNetwork) cellularNetwork = null
            refreshAllPhysicalNetworks(notify = true)
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            applyCapabilities(network, networkCapabilities)
        }
    }

    private fun refreshNetwork(network: Network) {
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return
        applyCapabilities(network, caps)
    }

    private fun applyCapabilities(network: Network, caps: NetworkCapabilities) {
        // A VPN/TUN network is never an uplink candidate. Do not require INTERNET here:
        // Android/OEMs may temporarily omit that capability during validation/reconnect.
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
            if (network == wifiNetwork) wifiNetwork = null
            if (network == cellularNetwork) cellularNetwork = null
            listener.onNetworksUpdated(wifiNetwork, cellularNetwork)
            return
        }

        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> wifiNetwork = network
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> cellularNetwork = network
        }

        listener.onNetworksUpdated(wifiNetwork, cellularNetwork)
    }

    private fun refreshAllPhysicalNetworks(notify: Boolean) {
        var foundWifi: Network? = null
        var foundCellular: Network? = null

        for (network in connectivityManager.allNetworks) {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue

            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> foundWifi = network
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> foundCellular = network
            }
        }

        wifiNetwork = foundWifi
        cellularNetwork = foundCellular

        if (notify) listener.onNetworksUpdated(wifiNetwork, cellularNetwork)
    }

    fun startMonitoring() {
        // requestNetwork(), not registerNetworkCallback(): the latter only observes networks
        // that happen to be up for some other reason. On a lot of devices Android will let
        // the cellular radio drop to a low-power/idle state once Wi-Fi is the default network,
        // unless something actively asks to keep it reachable - requestNetwork() is the
        // documented way to do that, and is exactly what we found Hexa Software's NetCombiner
        // does (two separate per-transport requestNetwork() calls - see
        // NetCombiner-Research-FA.md section 3, "NetworkListener.requestNetwork()"). A single
        // combined request can't express "I want both, even if one isn't the current default",
        // so this is two requests, each pinned to one transport, sharing the same callback
        // (unregisterNetworkCallback(networkCallback) below tears down both at once since
        // they're registered against the same callback instance).
        //
        // Trade-off, not a bug: keeping the cellular radio actively reachable costs a bit more
        // battery than letting it idle - that cost is inherent to what this fix buys back
        // (P2 actually being ready when a packet needs it, not waking up on demand).
        val wifiRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val cellularRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .build()

        try {
            connectivityManager.requestNetwork(wifiRequest, networkCallback)
            connectivityManager.requestNetwork(cellularRequest, networkCallback)
            AppLogger.log("NetworkMonitor", "requestNetwork() registered for wifi + cellular")
        } catch (e: Exception) {
            // Most likely SecurityException (missing permission) or the system-wide
            // outstanding-request quota - fall back to passive observation rather than have
            // no network visibility at all.
            AppLogger.logError(
                "NetworkMonitor",
                "requestNetwork() failed, falling back to registerNetworkCallback()",
                e,
            )
            val fallback = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            try {
                connectivityManager.registerNetworkCallback(fallback, networkCallback)
            } catch (_: Exception) {
                return
            }
        }

        refreshAllPhysicalNetworks(notify = true)
    }

    fun stopMonitoring() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        wifiNetwork = null
        cellularNetwork = null
    }
}
