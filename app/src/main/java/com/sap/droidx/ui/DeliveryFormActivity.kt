package com.sap.droidx.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.sap.droidx.R
import com.sap.droidx.data.DeliveryRepository
import com.sap.droidx.databinding.ActivityDeliveryFormBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class DeliveryFormActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeliveryFormBinding
    private val repo = DeliveryRepository()
    private var editId: String? = null
    private val photos = mutableListOf<PhotoItem>()
    private val deletedPhotoIds = mutableListOf<String>()
    private lateinit var photoAdapter: PhotoAdapter
    private var currentPhotoUri: Uri? = null

    data class PhotoItem(
        val photoId: String? = null,
        val base64: String
    )

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val scanned = result.contents.filter { it.isDigit() }.take(10)
            binding.etDeliveryNumber.setText(scanned)
        }
    }

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = currentPhotoUri
        if (success && uri != null) {
            binding.btnAddPhoto.isEnabled = false
            encodePhoto(uri)
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else Snackbar.make(binding.root, "Camera permission denied", Snackbar.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeliveryFormBinding.inflate(layoutInflater)
        setContentView(binding.root)

        editId = intent.getStringExtra(EXTRA_ID)
        binding.toolbar.title = if (editId == null) "New Delivery" else "Edit Delivery"
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.etDeliveryNumber.filters = arrayOf(
            InputFilter.LengthFilter(10),
            InputFilter { source, _, _, _, _, _ -> if (source.all { it.isDigit() }) source else "" }
        )

        photoAdapter = PhotoAdapter(photos) { pos ->
            val item = photos[pos]
            if (item.photoId != null) deletedPhotoIds.add(item.photoId)
            photos.removeAt(pos)
            photoAdapter.notifyItemRemoved(pos)
        }
        binding.rvPhotos.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvPhotos.adapter = photoAdapter

        binding.btnScan.setOnClickListener {
            scanLauncher.launch(ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES)
                setPrompt("Scan delivery barcode")
                setBeepEnabled(true)
                setOrientationLocked(false)
            })
        }

        binding.btnAddPhoto.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) launchCamera()
            else cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }

        binding.bottomBar.setNavigationOnClickListener { finish() }
        binding.bottomBar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_save -> { save(); true }
                R.id.action_delete -> { confirmDelete(); true }
                else -> false
            }
        }

        if (editId != null) {
            binding.bottomBar.inflateMenu(R.menu.menu_delivery_form_edit)
            loadForEdit(editId!!)
        } else {
            binding.bottomBar.inflateMenu(R.menu.menu_delivery_form_new)
        }

        val prefs = getSharedPreferences(QuestionnaireActivity.PREFS, Context.MODE_PRIVATE)
        val name = prefs.getString(QuestionnaireActivity.KEY_NAME, "") ?: ""
        binding.tvCreatedBy.text = if (name.isNotBlank()) "By: $name" else ""
    }

    private fun loadForEdit(id: String) {
        lifecycleScope.launch {
            runCatching {
                val row = repo.getDelivery(id)
                val photoItems = repo.getDeliveryPhotos(id)
                Pair(row, photoItems)
            }.onSuccess { (row, photoItems) ->
                row?.let {
                    binding.etDeliveryNumber.setText(it.deliveryNumber)
                    binding.etComments.setText(it.comments)
                }
                photoItems.forEach { p ->
                    if (!p.photoData.isNullOrBlank()) {
                        photos.add(PhotoItem(photoId = p.photoId, base64 = p.photoData))
                    }
                }
                photoAdapter.notifyDataSetChanged()
                Log.d(TAG, "Edit loaded: ${photoItems.size} photos")
            }.onFailure {
                Log.e(TAG, "Load failed: ${it.message}", it)
                Snackbar.make(binding.root, "Load failed: ${it.message}", Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun launchCamera() {
        val photoFile = File(cacheDir, "photos").also { it.mkdirs() }
            .let { File(it, "delivery_${System.currentTimeMillis()}.jpg") }
        currentPhotoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
        cameraLauncher.launch(currentPhotoUri!!)
    }

    private fun encodePhoto(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            if (bitmap == null) {
                withContext(Dispatchers.Main) { binding.btnAddPhoto.isEnabled = true }
                return@launch
            }
            val b64 = Base64.encodeToString(compressBitmap(bitmap), Base64.NO_WRAP)
            withContext(Dispatchers.Main) {
                photos.add(PhotoItem(base64 = b64))
                photoAdapter.notifyItemInserted(photos.size - 1)
                binding.btnAddPhoto.isEnabled = true
                Log.d(TAG, "Photo added: ${b64.length} chars, total=${photos.size}")
            }
        }
    }

    private fun compressBitmap(src: Bitmap): ByteArray {
        val maxDim = 800
        val scaled = if (src.width > maxDim || src.height > maxDim) {
            val ratio = maxDim.toFloat() / maxOf(src.width, src.height)
            Bitmap.createScaledBitmap(src, (src.width * ratio).toInt(), (src.height * ratio).toInt(), true)
        } else src
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 60, it) }.toByteArray()
    }

    private fun save() {
        val number = binding.etDeliveryNumber.text?.toString().orEmpty().trim()
        if (number.length != 10) {
            binding.tilDeliveryNumber.error = "Must be exactly 10 digits"; return
        }
        binding.tilDeliveryNumber.error = null
        val comments = binding.etComments.text?.toString().orEmpty().trim()
        val createdBy = binding.tvCreatedBy.text?.toString()?.removePrefix("By: ").orEmpty()

        binding.btnAddPhoto.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                val id = editId
                if (id == null) {
                    val newId = repo.createDelivery(number, comments, createdBy)
                    photos.forEachIndexed { idx, p -> repo.addPhoto(newId, p.base64, idx) }
                } else {
                    repo.updateDelivery(id, number, comments)
                    deletedPhotoIds.forEach { photoId ->
                        runCatching { repo.deletePhoto(photoId) }
                            .onFailure { Log.e(TAG, "deletePhoto($photoId) failed: ${it.message}") }
                    }
                    val existingCount = photos.count { it.photoId != null }
                    photos.filter { it.photoId == null }.forEachIndexed { idx, p ->
                        repo.addPhoto(id, p.base64, existingCount + idx)
                    }
                }
            }.onSuccess {
                setResult(RESULT_OK)
                finish()
            }.onFailure {
                Log.e(TAG, "Save failed: ${it.message}", it)
                Snackbar.make(binding.root, "Save failed: ${it.message}", Snackbar.LENGTH_LONG).show()
                binding.btnAddPhoto.isEnabled = true
            }
        }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Delivery")
            .setMessage("Permanently delete this delivery record and all its photos?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> deleteRecord() }
            .show()
    }

    private fun deleteRecord() {
        lifecycleScope.launch {
            runCatching { repo.deleteDelivery(editId!!) }
                .onSuccess { setResult(RESULT_OK); finish() }
                .onFailure {
                    Snackbar.make(binding.root, "Delete failed: ${it.message}", Snackbar.LENGTH_LONG).show()
                }
        }
    }

    companion object {
        const val EXTRA_ID = "delivery_id"
        private const val TAG = "DeliveryForm"
    }
}

class PhotoAdapter(
    private val items: MutableList<DeliveryFormActivity.PhotoItem>,
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<PhotoAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_photo_thumb, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        try {
            val bytes = Base64.decode(item.base64, Base64.DEFAULT)
            holder.imgThumb.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        } catch (e: Exception) {
            holder.imgThumb.setImageResource(android.R.drawable.ic_menu_gallery)
        }
        holder.btnRemove.setOnClickListener { onRemove(holder.adapterPosition) }
    }

    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val imgThumb: ImageView = v.findViewById(R.id.imgThumb)
        val btnRemove: ImageButton = v.findViewById(R.id.btnRemove)
    }
}
