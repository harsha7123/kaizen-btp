package com.sap.droidx

import android.app.Application
import android.content.Context
import ch.qos.logback.classic.Level
import com.sap.cloud.mobile.foundation.common.ClientProvider
import com.sap.cloud.mobile.foundation.common.SettingsParameters
import com.sap.cloud.mobile.foundation.common.SettingsProvider
import com.sap.cloud.mobile.foundation.logging.Logging
import com.sap.cloud.mobile.foundation.authentication.OAuth2Configuration
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class DroidXApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        val logLevel = if (BuildConfig.DEBUG) Level.DEBUG else Level.WARN
        Logging.initialize(this, Logging.ConfigurationBuilder()
            .initialLevel(logLevel)
            .logToConsole(BuildConfig.DEBUG)
            .build())

        // WebView debugging only in debug builds
        if (BuildConfig.DEBUG) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }

        val settings = SettingsParameters(
            BtpConfig.SERVER_URL,
            BtpConfig.APPLICATION_ID,
            deviceId(),
            BuildConfig.VERSION_NAME
        )
        SettingsProvider.set(settings)

        // The Mobile Services OAuth proxy handles client_secret server-side.
        // The app only needs a standard OkHttpClient — no secret injection required.
        val http = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        ClientProvider.set(http)

        val oauth = OAuth2Configuration.Builder(this)
            .clientId(BtpConfig.OAUTH_CLIENT_ID)
            .responseType("code")
            .authUrl(BtpConfig.AUTH_URL)
            .tokenUrl(BtpConfig.TOKEN_URL)
            .redirectUrl(BtpConfig.REDIRECT_URL)
            .build()

        // SDKInitializer.start() is intentionally NOT called: it registers a
        // ProcessLifecycleOwner observer that triggers re-authentication every
        // time the app comes back to foreground (e.g. after camera), causing an
        // unwanted logon screen.
        AppSettings.save(this, settings, oauth)
    }

    private fun deviceId(): String {
        val prefs = getSharedPreferences("droidx", MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    companion object {
        lateinit var appContext: Context
            private set
    }
}
