package com.sap.droidx.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.sap.cloud.mobile.foundation.common.ClientProvider
import com.sap.droidx.BtpConfig
import com.sap.droidx.databinding.ActivityDashboardBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs     = getSharedPreferences(QuestionnaireActivity.PREFS, Context.MODE_PRIVATE)
        val fullName  = prefs.getString(QuestionnaireActivity.KEY_NAME, "Operator") ?: "Operator"
        val site      = prefs.getString(QuestionnaireActivity.KEY_SITE, "") ?: ""
        val firstName = fullName.split(" ").firstOrNull()?.replaceFirstChar { it.uppercase() } ?: fullName

        val zone = ZoneId.systemDefault()
        val now  = ZonedDateTime.now(zone)
        val tzAbbr = zone.getDisplayName(TextStyle.SHORT, Locale.getDefault())

        val greeting = when (now.hour) {
            in 5..11  -> "Good morning, $firstName"
            in 12..16 -> "Good afternoon, $firstName"
            in 17..20 -> "Good evening, $firstName"
            else      -> "Good night, $firstName"
        }

        binding.tvGreeting.text = greeting
        binding.tvSite.text = if (site.isNotEmpty()) "Site: ${site.uppercase()}" else ""
        binding.tvDate.text = "${now.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"))}  ·  ${now.format(DateTimeFormatter.ofPattern("HH:mm"))} $tzAbbr"

        binding.cardInspections.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
        binding.cardDeliveries.setOnClickListener  { startActivity(Intent(this, DeliveryListActivity::class.java)) }
        binding.cardRf.setOnClickListener          { startActivity(Intent(this, RFMenuActivity::class.java)) }
        binding.cardLabels.setOnClickListener      { startActivity(Intent(this, LabelActivity::class.java)) }
        binding.cardPacking.setOnClickListener     { startActivity(Intent(this, PackingActivity::class.java)) }
        binding.cardReport.setOnClickListener      { startActivity(Intent(this, ReportActivity::class.java)) }
        binding.cardQc.setOnClickListener          { startActivity(Intent(this, QcActivity::class.java)) }

        binding.cardProduction.setOnClickListener { startActivity(Intent(this, ProductionActivity::class.java)) }
        binding.btnVoiceGr.setOnClickListener     { startActivity(Intent(this, VoiceGrActivity::class.java)) }
        binding.cardConfirmWt.setOnClickListener      { startActivity(Intent(this, ConfirmTaskActivity::class.java)) }
        binding.cardProfile.setOnClickListener {
            startActivity(Intent(this, QuestionnaireActivity::class.java)
                .putExtra(QuestionnaireActivity.EXTRA_FROM_NAV, true))
        }

        setupSearch()
        setupOverflowMenu()
    }

    private fun setupOverflowMenu() {
        binding.btnOverflow.setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor)
            popup.menu.add(0, 1, 0, "View Test Data")
            popup.menu.add(0, 2, 1, "Reset Test Data")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> { showTestDataList(); true }
                    2 -> {
                        AlertDialog.Builder(this)
                            .setTitle("Reset Test Data")
                            .setMessage("Restore all Production, QC, and Warehouse Task data to original seed state?")
                            .setPositiveButton("Reset") { _, _ -> doResetData() }
                            .setNegativeButton("Cancel", null)
                            .show()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun showTestDataList() {
        val loadingDialog = AlertDialog.Builder(this)
            .setTitle("Test Data")
            .setMessage("Loading…")
            .setCancelable(false)
            .show()

        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { fetchTestDataSummary() }
                loadingDialog.dismiss()

                val tv = TextView(this@DashboardActivity).apply {
                    this.text = text
                    textSize = 12.5f
                    setTextColor(0xFF212121.toInt())
                    setPadding(48, 24, 48, 24)
                    typeface = android.graphics.Typeface.MONOSPACE
                }
                val scroll = ScrollView(this@DashboardActivity).apply { addView(tv) }

                AlertDialog.Builder(this@DashboardActivity)
                    .setTitle("Test Data")
                    .setView(scroll)
                    .setPositiveButton("Close", null)
                    .show()
            } catch (e: Exception) {
                loadingDialog.dismiss()
                Snackbar.make(binding.root, "Failed to load: ${e.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun fetchTestDataSummary(): String {
        val http = ClientProvider.get()
        val base = "${BtpConfig.FORKQA_BACKEND_URL}${BtpConfig.ODATA_SERVICE_PATH}"
        val sb = StringBuilder()

        fun get(path: String): JSONObject {
            val resp = http.newCall(Request.Builder().url("$base/$path").get().build()).execute()
            return JSONObject(resp.body!!.string())
        }

        // Production Orders
        val orders = get("ProductionOrders").optJSONArray("value")
        sb.appendLine("── PRODUCTION ORDERS (${orders?.length() ?: 0}) ──────────")
        if (orders != null) {
            for (i in 0 until orders.length()) {
                val o = orders.getJSONObject(i)
                val open = maxOf(0, o.optInt("PLANNED_QTY") - o.optInt("RECEIVED_QTY"))
                sb.appendLine(
                    "  ${o.optString("ORDER_ID").padEnd(8)}  ${o.optString("MATERIAL").padEnd(10)}" +
                    "  Planned:${o.optInt("PLANNED_QTY").toString().padStart(4)}" +
                    "  Rcvd:${o.optInt("RECEIVED_QTY").toString().padStart(4)}" +
                    "  Open:${open.toString().padStart(4)}" +
                    "  [${o.optString("STATUS")}]"
                )
            }
        }

        // GR Items
        val grs = get("GrItems").optJSONArray("value")
        sb.appendLine()
        sb.appendLine("── GR ITEMS (${grs?.length() ?: 0}) ──────────────────────")
        if (grs != null) {
            for (i in 0 until grs.length()) {
                val g = grs.getJSONObject(i)
                sb.appendLine(
                    "  ${g.optString("ORDER_ID").padEnd(8)}  ${g.optString("HU_ID").padEnd(22)}" +
                    "  Qty:${g.optInt("QTY")}  By:${g.optString("GR_BY")}"
                )
            }
        }

        // QC Items
        val qcs = get("QcItems").optJSONArray("value")
        sb.appendLine()
        sb.appendLine("── QC ITEMS (${qcs?.length() ?: 0}) ──────────────────────")
        if (qcs != null) {
            for (i in 0 until qcs.length()) {
                val q = qcs.getJSONObject(i)
                sb.appendLine(
                    "  ${q.optString("SOURCE_TYPE").padEnd(9)}  ${q.optString("SOURCE_ID").padEnd(20)}" +
                    "  ${q.optString("MATERIAL").padEnd(10)}  Qty:${q.optInt("QTY").toString().padStart(4)}" +
                    "  [${q.optString("STATUS")}]"
                )
            }
        }

        // Warehouse Tasks
        val tasks = get("WarehouseTasks").optJSONArray("value")
        sb.appendLine()
        sb.appendLine("── WAREHOUSE TASKS (${tasks?.length() ?: 0}) ─────────────")
        if (tasks != null) {
            for (i in 0 until tasks.length()) {
                val t = tasks.getJSONObject(i)
                sb.appendLine(
                    "  ${t.optString("TASK_NUMBER").padEnd(12)}  ${t.optString("MATERIAL").padEnd(10)}" +
                    "  Qty:${t.optDouble("QTY").toInt().toString().padStart(4)}" +
                    "  ${t.optString("SOURCE_BIN").padEnd(12)}→${t.optString("DEST_BIN").padEnd(12)}" +
                    "  [${t.optString("STATUS")}]"
                )
            }
        }

        return sb.toString().trimEnd()
    }

    private fun doResetData() {
        binding.btnOverflow.isEnabled = false
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val resp = ClientProvider.get().newCall(
                        Request.Builder()
                            .url("${BtpConfig.FORKQA_BACKEND_URL}/api/resetData")
                            .post("".toRequestBody("application/json".toMediaType()))
                            .build()
                    ).execute()
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                }
                Snackbar.make(binding.root, "Test data reset to original state", Snackbar.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Reset failed: ${e.message}", Snackbar.LENGTH_LONG).show()
            } finally {
                binding.btnOverflow.isEnabled = true
            }
        }
    }

    private fun setupSearch() {
        val menuCards: List<Pair<CardView, String>> = listOf(
            binding.cardInspections to "Inspections",
            binding.cardDeliveries  to "Deliveries",
            binding.cardRf          to "RF Menu",
            binding.cardLabels      to "Labels",
            binding.cardPacking     to "Packing",
            binding.cardProduction  to "Production",
            binding.cardReport      to "Report",
            binding.cardQc          to "QC",
            binding.cardConfirmWt    to "Confirm WT",
            binding.cardProfile     to "Profile"
        )

        binding.btnSearch.setOnClickListener {
            if (binding.tilSearch.visibility == View.GONE) {
                binding.tilSearch.visibility = View.VISIBLE
                binding.etSearch.requestFocus()
            } else {
                binding.tilSearch.visibility = View.GONE
                binding.etSearch.text?.clear()
                menuCards.forEach { (card, _) -> card.visibility = View.VISIBLE }
            }
        }

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty().lowercase().trim()
                menuCards.forEach { (card, label) ->
                    card.visibility = if (query.isEmpty() || label.lowercase().contains(query)) View.VISIBLE else View.GONE
                }
            }
        })
    }
}
