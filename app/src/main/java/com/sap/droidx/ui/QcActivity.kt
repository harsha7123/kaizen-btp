package com.sap.droidx.ui

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar
import com.sap.droidx.databinding.ActivityQcBinding

class QcActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQcBinding
    private val vm: QcViewModel by viewModels()
    private var operatorName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQcBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomBar.setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences(QuestionnaireActivity.PREFS, Context.MODE_PRIVATE)
        operatorName = prefs.getString(QuestionnaireActivity.KEY_NAME, "").orEmpty()

        binding.etSourceId.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                loadItem(); true
            } else false
        }

        binding.btnLoadItem.setOnClickListener { loadItem() }
        binding.btnApprove.setOnClickListener  { vm.decide("APPROVED", operatorName) }
        binding.btnReject.setOnClickListener   { vm.decide("REJECTED", operatorName) }
        binding.btnReset.setOnClickListener    { vm.reset(); hideDetail() }

        binding.etSourceId.requestFocus()
        observeViewModel()
    }

    private fun loadItem() {
        val sourceId = binding.etSourceId.text?.toString().orEmpty().trim()
        if (sourceId.isEmpty()) {
            binding.tilSourceId.error = "Scan or enter a Material or HU ID"
            return
        }
        binding.tilSourceId.error = null
        val sourceType = if (binding.chipHu.isChecked) "HU" else "MATERIAL"
        vm.fetchItem(sourceId, sourceType)
    }

    private fun observeViewModel() {
        vm.loading.observe(this) { busy ->
            binding.progressLoad.visibility = if (busy) View.VISIBLE else View.GONE
            binding.btnLoadItem.isEnabled   = !busy
        }

        vm.item.observe(this) { item ->
            if (item == null) return@observe
            binding.tvMaterial.text   = "${item.material}  —  ${item.materialDesc}"
            binding.tvMaterialId.text = item.material
            binding.tvQty.text        = "${item.qty} units"
            binding.tvSupplier.text   = item.supplier.ifEmpty { "—" }
            binding.tvBatch.text      = item.batch.ifEmpty { "—" }
            binding.tvExpiration.text = item.expirationDate.ifEmpty { "—" }
            updateStatusBadge(item.status)
            setActionButtonState(item.status)
            binding.cardDetail.visibility    = View.VISIBLE
            binding.layoutActions.visibility = View.VISIBLE
            binding.btnReset.visibility      = View.VISIBLE
        }

        vm.error.observe(this) { msg ->
            if (msg != null) {
                binding.tilSourceId.error = msg
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

    private fun updateStatusBadge(status: String) {
        binding.tvStatus.text = status
        binding.tvStatus.setBackgroundColor(when (status) {
            "APPROVED" -> Color.parseColor("#2E7D32")
            "REJECTED" -> Color.parseColor("#C62828")
            else       -> Color.parseColor("#FF6F00")
        })
    }

    private fun setActionButtonState(status: String) {
        val decided = status == "APPROVED" || status == "REJECTED"
        binding.btnApprove.isEnabled = !decided
        binding.btnReject.isEnabled  = !decided
    }

    private fun hideDetail() {
        binding.etSourceId.text?.clear()
        binding.cardDetail.visibility    = View.GONE
        binding.layoutActions.visibility = View.GONE
        binding.btnReset.visibility      = View.GONE
        binding.btnApprove.isEnabled     = true
        binding.btnReject.isEnabled      = true
        binding.etSourceId.requestFocus()
    }
}
