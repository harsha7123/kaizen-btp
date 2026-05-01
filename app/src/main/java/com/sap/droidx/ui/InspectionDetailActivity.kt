package com.sap.droidx.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.sap.droidx.R
import com.sap.droidx.data.InspectionRepository
import com.sap.droidx.databinding.ActivityInspectionDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InspectionDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInspectionDetailBinding
    private val repo = InspectionRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityInspectionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.bottomBar.setNavigationOnClickListener { finish() }

        val id = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }

        binding.bottomBar.inflateMenu(R.menu.menu_inspection_detail)
        binding.bottomBar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_delete) { confirmDelete(id); true } else false
        }

        // Retrieve the row from the list cache via the ID passed as intent extra.
        // The repository will be called for the photo separately.
        val allRows = InspectionCache.rows
        val row = allRows.find { it.inspectionId == id }
        if (row != null) {
            bindRow(row)
        }

        // Fetch photo asynchronously
        lifecycleScope.launch {
            Log.d(TAG, "Fetching photo for id=$id")
            val photoB64 = repo.getInspectionPhoto(id)
            Log.d(TAG, "Photo response: ${if (photoB64.isNullOrBlank()) "null/blank" else "length=${photoB64.length}"}")
            if (!photoB64.isNullOrBlank()) {
                val bmp = withContext(Dispatchers.IO) {
                    try {
                        val bytes = Base64.decode(photoB64, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    } catch (e: IllegalArgumentException) {
                        Log.e(TAG, "Base64 decode failed: ${e.message}")
                        Log.e(TAG, "First 120 chars of photoB64: ${photoB64.take(120)}")
                        null
                    }
                }
                if (bmp != null) {
                    binding.imgPhoto.setImageBitmap(bmp)
                    binding.imgPhoto.visibility = View.VISIBLE
                    Log.d(TAG, "Photo displayed: ${bmp.width}x${bmp.height}")
                } else {
                    Log.e(TAG, "BitmapFactory.decodeByteArray returned null")
                }
            }
        }
    }

    private fun bindRow(r: InspectionRow) {
        binding.toolbar.title = r.forkliftId

        binding.tvStatus.text = r.status
        binding.tvStatus.setTextColor(
            if (r.status == "READY")
                getColor(com.google.android.material.R.color.design_default_color_secondary)
            else getColor(android.R.color.holo_red_dark)
        )

        setRow(binding.rowForklift.root,        "Forklift ID",         r.forkliftId)
        setRow(binding.rowOperator.root,        "Operator",            r.operator)
        setRow(binding.rowDate.root,            "Date",                r.date)
        setRow(binding.rowShift.root,           "Shift",               r.shift)
        setRow(binding.rowOdometer.root,        "Odometer",            r.odometer.toString())
        setRow(binding.rowTirePressure.root,    "Tire Pressure",       r.tirePressure)
        setRow(binding.rowPhysicalCondition.root,"Physical Condition", r.physicalCondition)
        setRow(binding.rowBrakes.root,          "Brakes",              r.brakes)
        setRow(binding.rowHydraulics.root,      "Hydraulics",          r.hydraulics)
        setRow(binding.rowHornLights.root,      "Horn / Lights",       r.hornLights)

        binding.tvRemarks.text = r.remarks.ifBlank { "—" }
        binding.tvCreatedInfo.text = "Created by ${r.createdBy} · ${r.createdAt.take(16).replace('T', ' ')}"
    }

    private fun setRow(root: View, label: String, value: String) {
        root.findViewById<TextView>(R.id.tvLabel).text = label
        root.findViewById<TextView>(R.id.tvValue).text = value
    }

    private fun confirmDelete(id: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Inspection")
            .setMessage("Permanently delete this inspection record?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deleteRecord(id) }
            .show()
    }

    private fun deleteRecord(id: String) {
        lifecycleScope.launch {
            runCatching { repo.deleteInspection(id) }
                .onSuccess {
                    InspectionCache.rows = InspectionCache.rows.filterNot { it.inspectionId == id }
                    setResult(RESULT_OK)
                    finish()
                }
                .onFailure {
                    Log.e(TAG, "Delete failed: ${it.message}", it)
                    Snackbar.make(binding.root, "Delete failed: ${it.message}", Snackbar.LENGTH_LONG).show()
                }
        }
    }

    companion object {
        const val EXTRA_ID = "inspection_id"
        private const val TAG = "InspectionDetail"
    }
}

object InspectionCache {
    var rows: List<InspectionRow> = emptyList()
}
