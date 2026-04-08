package com.example.screentranslator

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.screentranslator.databinding.ActivityMainBinding

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mediaProjectionManager: MediaProjectionManager? = null

    private val supportedLanguages = listOf(
        "en" to "English",
        "bn" to "Bengali",
        "hi" to "Hindi",
        "zh" to "Chinese",
        "es" to "Spanish",
        "fr" to "French",
        "ja" to "Japanese",
        "ru" to "Russian",
        "ar" to "Arabic"
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

        setupLanguageSpinners()
        setupButtons()
    }

    private fun setupLanguageSpinners() {
        val languageNames = supportedLanguages.map { it.second }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languageNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSource.adapter = adapter
        binding.spinnerTarget.adapter = adapter

        binding.spinnerSource.setSelection(0)
        binding.spinnerTarget.setSelection(1)
    }

    private fun setupButtons() {
        binding.btnDownload.setOnClickListener {
            val sourceCode = supportedLanguages[binding.spinnerSource.selectedItemPosition].first
            val targetCode = supportedLanguages[binding.spinnerTarget.selectedItemPosition].first
            downloadModel(sourceCode)
            downloadModel(targetCode)
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
                stopTranslationService()
            }
        }
    }

    private fun downloadModel(langCode: String) {
        val language = TranslateLanguage.fromLanguageTag(langCode) ?: return
        val modelManager = RemoteModelManager.getInstance()
        val model = TranslateRemoteModel.Builder(language).build()
        Toast.makeText(this, "$langCode ভাষা ডাউনলোড হচ্ছে...", Toast.LENGTH_SHORT).show()
        modelManager.download(model, DownloadConditions.Builder().build())
            .addOnSuccessListener { Toast.makeText(this, "$langCode ডাউনলোড সফল হয়েছে!", Toast.LENGTH_SHORT).show() }
            .addOnFailureListener { Toast.makeText(this, "$langCode ডাউনলোড ফেইল হয়েছে!", Toast.LENGTH_SHORT).show() }
    }

    private fun startScreenCapture() {
        val intent = mediaProjectionManager?.createScreenCaptureIntent()
        if (intent != null) {
            screenCaptureLauncher.launch(intent)
        }
    }

    private fun startServiceAndFloatingIcon(resultCode: Int, data: Intent) {
        val sourceCode = supportedLanguages[binding.spinnerSource.selectedItemPosition].first
        val targetCode = supportedLanguages[binding.spinnerTarget.selectedItemPosition].first

        val serviceIntent = Intent(this, ScreenTranslatorService::class.java).apply {
            putExtra("SOURCE_LANG", sourceCode)
            putExtra("TARGET_LANG", targetCode)
            putExtra("RESULT_CODE", resultCode)
            putExtra("DATA_INTENT", data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopTranslationService() {
        val intent = Intent(this, ScreenTranslatorService::class.java)
        stopService(intent)
    }
}
