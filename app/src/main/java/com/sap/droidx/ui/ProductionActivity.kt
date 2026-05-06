package com.sap.droidx.ui

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.sap.droidx.databinding.ActivityProductionBinding

class ProductionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProductionBinding
    private val vm: ProductionViewModel by viewModels()
    private var operatorName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProductionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomBar.setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences(QuestionnaireActivity.PREFS, Context.MODE_PRIVATE)
        operatorName = prefs.getString(QuestionnaireActivity.KEY_NAME, "").orEmpty()

        binding.etOrderId.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                loadOrder(); true
            } else false
        }
        binding.btnLoadOrder.setOnClickListener { loadOrder() }
        binding.btnGr.setOnClickListener        { vm.doGR(operatorName) }
        binding.btnGrShip.setOnClickListener    { vm.doGRAndShip(operatorName) }
        binding.btnReset.setOnClickListener     { vm.reset(); hideOrderUi() }

        binding.etOrderId.requestFocus()
        observeViewModel()
    }

    private fun loadOrder() {
        val orderId = binding.etOrderId.text?.toString().orEmpty().trim()
        if (orderId.isEmpty()) {
            binding.tilOrderId.error = "Scan or enter a Production Order ID"
            return
        }
        binding.tilOrderId.error = null
        vm.fetchOrder(orderId)
    }

    private fun observeViewModel() {
        vm.loading.observe(this) { busy ->
            binding.progressLoad.visibility = if (busy) View.VISIBLE else View.GONE
            binding.btnLoadOrder.isEnabled  = !busy
            if (!busy) updateGrButtonState()
        }

        vm.order.observe(this) { order ->
            if (order == null) return@observe
            binding.tvMaterial.text  = "${order.material}  —  ${order.materialDesc}"
            binding.tvBatch.text     = order.batch.ifEmpty { "—" }
            binding.tvDoor.text      = order.door.ifEmpty { "—" }
            binding.tvShipTo.text    = order.shipTo.ifEmpty { "—" }
            binding.tvPerPallet.text = "${order.palletQty} units/pallet"
            binding.cardOrderInfo.visibility = View.VISIBLE
            binding.cardStats.visibility     = View.VISIBLE
            binding.btnGr.visibility         = View.VISIBLE
            binding.btnGrShip.visibility     = View.VISIBLE
            binding.btnReset.visibility      = View.VISIBLE
        }

        vm.sessionGrCount.observe(this)  { binding.tvPalletsReceived.text = it.toString() }

        vm.sessionReceived.observe(this) { received ->
            val planned = vm.order.value?.plannedQty ?: 0
            binding.tvOpenQty.text     = maxOf(0, planned - received).toString()
            binding.tvPerPalletStat.text = vm.order.value?.palletQty?.toString() ?: "—"
            updateGrButtonState()
        }

        vm.lastHuId.observe(this) { huId ->
            if (huId != null) {
                binding.tvLastHu.text = huId
                binding.cardLastHu.visibility = View.VISIBLE
            }
        }

        vm.error.observe(this) { msg ->
            if (msg != null) {
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
                vm.clearError()
            }
        }

        vm.message.observe(this) { msg ->
            if (msg != null) {
                Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
                vm.clearMessage()
            }
        }
    }

    private fun updateGrButtonState() {
        val order    = vm.order.value ?: return
        val received = vm.sessionReceived.value ?: 0
        val done     = received >= order.plannedQty
        binding.btnGr.isEnabled = !done && (vm.loading.value != true)
        if (done) binding.btnGr.text = "All Received"
    }

    private fun hideOrderUi() {
        binding.etOrderId.text?.clear()
        binding.cardOrderInfo.visibility = View.GONE
        binding.cardStats.visibility     = View.GONE
        binding.cardLastHu.visibility    = View.GONE
        binding.btnGr.visibility         = View.GONE
        binding.btnGrShip.visibility     = View.GONE
        binding.btnReset.visibility      = View.GONE
        binding.btnGr.text = "GR — Goods Receipt"
        binding.etOrderId.requestFocus()
    }
}
