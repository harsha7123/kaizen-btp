package com.sap.droidx.ui

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.NumberPicker
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.sap.droidx.data.PackingRepository
import com.sap.droidx.databinding.ActivityPackingBinding
import kotlinx.coroutines.launch

class PackingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPackingBinding
    private val repo = PackingRepository()

    private var sourceType = "MATERIAL"
    private var destMode   = "CREATE"
    private var huType     = "PALLET"
    private var lastHuId   = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPackingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomBar.setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences(QuestionnaireActivity.PREFS, Context.MODE_PRIVATE)
        val operatorName = prefs.getString(QuestionnaireActivity.KEY_NAME, "").orEmpty()

        // Source type toggle
        binding.chipMaterial.isChecked = true
        binding.chipGroupSource.setOnCheckedStateChangeListener { _, checkedIds ->
            sourceType = if (checkedIds.firstOrNull() == com.sap.droidx.R.id.chipHU) "HU" else "MATERIAL"
            binding.etSourceId.hint = if (sourceType == "HU") "Scan or enter HU ID" else "Scan or enter Material ID"
            binding.etSourceId.requestFocus()
        }

        // Destination mode toggle
        binding.chipCreateNew.isChecked = true
        binding.cardCreateHU.visibility = View.VISIBLE
        binding.cardScanHU.visibility   = View.GONE
        binding.chipGroupDestMode.setOnCheckedStateChangeListener { _, checkedIds ->
            destMode = if (checkedIds.firstOrNull() == com.sap.droidx.R.id.chipScanExisting) "SCAN" else "CREATE"
            binding.cardCreateHU.visibility = if (destMode == "CREATE") View.VISIBLE else View.GONE
            binding.tilDestHuId.visibility  = if (destMode == "SCAN")   View.VISIBLE else View.GONE
            if (destMode == "SCAN") binding.etDestHuId.requestFocus()
        }

        // HU type for "Create New"
        binding.chipPallet.isChecked = true
        binding.chipGroupHUType.setOnCheckedStateChangeListener { _, checkedIds ->
            huType = when (checkedIds.firstOrNull()) {
                com.sap.droidx.R.id.chipCarton -> "CARTON"
                com.sap.droidx.R.id.chipBox    -> "BOX"
                else                           -> "PALLET"
            }
        }

        binding.btnPack.setOnClickListener { doPack(operatorName) }
        binding.btnPrintLabels.setOnClickListener { showPrintDialog() }
        binding.btnPackAnother.setOnClickListener {
            binding.cardResult.visibility = View.GONE
            binding.etSourceId.text?.clear()
            binding.etSourceId.requestFocus()
        }
    }

    private fun doPack(operatorName: String) {
        val sourceId = binding.etSourceId.text?.toString().orEmpty().trim()
        if (sourceId.isEmpty()) {
            binding.tilSourceId.error = "Source ID is required"
            return
        }
        binding.tilSourceId.error = null

        val destHuIdInput = binding.etDestHuId.text?.toString().orEmpty().trim()
        if (destMode == "SCAN" && destHuIdInput.isEmpty()) {
            binding.tilDestHuId.error = "Destination HU ID is required"
            return
        }
        binding.tilDestHuId.error = null

        binding.btnPack.isEnabled = false
        binding.progressPack.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val destHuId = if (destMode == "CREATE") {
                    repo.createHU(huType, operatorName)
                } else {
                    destHuIdInput
                }
                repo.packItem(destHuId, sourceType, sourceId, 1, operatorName)

                lastHuId = destHuId
                binding.tvResultHuId.text   = destHuId
                binding.tvResultSource.text = "$sourceType: $sourceId"
                binding.cardResult.visibility = View.VISIBLE
                binding.progressPack.visibility = View.GONE
                binding.btnPack.isEnabled = true
            } catch (e: Exception) {
                binding.progressPack.visibility = View.GONE
                binding.btnPack.isEnabled = true
                Snackbar.make(binding.root, "Failed: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun showPrintDialog() {
        val picker = NumberPicker(this).apply {
            minValue = 1
            maxValue = 20
            value    = 1
        }
        AlertDialog.Builder(this)
            .setTitle("Print Labels")
            .setMessage("How many labels for HU: $lastHuId?")
            .setView(picker)
            .setPositiveButton("Print") { _, _ ->
                val qty = picker.value
                Snackbar.make(binding.root, "Printing $qty label(s) for $lastHuId", Snackbar.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
