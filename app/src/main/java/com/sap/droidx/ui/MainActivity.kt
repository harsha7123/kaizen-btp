package com.sap.droidx.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.sap.droidx.R
import com.sap.droidx.data.InspectionRepository
import com.sap.droidx.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repo = InspectionRepository()
    private val adapter = InspectionAdapter { row ->
        detailLauncher.launch(
            Intent(this, InspectionDetailActivity::class.java)
                .putExtra(InspectionDetailActivity.EXTRA_ID, row.inspectionId)
        )
    }

    private val detailLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) load()
    }

    private val newInspectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) load()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = null
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.refresh.setOnRefreshListener { load() }

        binding.fab.setOnClickListener {
            newInspectionLauncher.launch(
                Intent(this, NewInspectionActivity::class.java)
            )
        }

        binding.bottomNav.selectedItemId = R.id.nav_home
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { load(); true }
                R.id.nav_delivery -> {
                    startActivity(Intent(this, DeliveryListActivity::class.java))
                    true
                }
                R.id.nav_rf -> {
                    startActivity(Intent(this, RFMenuActivity::class.java))
                    true
                }
                R.id.nav_report -> {
                    startActivity(Intent(this, ReportActivity::class.java))
                    true
                }
                R.id.nav_profile -> {
                    startActivity(
                        Intent(this, QuestionnaireActivity::class.java)
                            .putExtra(QuestionnaireActivity.EXTRA_FROM_NAV, true)
                    )
                    true
                }
                else -> false
            }
        }

        load()
    }

    private fun load() {
        binding.refresh.isRefreshing = true
        lifecycleScope.launch {
            runCatching { repo.listInspections() }
                .onSuccess { rows ->
                    Log.d("MainActivity", "Loaded ${rows.size} inspections")
                    InspectionCache.rows = rows
                    adapter.submit(rows)
                }
                .onFailure {
                    Log.e("MainActivity", "Load failed: ${it.javaClass.simpleName}: ${it.message}", it)
                    Snackbar.make(binding.root, it.message ?: "Load failed", Snackbar.LENGTH_LONG).show()
                }
            binding.refresh.isRefreshing = false
        }
    }
}

data class InspectionRow(
    val inspectionId: String,
    val forkliftId: String,
    val operator: String,
    val operatorId: String,
    val status: String,
    val date: String,
    val shift: String,
    val tirePressure: String,
    val odometer: Int,
    val physicalCondition: String,
    val brakes: String,
    val hydraulics: String,
    val hornLights: String,
    val remarks: String,
    val createdBy: String,
    val createdAt: String
)

class InspectionAdapter(
    private val onItemClick: (InspectionRow) -> Unit
) : RecyclerView.Adapter<InspectionAdapter.VH>() {

    private val items = mutableListOf<InspectionRow>()

    fun submit(rows: List<InspectionRow>) {
        items.clear(); items.addAll(rows); notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_inspection, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val title: TextView = v.findViewById(R.id.title)
        private val subtitle: TextView = v.findViewById(R.id.subtitle)
        private val status: TextView = v.findViewById(R.id.status)

        init {
            v.setOnClickListener { onItemClick(items[adapterPosition]) }
        }

        fun bind(r: InspectionRow) {
            title.text = "${r.forkliftId}  ·  ${r.operator}"
            subtitle.text = r.date
            status.text = r.status
        }
    }
}
