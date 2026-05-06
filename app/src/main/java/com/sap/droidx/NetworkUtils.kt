package com.sap.droidx

import android.net.ConnectivityManager
import android.net.NetworkCapabilities

object NetworkUtils {

    fun isOnline(): Boolean {
        val cm = DroidXApplication.appContext
            .getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps    = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun requireOnline() {
        if (!isOnline()) throw IllegalStateException("No network connection. Check Wi-Fi or mobile data.")
    }
}
