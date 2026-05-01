package com.sap.droidx

import android.app.Application
import ch.qos.logback.classic.Level
import com.sap.cloud.mobile.foundation.common.ClientProvider
import com.sap.cloud.mobile.foundation.common.SettingsParameters
import com.sap.cloud.mobile.foundation.common.SettingsProvider
import com.sap.cloud.mobile.foundation.logging.Logging
import com.sap.cloud.mobile.foundation.authentication.OAuth2Configuration
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class DroidXApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        Logging.initialize(this, Logging.ConfigurationBuilder()
            .initialLevel(Level.DEBUG)
            .logToConsole(true)
            .build())

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }

        val settings = SettingsParameters(
            BtpConfig.SERVER_URL,
            BtpConfig.APPLICATION_ID,
            deviceId(),
            "1.0"
        )
        SettingsProvider.set(settings)

        // Inject Basic auth on token endpoint requests so XSUAA accepts the client.
        val http = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.method == "POST" && req.url.encodedPath.endsWith("/oauth/token")) {
                    chain.proceed(
                        req.newBuilder()
                            .header("Authorization", Credentials.basic(
                                BtpConfig.OAUTH_CLIENT_ID,
                                BtpConfig.OAUTH_CLIENT_SECRET
                            ))
                            .build()
                    )
                } else {
                    chain.proceed(req)
                }
            }
            .build()
        ClientProvider.set(http)

        val oauth = OAuth2Configuration.Builder(this)
            .clientId(BtpConfig.OAUTH_CLIENT_ID)
            .secret(BtpConfig.OAUTH_CLIENT_SECRET)
            .responseType("code")
            .authUrl(BtpConfig.AUTH_URL)
            .tokenUrl(BtpConfig.TOKEN_URL)
            .redirectUrl(BtpConfig.REDIRECT_URL)
            .build()

        // SDKInitializer.start() is intentionally NOT called: it registers a
        // ProcessLifecycleOwner observer that triggers re-authentication every
        // time the app comes back to foreground (e.g. after camera), causing an
        // unwanted logon screen. We don't use any Mobile Services at runtime, so
        // there is nothing to initialize.
        AppSettings.save(this, settings, oauth)
    }

    private fun deviceId(): String {
        val prefs = getSharedPreferences("droidx", MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }
}
