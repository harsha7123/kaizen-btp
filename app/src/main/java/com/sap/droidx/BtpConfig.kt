package com.sap.droidx

/**
 * BTP / Mobile Services connection settings.
 *
 * OAuth goes through the Mobile Services proxy (SERVER_URL/OAuth2/api/v1/…) so that
 * the XSUAA client secret stays server-side and the app never needs to embed it.
 */
object BtpConfig {

    /** Mobile Services API endpoint (used for OData and OAuth proxy). */
    const val SERVER_URL: String =
        "https://f6c0e9f2trial-dev-com-sap-forkqa.cfapps.us10-001.hana.ondemand.com"

    /** The application ID in Mobile Services. */
    const val APPLICATION_ID: String = "com.sap.forkqa"

    /** OAuth2 client id — Mobile Services OAuth client ID (from Security tab in cockpit). */
    const val OAUTH_CLIENT_ID: String = "e80a0af5-cc40-471f-a961-b860b2750d63"

    const val OAUTH_CLIENT_SECRET: String =
        "3748c084-65e2-4c18-9877-7c8da46bc945\$fAx-NLa6yoF9UeiGuKMzMo9y8wBLJQcK7YgIi85ENoQ="

    /** XSUAA base URL. */
    const val XSUAA_BASE_URL: String =
        "https://f6c0e9f2trial.authentication.us10.hana.ondemand.com"

    /** Mobile Services OAuth proxy authorization endpoint. */
    const val AUTH_URL: String = "$SERVER_URL/oauth2/api/v1/authorize"

    /** Mobile Services OAuth proxy token endpoint. */
    const val TOKEN_URL: String = "$SERVER_URL/oauth2/api/v1/token"

    /** Redirect URL registered in Mobile Services and in AndroidManifest. */
    const val REDIRECT_URL: String = "com.sap.forkqa://oauth"

    /** Mobile Services destination name. */
    const val DESTINATION_NAME: String = "com.sap.forkqa"

    /** Direct URL for the ForkQA CAP service (used until Mobile Services destination is configured). */
    const val FORKQA_BACKEND_URL: String = "https://f6c0e9f2trial-dev-forkqa-srv.cfapps.us10-001.hana.ondemand.com"

    /** OData service path — ForkQA CAP service is exposed at /api. */
    const val ODATA_SERVICE_PATH: String = "/api"
}
