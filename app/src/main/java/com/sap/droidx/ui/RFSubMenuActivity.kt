package com.sap.droidx.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.sap.droidx.R
import com.sap.droidx.databinding.ActivityRfSubmenuBinding

class RFSubMenuActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRfSubmenuBinding

    data class RFSubItem(val number: String, val label: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRfSubmenuBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomBar.setNavigationOnClickListener { finish() }

        val section = intent.getStringExtra(EXTRA_SECTION) ?: return
        val title   = intent.getStringExtra(EXTRA_TITLE) ?: section

        binding.tvSection.text = title

        val items = subMenus[section] ?: emptyList()
        binding.rvSubMenu.layoutManager = LinearLayoutManager(this)
        binding.rvSubMenu.adapter = RFSubMenuAdapter(items) { item ->
            Snackbar.make(binding.root, "${item.label} — not yet implemented", Snackbar.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_SECTION = "section"
        const val EXTRA_TITLE   = "title"

        val subMenus: Map<String, List<RFSubItem>> = mapOf(
            "INBOUND" to listOf(
                RFSubItem("1", "GOODS RECEIPT"),
                RFSubItem("2", "UNLOADING"),
                RFSubItem("3", "PUTAWAY"),
                RFSubItem("4", "QUALITY INSPECTION"),
            ),
            "OUTBOUND" to listOf(
                RFSubItem("1", "PICKING"),
                RFSubItem("2", "PACKING"),
                RFSubItem("3", "GOODS ISSUE"),
                RFSubItem("4", "LOADING"),
            ),
            "INTERNAL" to listOf(
                RFSubItem("1", "STOCK TRANSFER"),
                RFSubItem("2", "REPLENISHMENT"),
                RFSubItem("3", "AD HOC MOVEMENT"),
                RFSubItem("4", "TASK MANAGEMENT"),
            ),
            "PHYS_INV" to listOf(
                RFSubItem("1", "COUNT DOCUMENT"),
                RFSubItem("2", "ENTER COUNT"),
                RFSubItem("3", "RECOUNT"),
                RFSubItem("4", "POST DIFFERENCES"),
            ),
            "EXCEPTION" to listOf(
                RFSubItem("1", "BLOCKED STOCK"),
                RFSubItem("2", "RETURN TO VENDOR"),
                RFSubItem("3", "SCRAPPING"),
                RFSubItem("4", "STATUS CHANGE"),
            ),
        )
    }
}

class RFSubMenuAdapter(
    private val items: List<RFSubMenuActivity.RFSubItem>,
    private val onClick: (RFSubMenuActivity.RFSubItem) -> Unit
) : RecyclerView.Adapter<RFSubMenuAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_rf_menu, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvNumber: TextView = v.findViewById(R.id.tvNumber)
        private val tvLabel: TextView  = v.findViewById(R.id.tvLabel)

        init { v.setOnClickListener { onClick(items[adapterPosition]) } }

        fun bind(item: RFSubMenuActivity.RFSubItem) {
            tvNumber.text = item.number
            tvLabel.text  = item.label
        }
    }
}
