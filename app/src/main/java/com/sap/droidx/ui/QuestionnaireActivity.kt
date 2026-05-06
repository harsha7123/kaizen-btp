package com.sap.droidx.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.sap.droidx.R
import com.sap.droidx.databinding.ActivityQuestionnaireBinding
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class QuestionnaireActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQuestionnaireBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Skip to main only on first-run launch; if opened from nav, always show form
        val fromNav = intent.getBooleanExtra(EXTRA_FROM_NAV, false)
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!fromNav && prefs.getString(KEY_NAME, "").orEmpty().isNotBlank()) {
            goToMain()
            return
        }

        binding = ActivityQuestionnaireBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Always show detected timezone
        val zone   = ZoneId.systemDefault()
        val tzName = zone.getDisplayName(TextStyle.SHORT, Locale.getDefault())
        val offset = ZonedDateTime.now(zone).format(DateTimeFormatter.ofPattern("OOOO"))
        binding.etTimezone.setText("$tzName  ($offset)  —  ${zone.id}")

        if (fromNav) binding.bottomBar.setNavigationOnClickListener { finish() }

        if (fromNav) {
            binding.etName.setText(prefs.getString(KEY_NAME, ""))
            binding.etSite.setText(prefs.getString(KEY_SITE, ""))
            binding.etEmail.setText(prefs.getString(KEY_EMAIL, ""))
            binding.etSupervisorEmail.setText(prefs.getString(KEY_SUPERVISOR_EMAIL, ""))
        }

        binding.btnContinue.setOnClickListener {
            val name = binding.etName.text?.toString().orEmpty().trim()
            val site = binding.etSite.text?.toString().orEmpty().trim()

            if (name.isEmpty()) {
                binding.tilName.error = "Name is required"
                return@setOnClickListener
            }
            binding.tilName.error = null

            if (site.isEmpty()) {
                binding.tilSite.error = "Site is required"
                return@setOnClickListener
            }
            binding.tilSite.error = null

            val email = binding.etEmail.text?.toString().orEmpty().trim()
            if (email.isNotEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                binding.tilEmail.error = "Enter a valid email address"
                return@setOnClickListener
            }
            binding.tilEmail.error = null

            val supervisorEmail = binding.etSupervisorEmail.text?.toString().orEmpty().trim()
            if (supervisorEmail.isNotEmpty() && !android.util.Patterns.EMAIL_ADDRESS.matcher(supervisorEmail).matches()) {
                binding.tilSupervisorEmail.error = "Enter a valid email address"
                return@setOnClickListener
            }
            binding.tilSupervisorEmail.error = null

            val role = when (binding.rgRole.checkedRadioButtonId) {
                R.id.rbInspector  -> ROLE_INSPECTOR
                R.id.rbSupervisor -> ROLE_SUPERVISOR
                else              -> ROLE_OPERATOR
            }

            prefs.edit()
                .putString(KEY_NAME, name)
                .putString(KEY_SITE, site)
                .putString(KEY_EMAIL, email)
                .putString(KEY_SUPERVISOR_EMAIL, supervisorEmail)
                .putString(KEY_ROLE, role)
                .putString(KEY_TIMEZONE, ZoneId.systemDefault().id)
                .apply()

            goToMain()
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, DashboardActivity::class.java))
        finish()
    }

    companion object {
        const val PREFS                = "droidx_profile"
        const val KEY_NAME             = "operator_name"
        const val KEY_SITE             = "site"
        const val KEY_EMAIL            = "email"
        const val KEY_SUPERVISOR_EMAIL = "supervisor_email"
        const val KEY_ROLE             = "role"
        const val KEY_TIMEZONE         = "timezone"
        const val ROLE_OPERATOR        = "Forklift Operator"
        const val ROLE_INSPECTOR       = "Inspector"
        const val ROLE_SUPERVISOR      = "Supervisor"
        const val EXTRA_FROM_NAV       = "from_nav"
    }
}
