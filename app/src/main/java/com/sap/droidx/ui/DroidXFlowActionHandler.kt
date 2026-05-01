package com.sap.droidx.ui

import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.LaunchedEffect
import com.sap.cloud.mobile.flows.compose.core.FlowContextRegistry
import com.sap.cloud.mobile.flows.compose.ext.CustomStepInsertionPoint
import com.sap.cloud.mobile.flows.compose.ext.FlowActionHandler
import com.sap.cloud.mobile.flows.compose.flows.BaseFlow
import com.sap.cloud.mobile.foundation.model.AppConfig
import com.sap.cloud.mobile.foundation.model.AbstractOAuthClient
import com.sap.cloud.mobile.foundation.model.OAuth
import com.sap.cloud.mobile.foundation.model.OAuthClient
import com.sap.cloud.mobile.foundation.model.OAuthConfig
import com.sap.droidx.BtpConfig

class DroidXFlowActionHandler(private val appConfig: AppConfig) : FlowActionHandler() {

    override suspend fun activateFromManagedConfig(bundle: Bundle): AppConfig = appConfig

    override fun getFlowCustomizationSteps(flow: BaseFlow, insertionPoint: CustomStepInsertionPoint) {
        when (insertionPoint) {
            is CustomStepInsertionPoint.BeforeActivation -> {
                flow.addSingleStep("droidx_activate", false) { _ ->
                    LaunchedEffect(Unit) {
                        Log.d("DroidX-OAuthPatch", "BeforeActivation: setting AppConfig")
                        flow.updateAppConfigBeforeActivation(appConfig)
                        flow.flowDone("droidx_activate")
                    }
                }
            }
            is CustomStepInsertionPoint.BeforeAuthentication -> {
                // AuthenticationFlow.prepare() reads AppConfig via
                // FlowContextRegistry.flowContext.appConfig. By the time we reach here,
                // the SDK has overwritten that AppConfig's auth list with the server-returned
                // accounts.sap.com config. We patch the auth list back to our direct-XSUAA
                // config. AppConfig.auth is private non-final — safe to set via reflection.
                flow.addSingleStep("droidx_pre_auth", false) { _ ->
                    LaunchedEffect(Unit) {
                        Log.d("DroidX-OAuthPatch", "BeforeAuthentication: patching FlowContext AppConfig to direct XSUAA")
                        try {
                            val ctx = FlowContextRegistry.flowContext
                            if (ctx != null) {
                                val existingConfig = ctx.appConfig
                                val authField = AppConfig::class.java.getDeclaredField("auth")
                                authField.isAccessible = true
                                authField.set(existingConfig, appConfig.auth)
                                Log.d("DroidX-OAuthPatch", "BeforeAuthentication: auth list patched — endpoint=${existingConfig.primaryAuthenticationConfig?.let {
                                    (it as? com.sap.cloud.mobile.foundation.model.OAuth)?.config?.authorizationEndpoint
                                }}")
                            } else {
                                Log.w("DroidX-OAuthPatch", "BeforeAuthentication: FlowContext is null")
                            }
                        } catch (e: Exception) {
                            Log.e("DroidX-OAuthPatch", "BeforeAuthentication: patch failed", e)
                        }
                        flow.flowDone("droidx_pre_auth")
                    }
                }
            }
            else -> { /* no-op */ }
        }
    }

    override fun getEffectiveOAuthClient(oAuthConfig: OAuthConfig): AbstractOAuthClient {
        return OAuthClient.Builder()
            .clientID(BtpConfig.OAUTH_CLIENT_ID)
            .redirectURL(BtpConfig.REDIRECT_URL)
            .grantType(OAuth.GRANT_TYPE_AUTHORIZATION_CODE)
            .build()
    }
}
