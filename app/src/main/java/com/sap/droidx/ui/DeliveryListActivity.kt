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
import com.sap.droidx.data.DeliveryRepository
import com.sap.droidx.databinding.ActivityDeliveryListBinding
import kotlinx.coroutines.launch

class DeliveryListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeliveryListBinding
    private val repo = DeliveryRepository()
    private val adapter = DeliveryAdapter { row ->
        formLauncher.launch(
            Intent(this, DeliveryFormActivity::class.java)
                .putExtra(DeliveryFormActivity.EXTRA_ID, row.deliveryId)
        )
    }

    private val formLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) load()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeliveryListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.bottomBar.setNavigationOnClickListener { finish() }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.refresh.setOnRefreshListener { load() }

        binding.fab.setOnClickListener {
            formLauncher.launch(Intent(this, DeliveryFormActivity::class.java))
        }

        load()
    }

    private fun load() {
        binding.refresh.isRefreshing = true
        lifecycleScope.launch {
            runCatching { repo.listDeliveries() }
                .onSuccess { rows ->
                    Log.d(TAG, "Loaded ${rows.size} deliveries")
                    adapter.submit(rows)
                }
                .onFailure {
                    Log.e(TAG, "Load failed: ${it.message}", it)
                    Snackbar.make(binding.root, it.message ?: "Load failed", Snackbar.LENGTH_LONG).show()
                }
            binding.refresh.isRefreshing = false
        }
    }

    companion object {
        private const val TAG = "DeliveryList"
    }
}

data class DeliveryRow(
    val deliveryId: String,
    val deliveryNumber: String,
    val comments: String,
    val createdBy: String,
    val createdAt: String,
    val updatedAt: String
)

class DeliveryAdapter(
    private val onItemClick: (DeliveryRow) -> Unit
) : RecyclerView.Adapter<DeliveryAdapter.VH>() {

    private val items = mutableListOf<DeliveryRow>()

    fun submit(rows: List<DeliveryRow>) {
        items.clear(); items.addAll(rows); notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_delivery, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val tvNumber: TextView = v.findViewById(R.id.tvDeliveryNumber)
        private val tvDate: TextView = v.findViewById(R.id.tvDate)
        private val tvComment: TextView = v.findViewById(R.id.tvComment)

        init {
            v.setOnClickListener { onItemClick(items[adapterPosition]) }
        }

        fun bind(r: DeliveryRow) {
            tvNumber.text = "Delivery #${r.deliveryNumber}"
            tvDate.text = r.createdAt.take(10)
            tvComment.text = r.comments.ifBlank { "—" }
        }
    }
}
