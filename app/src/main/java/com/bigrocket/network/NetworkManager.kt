package com.bigrocket.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class NetworkManager(
    context: Context,
    private val okHttpClient: OkHttpClient
) {

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            purgeConnections()
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            purgeConnections()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            purgeConnections()
        }
    }

    private fun purgeConnections() {
        // Cancel in-flight calls and drop pooled connections tied to the old network, but do
        // NOT shut down the dispatcher's executor service - that would permanently kill this
        // client after the very first network change.
        okHttpClient.dispatcher.cancelAll()
        okHttpClient.connectionPool.evictAll()
    }

    fun registerNetworkCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }

        connectivityManager.registerNetworkCallback(request, networkCallback)
    }

    fun unregisterNetworkCallback() {
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
    }

    companion object {
        fun createProductionOkHttpClient(context: Context): OkHttpClient {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .connectionPool(ConnectionPool(0, 1, TimeUnit.MILLISECONDS))
                .socketFactory(BoundNetworkSocketFactory(cm))
                .dns(Dns.SYSTEM)
                .build()
        }
    }
}