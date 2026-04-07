package com.example.screentranslator

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.screentranslator.databinding.ActivityMainBinding
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.translate.TranslateLanguage
import com.google.mlkit.translate.TranslateRemoteModel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var screenTranslatorService: ScreenTranslatorService? = null
    private var isBound = false

    private val supportedLanguages = listOf(
        "en" to "English",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
        "it" to "Italian",
        "pt" to "Portuguese",
        "ru" to "Russian",
        "ja" to "Japanese",
        "ko" to "Korean",
        "zh" to "Chinese",
        "ar" to "Arabic",
        "hi" to "Hindi"
    )

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            startScreenCapture()
        } else {
            Toast.makeText(this, "Overlay permission required", Toast.LENGTH_SHORT).show()
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val mediaProjection = mediaProjectionManager?.getMediaProjection(result.resultCode, result.data!!)
            if (mediaProjection != null && isBound && screenTranslatorService != null) {
                screenTranslatorService?.setMediaProjection(mediaProjection)
                startServiceAndFloatingIcon()
            } else {
                Toast.makeText(this, "Failed to get MediaProjection", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ScreenTranslatorService.LocalBinder
            screenTranslatorService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            screenTranslatorService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupLanguageSpinners()
        setupStartStopButton()
        preDownloadModels()
    }

    private fun setupLanguageSpinners() {
        val languageNames = supportedLanguages.map { it.second }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languageNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSource.adapter = adapter
        binding.spinnerTarget.adapter = adapter

        binding.spinnerSource.setSelection(supportedLanguages.indexOfFirst { it.first == "en" })
        binding.spinnerTarget.setSelection(supportedLanguages.indexOfFirst { it.first == "es" })
    }

    private fun setupStartStopButton() {
        binding.toggleStartStop.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startTranslationService()
            } else {
                stopTranslationService()
            }
        }
    }

    private fun startTranslationService() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }
        startScreenCapture()
    }

    private fun requestOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        overlayPermissionLauncher.launch(intent)
    }

    private fun startScreenCapture() {
        val intent = mediaProjectionManager?.createScreenCaptureIntent()
        if (intent != null) {
            screenCaptureLauncher.launch(intent)
        } else {
            Toast.makeText(this, "MediaProjection not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startServiceAndFloatingIcon() {
        val sourceCode = supportedLanguages[binding.spinnerSource.selectedItemPosition].first
        val targetCode = supportedLanguages[binding.spinnerTarget.selectedItemPosition].first

        val serviceIntent = Intent(this, ScreenTranslatorService::class.java).apply {
            putExtra("SOURCE_LANG", sourceCode)
            putExtra("TARGET_LANG", targetCode)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        binding.toggleStartStop.isChecked = true
    }

    private fun stopTranslationService() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        screenTranslatorService?.stopServiceAndCleanup()
        screenTranslatorService = null
        val intent = Intent(this, ScreenTranslatorService::class.java)
        stopService(intent)
        binding.toggleStartStop.isChecked = false
    }

    private fun preDownloadModels() {
        val modelManager = RemoteModelManager.getInstance()
        val uniqueLanguages = supportedLanguages.map { it.first }.distinct()
        uniqueLanguages.forEach { langCode ->
            val language = TranslateLanguage.fromLanguageCode(langCode)
            if (language != null) {
                val model = TranslateRemoteModel.Builder(language).build()
                modelManager.download(model, DownloadConditions.Builder().build())
                    .addOnSuccessListener { /* pre‑downloaded */ }
                    .addOnFailureListener { /* will download on first use */ }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
