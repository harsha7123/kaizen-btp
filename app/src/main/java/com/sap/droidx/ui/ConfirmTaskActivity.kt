package com.sap.droidx.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.snackbar.Snackbar
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.sap.droidx.data.ConfirmTaskRepository
import com.sap.droidx.data.WarehouseTask
import com.sap.droidx.databinding.ActivityConfirmTaskBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

class ConfirmTaskActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfirmTaskBinding
    private val repo = ConfirmTaskRepository()

    private var currentTask: WarehouseTask? = null
    private val photos = mutableListOf<String>()
    private var photoUri: Uri? = null
    private var locationLat: Double? = null
    private var locationLon: Double? = null
    private var locationAccuracy: Float? = null
    private var physicalAddress: String = ""
    private var city: String = ""

    // Multi-sample location state
    private var bestFix: Location? = null
    private var locationCallback: LocationCallback? = null
    private val locationHandler = Handler(Looper.getMainLooper())
    private var locationTimeoutRunnable: Runnable? = null

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    // ── Barcode launchers ────────────────────────────────────────────────────

    private val taskScanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null)
            binding.etTaskNumber.setText(result.contents.filter { it.isDigit() }.take(10))
    }

    private val huScanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) binding.etConfirmHU.setText(result.contents.trim())
    }

    private val binScanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) binding.etBin.setText(result.contents.trim())
    }

    // ── Camera ───────────────────────────────────────────────────────────────

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = photoUri
        if (ok && uri != null) processPhoto(uri)
    }

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else Snackbar.make(binding.root, "Camera permission denied", Snackbar.LENGTH_SHORT).show()
    }

    // ── Location permission ───────────────────────────────────────────────────

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            fetchLocation()
        } else {
            binding.tvLocation.text = "Location permission denied — will confirm without GPS"
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfirmTaskBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomBar.setNavigationOnClickListener { finish() }

        binding.btnScanTask.setOnClickListener { launchScanner(taskScanLauncher, "Scan warehouse task barcode") }
        binding.btnFetchTask.setOnClickListener { doFetchTask() }
        binding.etTaskNumber.setOnEditorActionListener { _, _, _ -> doFetchTask(); true }

        binding.btnScanHU.setOnClickListener { launchScanner(huScanLauncher, "Scan handling unit barcode") }
        binding.btnScanBin.setOnClickListener { launchScanner(binScanLauncher, "Scan destination bin barcode") }

        binding.btnAddPhoto.setOnClickListener { requestCamera() }
        binding.btnGetLocation.setOnClickListener { requestLocation() }
        binding.btnConfirm.setOnClickListener { doConfirm() }

        // Start warming up GPS immediately so it is ready by the time task is fetched
        requestLocation()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
    }

    // ── Fetch task ───────────────────────────────────────────────────────────

    private fun doFetchTask() {
        val taskNum = binding.etTaskNumber.text?.toString()?.trim() ?: ""
        binding.tilTaskNumber.error = null
        if (taskNum.length != 10) {
            binding.tilTaskNumber.error = "Enter a 10-digit task number"
            return
        }
        binding.btnFetchTask.isEnabled = false
        binding.progressFetch.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val task = repo.fetchTask(taskNum)
                currentTask = task
                populateForm(task)
                binding.cardTaskDetails.visibility   = View.VISIBLE
                binding.cardConfirmInputs.visibility = View.VISIBLE
                binding.cardPhoto.visibility         = View.VISIBLE
                binding.cardLocation.visibility      = View.VISIBLE
                binding.frameConfirm.visibility      = View.VISIBLE
                // GPS is already running from onCreate; if we already have a good fix
                // the location card shows it immediately; otherwise it updates in place.
                val fix = bestFix
                if (fix != null) updateLocationUi(fix)
            } catch (e: Exception) {
                Snackbar.make(binding.root, e.message ?: "Task not found", Snackbar.LENGTH_LONG).show()
            } finally {
                binding.btnFetchTask.isEnabled = true
                binding.progressFetch.visibility = View.GONE
            }
        }
    }

    private fun populateForm(task: WarehouseTask) {
        val matLine = listOf(task.material, task.materialDesc).filter { it.isNotEmpty() }.joinToString("  ·  ")
        binding.tvDetailMaterial.text = matLine.ifEmpty { "—" }
        val qtyStr = task.qty.toBigDecimal().stripTrailingZeros().toPlainString()
        binding.tvDetailQty.text  = if (task.uom.isNotEmpty()) "$qtyStr ${task.uom}" else qtyStr
        binding.tvDetailRoute.text = "${task.sourceBin.ifEmpty { "?" }}  →  ${task.destBin.ifEmpty { "?" }}"

        if (task.status == "CONFIRMED") {
            binding.cardAlreadyConfirmed.visibility = View.VISIBLE
            binding.tvPrevConfirmedHU.text  = task.confirmedHu.ifEmpty { "—" }
            binding.tvPrevConfirmedQty.text = if (task.confirmedQty > 0)
                task.confirmedQty.toBigDecimal().stripTrailingZeros().toPlainString() else "—"
            binding.tvPrevConfirmedBin.text = task.confirmedBin.ifEmpty { "—" }
            binding.tvPrevConfirmedBy.text  = task.confirmedBy.ifEmpty { "—" }
            binding.tvPrevConfirmedAt.text  = task.confirmedAt.ifEmpty { "—" }
            binding.btnConfirm.text = "Update Confirmation"
        } else {
            binding.cardAlreadyConfirmed.visibility = View.GONE
            binding.btnConfirm.text = "Save & Confirm Task"
        }

        binding.etConfirmHU.setText(task.confirmedHu.ifEmpty { task.hu })
        binding.etConfirmQty.setText(
            if (task.confirmedQty > 0)
                task.confirmedQty.toBigDecimal().stripTrailingZeros().toPlainString()
            else
                task.qty.toBigDecimal().stripTrailingZeros().toPlainString()
        )
        binding.etBin.setText(task.confirmedBin.ifEmpty { task.destBin })
    }

    // ── Photos ───────────────────────────────────────────────────────────────

    private fun requestCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) launchCamera()
        else cameraPermLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun launchCamera() {
        val dir  = File(cacheDir, "tasks").also { it.mkdirs() }
        val file = File(dir, "task_${System.currentTimeMillis()}.jpg")
        photoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        cameraLauncher.launch(photoUri!!)
    }

    private fun processPhoto(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return@launch
            val bos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 60, bos)
            val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            photos.add(b64)
            withContext(Dispatchers.Main) { addThumbnail(bitmap) }
        }
    }

    private fun addThumbnail(bitmap: Bitmap) {
        val dp     = resources.displayMetrics.density
        val size   = (84 * dp).toInt()
        val margin = (6 * dp).toInt()
        val iv = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size).also { it.marginEnd = margin }
            scaleType    = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(bitmap)
            setBackgroundColor(0x22000000)
        }
        binding.layoutPhotos.addView(iv)
        binding.scrollPhotos.visibility = View.VISIBLE
        binding.tvPhotoCount.text = "${photos.size} / 5"
        if (photos.size >= 5) binding.btnAddPhoto.isEnabled = false
    }

    // ── Location ─────────────────────────────────────────────────────────────

    private fun requestLocation() {
        val fine   = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED)
            fetchLocation()
        else locationPermLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocation() {
        stopLocationUpdates()
        bestFix = null

        binding.tvLocation.text = "Acquiring GPS fix…"
        binding.tvLocation.setTextColor(Color.parseColor("#757575"))
        binding.btnGetLocation.isEnabled = false

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .setWaitForAccurateLocation(false)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val cur = bestFix
                if (cur == null || loc.accuracy < cur.accuracy) {
                    bestFix = loc
                    updateLocationUi(loc)
                }
                if (loc.accuracy <= 10f) finishLocationCapture()
            }
        }
        locationCallback = cb
        fusedLocationClient.requestLocationUpdates(request, cb, Looper.getMainLooper())

        // Stop collecting after 15 s regardless of accuracy reached
        val timeout = Runnable { finishLocationCapture() }
        locationTimeoutRunnable = timeout
        locationHandler.postDelayed(timeout, 15_000L)
    }

    private fun updateLocationUi(loc: Location) {
        locationLat      = loc.latitude
        locationLon      = loc.longitude
        locationAccuracy = loc.accuracy

        val (color, label) = when {
            loc.accuracy <= 10f -> Pair(Color.parseColor("#2E7D32"), "Good")
            loc.accuracy <= 30f -> Pair(Color.parseColor("#E65100"), "Fair")
            else                -> Pair(Color.parseColor("#C62828"), "Poor")
        }
        binding.tvLocation.setTextColor(color)
        binding.tvLocation.text =
            "Lat: %.6f\nLon: %.6f\nAccuracy: ±%.1f m  [%s]".format(
                loc.latitude, loc.longitude, loc.accuracy, label)
        reverseGeocode(loc.latitude, loc.longitude)
    }

    private fun reverseGeocode(lat: Double, lon: Double) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                @Suppress("DEPRECATION")
                val addresses = Geocoder(this@ConfirmTaskActivity, Locale.getDefault())
                    .getFromLocation(lat, lon, 1)
                val addr = addresses?.firstOrNull()
                if (addr != null) {
                    physicalAddress = addr.getAddressLine(0) ?: ""
                    city = addr.locality ?: addr.subAdminArea ?: addr.adminArea ?: ""
                    withContext(Dispatchers.Main) {
                        val display = buildString {
                            if (physicalAddress.isNotEmpty()) appendLine(physicalAddress)
                            if (city.isNotEmpty() && !physicalAddress.contains(city)) append(city)
                        }.trim()
                        if (display.isNotEmpty()) {
                            binding.tvAddress.text = display
                            binding.tvAddress.visibility = android.view.View.VISIBLE
                        }
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun finishLocationCapture() {
        stopLocationUpdates()
        val fix = bestFix
        if (fix != null) {
            updateLocationUi(fix)
        } else {
            binding.tvLocation.text = "Could not get location — tap Refresh to retry"
            binding.tvLocation.setTextColor(Color.parseColor("#757575"))
        }
        binding.btnGetLocation.isEnabled = true
    }

    private fun stopLocationUpdates() {
        locationTimeoutRunnable?.let { locationHandler.removeCallbacks(it) }
        locationTimeoutRunnable = null
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        locationCallback = null
    }

    // ── Confirm / save ───────────────────────────────────────────────────────

    private fun doConfirm() {
        val task = currentTask ?: return

        val hu     = binding.etConfirmHU.text?.toString()?.trim().orEmpty()
        val bin    = binding.etBin.text?.toString()?.trim().orEmpty()
        val qtyStr = binding.etConfirmQty.text?.toString()?.trim().orEmpty()

        binding.tilBin.error = null
        binding.tilConfirmQty.error = null

        if (bin.isEmpty()) { binding.tilBin.error = "Destination bin is required"; return }
        val qty = qtyStr.toDoubleOrNull()
        if (qty == null || qty <= 0) { binding.tilConfirmQty.error = "Enter a valid quantity"; return }

        val operator = getSharedPreferences(QuestionnaireActivity.PREFS, Context.MODE_PRIVATE)
            .getString(QuestionnaireActivity.KEY_NAME, "").orEmpty()

        binding.btnConfirm.isEnabled = false
        binding.progressConfirm.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                repo.confirmTask(
                    taskId       = task.taskId,
                    confirmedHu  = hu,
                    confirmedQty = qty,
                    bin          = bin,
                    lat          = locationLat,
                    lon          = locationLon,
                    accuracy     = locationAccuracy,
                    operatorName = operator,
                    photos       = photos,
                    physicalAddress = physicalAddress,
                    city         = city
                )
                Snackbar.make(
                    binding.root,
                    "Task ${task.taskNumber} confirmed & saved",
                    Snackbar.LENGTH_LONG
                ).show()
                resetForm()
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Confirm failed: ${e.message}", Snackbar.LENGTH_LONG).show()
            } finally {
                binding.btnConfirm.isEnabled = true
                binding.progressConfirm.visibility = View.GONE
            }
        }
    }

    // ── Reset ────────────────────────────────────────────────────────────────

    private fun resetForm() {
        currentTask      = null
        photos.clear()
        photoUri         = null
        locationLat      = null
        locationLon      = null
        locationAccuracy = null
        physicalAddress  = ""
        city             = ""

        stopLocationUpdates()
        bestFix = null

        binding.etTaskNumber.text?.clear()
        binding.tilTaskNumber.error = null
        binding.cardAlreadyConfirmed.visibility = View.GONE
        binding.cardTaskDetails.visibility   = View.GONE
        binding.cardConfirmInputs.visibility = View.GONE
        binding.cardPhoto.visibility         = View.GONE
        binding.cardLocation.visibility      = View.GONE
        binding.frameConfirm.visibility      = View.GONE

        binding.etConfirmHU.text?.clear()
        binding.etConfirmQty.text?.clear()
        binding.tilConfirmQty.error = null
        binding.etBin.text?.clear()
        binding.tilBin.error = null

        binding.layoutPhotos.removeAllViews()
        binding.scrollPhotos.visibility = View.GONE
        binding.tvPhotoCount.text = "0 / 5"
        binding.btnAddPhoto.isEnabled = true

        binding.tvLocation.text = "Fetching location…"
        binding.tvLocation.setTextColor(Color.parseColor("#757575"))
        binding.tvAddress.visibility = android.view.View.GONE
        binding.tvAddress.text = ""

        // Restart GPS warmup for the next task
        requestLocation()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun launchScanner(
        launcher: androidx.activity.result.ActivityResultLauncher<ScanOptions>,
        prompt: String
    ) {
        launcher.launch(ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES)
            setPrompt(prompt)
            setBeepEnabled(true)
            setOrientationLocked(false)
        })
    }
}
