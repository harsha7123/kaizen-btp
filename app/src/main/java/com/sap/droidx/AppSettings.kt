package com.sap.droidx

import android.content.Context
import com.sap.cloud.mobile.foundation.authentication.OAuth2Configuration
import com.sap.cloud.mobile.foundation.common.SettingsParameters

/**
 * Tiny in-memory / SharedPreferences holder for the SDK bootstrap values so
 * UI components can reach them without re-reading [BtpConfig] each time.
 */
object AppSettings {

    @Volatile var settingsParameters: SettingsParameters? = null
        private set

    @Volatile var oauthConfig: OAuth2Configuration? = null
        private set

    fun save(context: Context, sp: SettingsParameters, oauth: OAuth2Configuration) {
        settingsParameters = sp
        oauthConfig = oauth
    }

    fun require(): SettingsParameters =
        settingsParameters ?: error("SDK not initialized – DroidXApplication.onCreate did not run")
}
