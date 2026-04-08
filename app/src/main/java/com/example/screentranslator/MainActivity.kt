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
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var screenTranslatorService: ScreenTranslatorService? = null
    private var isBound = false

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
                Toast.makeText(this, "স্ক্রিন রেকর্ড পারমিশন দেওয়া হয়নি!", Toast.LENGTH_SHORT).show()
                binding.toggleStartStop.isChecked = false
            }
        } else {
            Toast.makeText(this, "ক্যানসেল করা হয়েছে!", Toast.LENGTH_SHORT).show()
            binding.toggleStartStop.isChecked = false
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
        setupButtons()

        val serviceIntent = Intent(this, ScreenTranslatorService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setupLanguageSpinners() {
        val languageNames = supportedLanguages.map { it.second }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languageNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerSource.adapter = adapter
        binding.spinnerTarget.adapter = adapter

        // Default: English to Bengali
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
        Toast.makeText(this, "$langCode ভাষা ডাউনলোড হচ্ছে, দয়া করে অপেক্ষা করুন...", Toast.LENGTH_SHORT).show()
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
    }

    private fun stopTranslationService() {
        screenTranslatorService?.stopServiceAndCleanup()
        val intent = Intent(this, ScreenTranslatorService::class.java)
        stopService(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
