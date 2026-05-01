package com.sap.droidx.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sap.droidx.R
import com.sap.droidx.databinding.ActivityRfMenuBinding
import java.time.LocalDate

class RFMenuActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRfMenuBinding

    data class RFMenuItem(val number: String, val label: String, val section: String)

    private val menuItems = listOf(
        RFMenuItem("1", "INBOUND",            "INBOUND"),
        RFMenuItem("2", "OUTBOUND",           "OUTBOUND"),
        RFMenuItem("3", "INTERNAL PROCESS",   "INTERNAL"),
        RFMenuItem("4", "PHYSICAL INVENTORY", "PHYS_INV"),
        RFMenuItem("5", "EXCEPTION HANDLING", "EXCEPTION"),
        RFMenuItem("6", "LABELS",             "LABELS"),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRfMenuBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomBar.setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences(QuestionnaireActivity.PREFS, Context.MODE_PRIVATE)
        val user = prefs.getString(QuestionnaireActivity.KEY_NAME, "OPERATOR")
            ?.uppercase()?.take(12) ?: "OPERATOR"
        val site = prefs.getString(QuestionnaireActivity.KEY_SITE, "---")
            ?.uppercase()?.take(10) ?: "---"

        binding.tvUserInfo.text =
            "WH: ${site.padEnd(14)}USER: $user\nDATE: ${LocalDate.now()}"

        binding.rvMenu.layoutManager = LinearLayoutManager(this)
        binding.rvMenu.adapter = RFMenuAdapter(menuItems) { item ->
            if (item.section == "LABELS") {
                startActivity(Intent(this, LabelActivity::class.java))
            } else {
                startActivity(
                    Intent(this, RFSubMenuActivity::class.java)
                        .putExtra(RFSubMenuActivity.EXTRA_SECTION, item.section)
                        .putExtra(RFSubMenuActivity.EXTRA_TITLE, item.label)
                )
            }
        }
    }
}

class RFMenuAdapter(
    private val items: List<RFMenuActivity.RFMenuItem>,
    private val onClick: (RFMenuActivity.RFMenuItem) -> Unit
) : RecyclerView.Adapter<RFMenuAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_rf_menu, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvNumber: TextView = v.findViewById(R.id.tvNumber)
        private val tvLabel: TextView = v.findViewById(R.id.tvLabel)

        init { v.setOnClickListener { onClick(items[adapterPosition]) } }

        fun bind(item: RFMenuActivity.RFMenuItem) {
            tvNumber.text = item.number
            tvLabel.text = item.label
        }
    }
}
