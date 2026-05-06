package com.sap.droidx.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sap.droidx.R
import com.sap.droidx.databinding.ActivityRfMenuBinding
import java.time.LocalDate

class RFMenuActivity : AppCompatActivity() {

    data class RFMenuItem(val number: String, val label: String, val section: String)

    private val menuItems = listOf(
        RFMenuItem("1", "INBOUND",            "INBOUND"),
        RFMenuItem("2", "OUTBOUND",           "OUTBOUND"),
        RFMenuItem("3", "INTERNAL PROCESS",   "INTERNAL"),
        RFMenuItem("4", "PHYSICAL INVENTORY", "PHYS_INV"),
        RFMenuItem("5", "EXCEPTION HANDLING", "EXCEPTION"),
        RFMenuItem("6", "LABELS",             "LABELS"),
        RFMenuItem("7", "CONFIRM TASK",       "CONFIRM_TASK"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityRfMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        val prefs = getSharedPreferences(QuestionnaireActivity.PREFS, Context.MODE_PRIVATE)
        val user = prefs.getString(QuestionnaireActivity.KEY_NAME, "OPERATOR")
            ?.uppercase()?.take(12) ?: "OPERATOR"
        val site = prefs.getString(QuestionnaireActivity.KEY_SITE, "---")
            ?.uppercase()?.take(10) ?: "---"

        binding.tvUserInfo.text =
            "WH: ${site.padEnd(14)}USER: $user\nDATE: ${LocalDate.now()}"

        val inflater = LayoutInflater.from(this)
        menuItems.forEach { item ->
            val row = inflater.inflate(R.layout.item_rf_menu, binding.menuContainer, false)
            row.findViewById<TextView>(R.id.tvNumber).text = item.number
            row.findViewById<TextView>(R.id.tvLabel).text  = item.label
            row.setOnClickListener { onItemClick(item) }
            binding.menuContainer.addView(row)
        }
    }

    private fun onItemClick(item: RFMenuItem) {
        when (item.section) {
            "LABELS"       -> startActivity(Intent(this, LabelActivity::class.java))
            "CONFIRM_TASK" -> startActivity(Intent(this, ConfirmTaskActivity::class.java))
            else -> startActivity(
                Intent(this, RFSubMenuActivity::class.java)
                    .putExtra(RFSubMenuActivity.EXTRA_SECTION, item.section)
                    .putExtra(RFSubMenuActivity.EXTRA_TITLE, item.label)
            )
        }
    }
}
