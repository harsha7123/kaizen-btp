package com.sap.droidx.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.sap.droidx.R
import com.sap.droidx.data.ReportRepository
import com.sap.droidx.databinding.ActivityReportBinding
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ReportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReportBinding
    private var photoUri: Uri? = null
    private var urgency = "MEDIUM"

    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            binding.ivPhoto.setImageURI(null)
            binding.ivPhoto.setImageURI(photoUri)
            binding.ivPhoto.visibility = View.VISIBLE
            binding.tvPhotoHint.visibility = View.GONE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomBar.setNavigationOnClickListener { finish() }
        binding.chipMedium.isChecked = true

        binding.chipGroupUrgency.setOnCheckedStateChangeListener { _, checkedIds ->
            urgency = when (checkedIds.firstOrNull()) {
                R.id.chipHigh -> "HIGH"
                R.id.chipLow  -> "LOW"
                else          -> "MEDIUM"
            }
        }

        binding.btnCamera.setOnClickListener { launchCamera() }
        binding.btnSend.setOnClickListener { emailReport() }
        binding.btnDashboard.setOnClickListener { saveToDatabase() }

        updateEmailHint()

        // Pre-fill location with user's site
        val prefs = getSharedPreferences(QuestionnaireActivity.PREFS, Context.MODE_PRIVATE)
        val site  = prefs.getString(QuestionnaireActivity.KEY_SITE, "").orEmpty()
        if (site.isNotEmpty()) binding.etLocation.setText(site)
    }

    private fun updateEmailHint() {
        val prefs = getSharedPreferences(QuestionnaireActivity.PREFS, Context.MODE_PRIVATE)
        val sup = prefs.getString(QuestionnaireActivity.KEY_SUPERVISOR_EMAIL, "").orEmpty()
        binding.tvEmailHint.text = if (sup.isNotEmpty())
            "Will send to: $sup"
        else
            "⚠  No supervisor email — set it in Profile first"
    }

    private fun launchCamera() {
        val photoFile = File(cacheDir, "reports/report_${System.currentTimeMillis()}.jpg").also {
            it.parentFile?.mkdirs()
        }
        photoUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile)
        cameraLauncher.launch(photoUri!!)
    }

    // ── Email only — no database ──────────────────────────────────────────
    private fun emailReport() {
        val description = binding.etDescription.text?.toString().orEmpty().trim()
        val location    = binding.etLocation.text?.toString().orEmpty().trim()
        if (!validateFields(description, location)) return

        val prefs           = getSharedPreferences(QuestionnaireActivity.PREFS, Context.MODE_PRIVATE)
        val myEmail         = prefs.getString(QuestionnaireActivity.KEY_EMAIL, "").orEmpty()
        val supervisorEmail = prefs.getString(QuestionnaireActivity.KEY_SUPERVISOR_EMAIL, "").orEmpty()
        val operator        = prefs.getString(QuestionnaireActivity.KEY_NAME, "Unknown").orEmpty()
        val site            = prefs.getString(QuestionnaireActivity.KEY_SITE, "").orEmpty()

        if (supervisorEmail.isEmpty()) {
            Snackbar.make(binding.root, "Set supervisor email in Profile first", Snackbar.LENGTH_LONG).show()
            return
        }

        val now     = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val subject = "[$urgency] Incident Report — $location — $now"
        val body    = buildString {
            appendLine("INCIDENT REPORT")
            appendLine("═══════════════════════════")
            appendLine("Date/Time  : $now")
            appendLine("Operator   : $operator")
            appendLine("Site       : $site")
            appendLine("Location   : $location")
            appendLine("Urgency    : $urgency")
            appendLine()
            appendLine("Description:")
            appendLine(description)
            if (photoUri != null) appendLine("\n[Photo attached]")
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (photoUri != null) "image/jpeg" else "text/plain"
            setPackage("com.google.android.gm")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(supervisorEmail))
            if (myEmail.isNotEmpty()) putExtra(Intent.EXTRA_CC, arrayOf(myEmail))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            photoUri?.let {
                putExtra(Intent.EXTRA_STREAM, it)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }

        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            intent.setPackage(null)
            startActivity(Intent.createChooser(intent, "Send Report via"))
        }
    }

    // ── Database only — no email ──────────────────────────────────────────
    private fun saveToDatabase() {
        val description = binding.etDescription.text?.toString().orEmpty().trim()
        val location    = binding.etLocation.text?.toString().orEmpty().trim()
        if (!validateFields(description, location)) return

        val prefs    = getSharedPreferences(QuestionnaireActivity.PREFS, Context.MODE_PRIVATE)
        val operator = prefs.getString(QuestionnaireActivity.KEY_NAME, "Unknown").orEmpty()
        val site     = prefs.getString(QuestionnaireActivity.KEY_SITE, "").orEmpty()

        val photoBase64: String? = photoUri?.let { uri ->
            try {
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
            } catch (e: Exception) { null }
        }

        binding.btnDashboard.isEnabled = false
        lifecycleScope.launch {
            try {
                ReportRepository().createReport(
                    operator    = operator,
                    site        = site,
                    location    = location,
                    description = description,
                    urgency     = urgency,
                    photoBase64 = photoBase64
                )
                Snackbar.make(binding.root, "Report saved to dashboard", Snackbar.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Snackbar.make(binding.root, "Failed: ${e.message}", Snackbar.LENGTH_LONG).show()
            } finally {
                binding.btnDashboard.isEnabled = true
            }
        }
    }

    private fun validateFields(description: String, location: String): Boolean {
        var ok = true
        if (description.isEmpty()) {
            binding.tilDescription.error = "Description is required"
            ok = false
        } else {
            binding.tilDescription.error = null
        }
        if (location.isEmpty()) {
            binding.tilLocation.error = "Location is required"
            ok = false
        } else {
            binding.tilLocation.error = null
        }
        return ok
    }
}
