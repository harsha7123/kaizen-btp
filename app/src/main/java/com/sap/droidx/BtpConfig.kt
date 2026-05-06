package com.sap.droidx

/**
 * BTP / Mobile Services connection settings.
 *
 * All values come from BuildConfig fields which are injected at build time from local.properties.
 * No secrets or environment-specific URLs are hardcoded in this file.
 *
 * To set up a new environment, add the following keys to local.properties (not committed to VCS):
 *   btp.server.url          — Mobile Services app proxy URL
 *   btp.xsuaa.base.url      — XSUAA base URL (https://<subdomain>.authentication.<region>.hana.ondemand.com)
 *   btp.backend.url         — Direct CAP service URL
 *   btp.oauth.client.id     — OAuth client ID from Mobile Services → Security tab
 *   btp.oauth.client.secret — OAuth client secret (Mobile Services proxy handles this server-side in production)
 */
object BtpConfig {

    val SERVER_URL:      String get() = BuildConfig.BTP_SERVER_URL
    val XSUAA_BASE_URL:  String get() = BuildConfig.BTP_XSUAA_BASE_URL
    val FORKQA_BACKEND_URL: String get() = BuildConfig.BTP_BACKEND_URL
    val OAUTH_CLIENT_ID: String get() = BuildConfig.OAUTH_CLIENT_ID

    const val APPLICATION_ID   = "com.sap.forkqa"
    const val REDIRECT_URL     = "com.sap.forkqa://oauth"
    const val DESTINATION_NAME = "com.sap.forkqa"
    const val ODATA_SERVICE_PATH = "/api"

    val AUTH_URL:  String get() = "$SERVER_URL/oauth2/api/v1/authorize"
    val TOKEN_URL: String get() = "$SERVER_URL/oauth2/api/v1/token"
}
