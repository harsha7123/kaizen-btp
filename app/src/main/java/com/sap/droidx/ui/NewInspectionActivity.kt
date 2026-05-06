package com.sap.droidx.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.sap.cloud.mobile.foundation.common.ClientProvider
import com.sap.droidx.BtpConfig
import com.sap.droidx.R
import com.sap.droidx.databinding.ActivityNewInspectionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate

class NewInspectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNewInspectionBinding
    private var photoUri: Uri? = null
    private var photoBase64: String? = null

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = photoUri
        if (success && uri != null) {
            binding.btnSubmit.isEnabled = false
            showPhotoPreview(uri)
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera() else
            Snackbar.make(binding.root, "Camera permission denied", Snackbar.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNewInspectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomBar.setNavigationOnClickListener { finish() }

        val prefs = getSharedPreferences(QuestionnaireActivity.PREFS, Context.MODE_PRIVATE)
        binding.etOperatorName.setText(prefs.getString(QuestionnaireActivity.KEY_NAME, ""))

        binding.btnTakePhoto.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                launchCamera()
            } else {
                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            }
        }

        binding.btnSubmit.setOnClickListener { validateAndSubmit() }
    }

    private fun launchCamera() {
        val photoFile = File(cacheDir, "photos").also { it.mkdirs() }
            .let { File(it, "inspection_${System.currentTimeMillis()}.jpg") }
        photoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
        cameraLauncher.launch(photoUri!!)
    }

    private fun showPhotoPreview(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: run {
                Log.e(TAG, "showPhotoPreview: failed to decode bitmap from $uri")
                withContext(Dispatchers.Main) { binding.btnSubmit.isEnabled = true }
                return@launch
            }
            val compressed = compressBitmap(bitmap)
            photoBase64 = Base64.encodeToString(compressed, Base64.NO_WRAP)
            Log.d(TAG, "Photo ready: ${photoBase64!!.length} base64 chars")
            withContext(Dispatchers.Main) {
                binding.imgPhotoPreview.setImageBitmap(bitmap)
                binding.imgPhotoPreview.visibility = android.view.View.VISIBLE
                binding.btnTakePhoto.text = "Retake Photo"
                binding.btnSubmit.isEnabled = true
            }
        }
    }

    private fun compressBitmap(src: Bitmap): ByteArray {
        val maxDim = 800
        val scaled = if (src.width > maxDim || src.height > maxDim) {
            val ratio = maxDim.toFloat() / maxOf(src.width, src.height)
            Bitmap.createScaledBitmap(src, (src.width * ratio).toInt(), (src.height * ratio).toInt(), true)
        } else src
        return ByteArrayOutputStream().also {
            scaled.compress(Bitmap.CompressFormat.JPEG, 60, it)
        }.toByteArray()
    }

    private fun validateAndSubmit() {
        val forkliftId = binding.etForkliftId.text?.toString().orEmpty().trim()
        if (forkliftId.isEmpty()) {
            binding.tilForkliftId.error = "Forklift ID is required"; return
        }
        binding.tilForkliftId.error = null

        val operatorName = binding.etOperatorName.text?.toString().orEmpty().trim()
        if (operatorName.isEmpty()) {
            binding.tilOperatorName.error = "Operator name is required"; return
        }
        binding.tilOperatorName.error = null

        val shift      = if (binding.rgShift.checkedRadioButtonId == R.id.rbDay) "DAY" else "NIGHT"
        val odometer   = binding.etOdometer.text?.toString().orEmpty().trim().toIntOrNull() ?: 0
        val remarks    = binding.etRemarks.text?.toString().orEmpty().trim()

        val tires      = if (binding.rgTires.checkedRadioButtonId      == R.id.rbTiresPass)      "PASS" else "FAIL"
        val body       = if (binding.rgBody.checkedRadioButtonId        == R.id.rbBodyPass)       "PASS" else "FAIL"
        val brakes     = if (binding.rgBrakes.checkedRadioButtonId      == R.id.rbBrakesPass)     "PASS" else "FAIL"
        val hydraulics = if (binding.rgHydraulics.checkedRadioButtonId  == R.id.rbHydraulicsPass) "PASS" else "FAIL"
        val horn       = if (binding.rgHorn.checkedRadioButtonId        == R.id.rbHornPass)       "PASS" else "FAIL"

        submitInspection(forkliftId, operatorName, shift, odometer, tires, body, brakes, hydraulics, horn, remarks)
    }

    private fun submitInspection(
        forkliftId: String, operatorName: String, shift: String, odometer: Int,
        tires: String, body: String, brakes: String, hydraulics: String, horn: String,
        remarks: String
    ) {
        binding.btnSubmit.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val json = JSONObject().apply {
                        put("FORKLIFT_ID",        forkliftId)
                        put("OPERATOR_NAME",      operatorName)
                        put("OPERATOR_ID",        "")
                        put("INSPECTION_DATE",    LocalDate.now().toString())
                        put("SHIFT",              shift)
                        put("ODOMETER",           odometer)
                        put("TIRE_PRESSURE",      tires)
                        put("PHYSICAL_CONDITION", body)
                        put("BRAKES",             brakes)
                        put("HYDRAULICS",         hydraulics)
                        put("HORN_LIGHTS",        horn)
                        put("REMARKS",            remarks)
                        if (photoBase64 != null) put("PHOTO_DATA", photoBase64)
                    }
                    val request = Request.Builder()
                        .url("${BtpConfig.FORKQA_BACKEND_URL}${BtpConfig.ODATA_SERVICE_PATH}/Inspections")
                        .post(json.toString().toRequestBody("application/json".toMediaType()))
                        .build()
                    val response = ClientProvider.get().newCall(request).execute()
                    if (!response.isSuccessful)
                        throw IllegalStateException("HTTP ${response.code}: ${response.body?.string()}")
                }
            }.onSuccess {
                setResult(RESULT_OK)
                finish()
            }.onFailure {
                Log.e(TAG, "Submit failed: ${it.message}", it)
                Snackbar.make(binding.root, it.message ?: "Submit failed", Snackbar.LENGTH_LONG).show()
                binding.btnSubmit.isEnabled = true
            }
        }
    }

    companion object {
        private const val TAG = "NewInspection"
    }
}
