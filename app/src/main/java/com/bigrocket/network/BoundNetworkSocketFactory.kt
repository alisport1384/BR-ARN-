package com.bigrocket.network

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

class BoundNetworkSocketFactory(
    private val connectivityManager: ConnectivityManager
) : SocketFactory() {

    private fun getActivePhysicalNetwork(): Network? {
        val active = connectivityManager.activeNetwork
        val activeCaps = active?.let { connectivityManager.getNetworkCapabilities(it) }
        if (active != null && activeCaps != null &&
            activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
            return active
        }

        // When the app's active network is the BigRocket VPN, explicitly select a physical
        // network instead of falling back to the default SocketFactory (which would re-enter
        // the VPN/TUN and create a routing loop).
        return connectivityManager.allNetworks.firstOrNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network)
            caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                 caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        }
    }

    private fun getSocketFactory(): SocketFactory {
        return getActivePhysicalNetwork()?.socketFactory ?: getDefault()
    }

    override fun createSocket(): Socket = getSocketFactory().createSocket()

    override fun createSocket(host: String, port: Int): Socket =
        getSocketFactory().createSocket(host, port)

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        getSocketFactory().createSocket(host, port, localHost, localPort)

    override fun createSocket(host: InetAddress, port: Int): Socket =
        getSocketFactory().createSocket(host, port)

    override fun createSocket(
        host: InetAddress,
        port: Int,
        localHost: InetAddress,
        localPort: Int
    ): Socket = getSocketFactory().createSocket(host, port, localHost, localPort)
}
