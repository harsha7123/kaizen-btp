package com.sap.droidx.ui

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import androidx.appcompat.app.AppCompatActivity
import com.sap.cloud.mobile.flows.compose.core.FlowContext
import com.sap.cloud.mobile.flows.compose.core.FlowContextRegistry
import com.sap.cloud.mobile.flows.compose.flows.FlowType
import com.sap.cloud.mobile.flows.compose.flows.FlowUtil
import com.sap.cloud.mobile.flows.compose.ext.ActivationOption
import com.sap.cloud.mobile.flows.compose.ext.FlowOptions
import com.sap.cloud.mobile.flows.compose.ext.FlowStateListener
import com.sap.cloud.mobile.flows.compose.ext.OAuthOption
import com.sap.cloud.mobile.foundation.model.AppConfig
import com.sap.cloud.mobile.foundation.model.OAuth
import com.sap.cloud.mobile.foundation.model.OAuthClient
import com.sap.cloud.mobile.foundation.model.OAuthConfig
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.sap.droidx.BtpConfig
import com.sap.droidx.R
import java.net.URL
import kotlin.math.PI
import kotlin.math.sin

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()   // consumes the system splash; theme switches to postSplashScreenTheme
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        playChime()

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) proceed()
        }, 2000)
    }

    private fun proceed() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_ONBOARDED, false)) {
            installNoOpFlowContext()
            startActivity(Intent(this, QuestionnaireActivity::class.java))
            finish()
            return
        }

        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()

        val myAppConfig = buildAppConfig()

        val flowContext = FlowContext(
            appConfig = myAppConfig,
            flowType = FlowType.Onboarding,
            flowOptions = FlowOptions(
                activationOption = ActivationOption.MDM_ONLY,
                oAuthOption = OAuthOption(enablePKCE = true)
            ),
            flowStateListener = DroidXFlowListener(
                onDone = { markDoneAndProceed(myAppConfig) },
                onCancel = { finish() }
            ),
            flowActionHandler = DroidXFlowActionHandler(myAppConfig)
        )

        FlowContextRegistry.flowContext = flowContext
        FlowUtil.startFlow(
            this,
            flowContext,
            { _ -> markDoneAndProceed(myAppConfig) },
            { _: Int, _: android.content.Intent? -> finish() }
        )
    }

    private fun markDoneAndProceed(appConfig: AppConfig) {
        Log.d("DroidX", "Onboarding DONE → launching Questionnaire")
        installNoOpFlowContext(appConfig)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(KEY_ONBOARDED, true).apply()
        startActivity(Intent(this, QuestionnaireActivity::class.java))
        finish()
    }

    private fun installNoOpFlowContext(appConfig: AppConfig = buildAppConfig()) {
        FlowContextRegistry.flowContext = FlowContext(
            appConfig = appConfig,
            flowType = FlowType.Restore,
            flowStateListener = object : FlowStateListener() {
                override suspend fun onFlowFinished(flowName: String?) { }
                override suspend fun onFlowFinishedWithData(flowName: String?, data: Intent?) { }
            }
        )
    }

    private fun buildAppConfig(): AppConfig {
        val serverUrl = URL(BtpConfig.SERVER_URL)

        val client = OAuthClient.Builder()
            .clientID(BtpConfig.OAUTH_CLIENT_ID)
            .redirectURL(BtpConfig.REDIRECT_URL)
            .grantType(OAuth.GRANT_TYPE_AUTHORIZATION_CODE)
            .build()

        val config = OAuthConfig.Builder()
            .authorizationEndpoint(BtpConfig.AUTH_URL)
            .tokenEndpoint(BtpConfig.TOKEN_URL)
            .addClient(client)
            .build()

        val auth = OAuth.Builder()
            .config(config)
            .build()

        return AppConfig.Builder()
            .protocol(serverUrl.protocol)
            .host(serverUrl.host)
            .port(if (serverUrl.port == -1) serverUrl.defaultPort else serverUrl.port)
            .applicationId(BtpConfig.APPLICATION_ID)
            .addAuth(auth)
            .build()
    }

    // Synthesises a 3-note ascending chime (D5 → F#5 → A5) using AudioTrack.
    // Runs on a background thread so it never blocks the UI.
    private fun playChime() {
        Thread {
            val sampleRate = 44100
            val notes      = intArrayOf(587, 740, 880)   // D5, F#5, A5
            val durations  = intArrayOf(150, 150, 350)   // ms per note

            for (i in notes.indices) {
                val freq     = notes[i]
                val nSamples = sampleRate * durations[i] / 1000
                val buf      = ShortArray(nSamples)

                for (j in 0 until nSamples) {
                    val t   = j.toDouble() / sampleRate
                    val env = when {
                        j < nSamples * 0.05 -> j / (nSamples * 0.05)        // 5 ms attack
                        j > nSamples * 0.55 -> (nSamples - j).toDouble() / (nSamples * 0.45) // decay
                        else                -> 1.0
                    }
                    buf[j] = (sin(2.0 * PI * freq * t) * env * Short.MAX_VALUE * 0.45).toInt().toShort()
                }

                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(buf.size * 2, minBuf))
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(buf, 0, buf.size)
                track.play()
                Thread.sleep(durations[i].toLong())
                track.stop()
                track.release()
            }
        }.start()
    }

    companion object {
        private const val PREFS         = "droidx_splash"
        private const val KEY_ONBOARDED = "onboarded"
    }
}
