package com.sap.droidx.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.sap.droidx.data.ProductionOrder
import com.sap.droidx.data.ProductionRepository
import com.sap.droidx.databinding.ActivityVoiceGrBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class VoiceGrActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVoiceGrBinding
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var pulseAnimator: ObjectAnimator? = null

    private val prodRepo = ProductionRepository()

    private enum class State { IDLE, PROCESSING, CONFIRM, DONE, ERROR }
    private var state = State.IDLE
    private var pendingOrder: ProductionOrder? = null

    // Listens for "confirm" / "yes" after the order is displayed
    private val confirmLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val heard = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
        if (heard.isBlank()) {
            // Nothing heard — stay on confirm card, prompt user to tap YES or retry
            if (state == State.CONFIRM) {
                binding.tvVoiceState.text = "Couldn't hear — tap YES or mic to retry"
                binding.tvVoiceState.setTextColor(0xFFFFCA28.toInt())
            }
        } else {
            processConfirmation(heard)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceGrBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.getDefault()
                ttsReady = true
                // After TTS finishes the confirm prompt, auto-open the microphone
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String) {}
                    override fun onDone(id: String) {
                        if (id == "confirm_prompt") runOnUiThread { startConfirmListening() }
                    }
                    override fun onError(id: String) {}
                })
            }
        }

        setState(State.IDLE, "Tap microphone to post a Goods Receipt")

        binding.fabMic.setOnClickListener {
            when (state) {
                State.IDLE, State.ERROR, State.DONE -> fetchAndPrompt()
                State.CONFIRM -> startConfirmListening()
                else -> {}
            }
        }
        binding.btnConfirmYes.setOnClickListener { postGR() }
        binding.btnConfirmNo.setOnClickListener {
            pendingOrder = null
            setState(State.IDLE, "Tap microphone to post a Goods Receipt")
        }
        binding.btnVoiceBack.setOnClickListener { finish() }
    }

    // Fetch the first OPEN order then speak the confirm prompt
    private fun fetchAndPrompt() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setState(State.ERROR, "Speech recognition not available on this device.")
            return
        }
        setState(State.PROCESSING, "Fetching next order…")
        lifecycleScope.launch {
            try {
                val order = withContext(Dispatchers.IO) { prodRepo.fetchFirstOpenOrder() }
                if (order == null) {
                    setState(State.ERROR, "No open production orders found.")
                    return@launch
                }
                pendingOrder = order
                val openQty = order.plannedQty - order.receivedQty
                binding.tvOrderDetails.text = buildString {
                    appendLine("Order:    ${order.orderId}")
                    appendLine("Material: ${order.material}  ${order.materialDesc}")
                    appendLine("Planned:  ${order.plannedQty}   Received: ${order.receivedQty}")
                    append    ("Open:     $openQty   Door: ${order.door}")
                }
                binding.tvHeard.text = order.orderId
                setState(State.CONFIRM, "Say \"confirm\" to post GR for ${order.orderId}")
                // TTS UtteranceProgressListener opens mic when this finishes
                speak(
                    "Please say confirm to process goods receipt for Order ${order.orderId}",
                    id = "confirm_prompt"
                )
            } catch (e: Exception) {
                setState(State.ERROR, "Could not fetch order: ${e.message}")
            }
        }
    }

    private fun startConfirmListening() {
        if (state != State.CONFIRM) return
        confirmLauncher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say CONFIRM to post GR or CANCEL")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        })
    }

    private fun processConfirmation(heard: String) {
        if (heard.isBlank()) return
        val lower = heard.lowercase()
        val yes = listOf("confirm", "yes", "yeah", "yep", "ok", "sure", "post", "correct")
        val no  = listOf("no", "nope", "cancel", "stop", "abort", "back")
        when {
            yes.any { lower.contains(it) } -> postGR()
            no.any  { lower.contains(it) } -> {
                pendingOrder = null
                setState(State.IDLE, "Tap microphone to post a Goods Receipt")
                speak("Cancelled.")
            }
            else -> speak(
                "Please say confirm to post, or cancel to abort.",
                id = "confirm_prompt"
            )
        }
    }

    private fun postGR() {
        val order = pendingOrder ?: return
        setState(State.PROCESSING, "Posting GR…")
        lifecycleScope.launch {
            try {
                val prefs    = getSharedPreferences(QuestionnaireActivity.PREFS, Context.MODE_PRIVATE)
                val operator = prefs.getString(QuestionnaireActivity.KEY_NAME, "Operator") ?: "Operator"
                val grResult = withContext(Dispatchers.IO) {
                    prodRepo.createGR(orderId = order.orderId, qty = 1, grBy = operator)
                }
                pendingOrder = null
                setState(State.DONE, "Transaction Posted")
                binding.tvResultDetail.text = "Order: ${order.orderId}  ·  HU: ${grResult.huId}"
                speak("Transaction posted. Order ${order.orderId}.")
            } catch (e: Exception) {
                setState(State.ERROR, "GR failed: ${e.message}")
            }
        }
    }

    private fun setState(newState: State, message: String) {
        state = newState
        binding.progressVoice.visibility = View.GONE
        binding.cardResult.visibility    = View.GONE
        binding.cardPending.visibility   = View.GONE
        binding.fabContainer.visibility  = View.VISIBLE
        binding.tvHeard.visibility       = View.GONE
        binding.fabMic.isEnabled         = true
        binding.fabMic.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFF1565C0.toInt())

        when (newState) {
            State.IDLE -> {
                binding.tvVoiceState.text = message
                binding.tvVoiceState.setTextColor(0xFF90A4AE.toInt())
                stopPulse()
            }
            State.PROCESSING -> {
                binding.tvVoiceState.text = message
                binding.tvVoiceState.setTextColor(0xFFFFCA28.toInt())
                binding.progressVoice.visibility = View.VISIBLE
                binding.fabContainer.visibility  = View.GONE
                binding.fabMic.isEnabled         = false
                stopPulse()
            }
            State.CONFIRM -> {
                binding.tvVoiceState.text = message
                binding.tvVoiceState.setTextColor(0xFF9FA8DA.toInt())
                binding.cardPending.visibility  = View.VISIBLE
                binding.tvHeard.visibility      = View.VISIBLE
                // FAB stays visible — user taps it when ready to say "confirm"
                binding.fabMic.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(0xFF2E7D32.toInt())
                stopPulse()
            }
            State.DONE -> {
                binding.tvVoiceState.text = "Done"
                binding.tvVoiceState.setTextColor(0xFF66BB6A.toInt())
                binding.cardResult.visibility = View.VISIBLE
                binding.tvResultTitle.text    = message
                binding.cardResult.setCardBackgroundColor(0xFF1B5E20.toInt())
                stopPulse()
            }
            State.ERROR -> {
                binding.tvVoiceState.text = message
                binding.tvVoiceState.setTextColor(0xFFEF5350.toInt())
                stopPulse()
            }
        }
    }

    private fun startPulse() {
        pulseAnimator?.cancel()
        binding.vPulse.alpha = 0f
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.5f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.5f)
        val alpha  = PropertyValuesHolder.ofFloat(View.ALPHA, 0.6f, 0f)
        pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(binding.vPulse, scaleX, scaleY, alpha).apply {
            duration = 900
            repeatCount = ObjectAnimator.INFINITE
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) {
                    binding.vPulse.scaleX = 1f; binding.vPulse.scaleY = 1f
                }
            })
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        binding.vPulse.alpha = 0f
    }

    private fun speak(text: String, id: String = "vgr_${System.currentTimeMillis()}") {
        if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    override fun onDestroy() {
        tts.stop(); tts.shutdown()
        super.onDestroy()
    }
}
