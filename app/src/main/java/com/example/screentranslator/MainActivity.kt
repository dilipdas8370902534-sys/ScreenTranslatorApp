package com.example.screentranslator

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.screentranslator.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mediaProjectionManager: MediaProjectionManager? = null

    // গুগল স্ক্যানার যে ভাষাগুলো অফলাইনে সাপোর্ট করে
    private val supportedLanguages = listOf(
        "en" to "English (Latin Script)",
        "zh" to "Chinese (চাইনিজ)",
        "hi" to "Hindi (হিন্দি)",
        "ja" to "Japanese (জাপানিজ)",
        "ko" to "Korean (কোরিয়ান)"
    )

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            startScreenCapture()
        } else {
            Toast.makeText(this, "দয়া করে Display over other apps পারমিশন দিন!", Toast.LENGTH_LONG).show()
            binding.toggleStartStop.isChecked = false
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startServiceAndFloatingIcon(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "স্ক্রিন রেকর্ড পারমিশন দেওয়া হয়নি!", Toast.LENGTH_SHORT).show()
            binding.toggleStartStop.isChecked = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupLanguageSpinner()
        loadAiSettings()
        setupButtons()
        setupExitDialog()
    }

    private fun setupLanguageSpinner() {
        val languageNames = supportedLanguages.map { it.second }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languageNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSource.adapter = adapter
    }

    private fun loadAiSettings() {
        val sharedPref = getSharedPreferences("AiSettings", Context.MODE_PRIVATE)
        binding.etApiUrl.setText(sharedPref.getString("apiUrl", ""))
        binding.etModelName.setText(sharedPref.getString("modelName", ""))
        binding.etApiKey.setText(sharedPref.getString("apiKey", ""))
    }

    private fun setupButtons() {
        binding.btnSaveConfig.setOnClickListener {
            val sharedPref = getSharedPreferences("AiSettings", Context.MODE_PRIVATE)
            with(sharedPref.edit()) {
                putString("apiUrl", binding.etApiUrl.text.toString().trim())
                putString("modelName", binding.etModelName.text.toString().trim())
                putString("apiKey", binding.etApiKey.text.toString().trim())
                apply()
            }
            Toast.makeText(this, "AI Settings Saved!", Toast.LENGTH_SHORT).show()
        }

        binding.toggleStartStop.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    overlayPermissionLauncher.launch(intent)
                } else {
                    startScreenCapture()
                }
            } else {
                stopService(Intent(this, ScreenTranslatorService::class.java))
            }
        }
    }

    private fun startScreenCapture() {
        val sharedPref = getSharedPreferences("AiSettings", Context.MODE_PRIVATE)
        if (sharedPref.getString("apiKey", "").isNullOrEmpty()) {
            Toast.makeText(this, "আগে API Key সেভ করুন!", Toast.LENGTH_LONG).show()
            binding.toggleStartStop.isChecked = false
            return
        }

        val intent = mediaProjectionManager?.createScreenCaptureIntent()
        if (intent != null) {
            screenCaptureLauncher.launch(intent)
        }
    }

    private fun startServiceAndFloatingIcon(resultCode: Int, data: Intent) {
        val sourceCode = supportedLanguages[binding.spinnerSource.selectedItemPosition].first

        val serviceIntent = Intent(this, ScreenTranslatorService::class.java).apply {
            putExtra("SOURCE_LANG", sourceCode)
            putExtra("RESULT_CODE", resultCode)
            putExtra("DATA_INTENT", data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun setupExitDialog() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("অ্যাপ বন্ধ করবেন?")
                    .setMessage("আপনি কি অ্যাপটি পুরোপুরি বন্ধ করতে চান?")
                    .setPositiveButton("Exit") { _, _ ->
                        stopService(Intent(this@MainActivity, ScreenTranslatorService::class.java))
                        finishAffinity() 
                    }
                    .setNegativeButton("Non Exit") { _, _ ->
                        moveTaskToBack(true)
                    }
                    .setCancelable(true)
                    .show()
            }
        })
    }
}
