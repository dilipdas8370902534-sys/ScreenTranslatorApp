package com.example.screentranslator

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
        setupLanguageList()
        setupToggle()
        setupExitDialog()
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

    private fun setupLanguageList() {
        binding.languageContainer.removeAllViews()

        supportedLanguages.forEach { (code, name) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 8, 0, 8) }
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val nameText = TextView(this).apply {
                text = name
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnDownload = Button(this).apply {
                text = "⬇️ Download"
                setBackgroundColor(Color.parseColor("#4CAF50"))
                setTextColor(Color.WHITE)
                setOnClickListener { downloadModel(code, name) }
            }

            val btnDelete = Button(this).apply {
                text = "🗑️"
                setBackgroundColor(Color.parseColor("#F44336"))
                setTextColor(Color.WHITE)
                setOnClickListener { deleteModel(code, name) }
            }

            row.addView(nameText)
            row.addView(btnDownload)
            row.addView(btnDelete)
            binding.languageContainer.addView(row)
        }
    }

    private fun downloadModel(langCode: String, langName: String) {
        val language = TranslateLanguage.fromLanguageTag(langCode) ?: return
        val modelManager = RemoteModelManager.getInstance()
        val model = TranslateRemoteModel.Builder(language).build()
        Toast.makeText(this, "$langName ডাউনলোড হচ্ছে...", Toast.LENGTH_SHORT).show()
        modelManager.download(model, DownloadConditions.Builder().build())
            .addOnSuccessListener { Toast.makeText(this, "$langName ডাউনলোড সফল!", Toast.LENGTH_SHORT).show() }
            .addOnFailureListener { Toast.makeText(this, "$langName ডাউনলোড ফেইল!", Toast.LENGTH_SHORT).show() }
    }

    private fun deleteModel(langCode: String, langName: String) {
        val language = TranslateLanguage.fromLanguageTag(langCode) ?: return
        val modelManager = RemoteModelManager.getInstance()
        val model = TranslateRemoteModel.Builder(language).build()
        modelManager.deleteDownloadedModel(model)
            .addOnSuccessListener { Toast.makeText(this, "$langName ডিলিট করা হয়েছে!", Toast.LENGTH_SHORT).show() }
    }

    private fun setupToggle() {
        binding.toggleStartStop.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    overlayPermissionLauncher.launch(intent)
                } else {
                    startScreenCapture()
                }
            } else {
                val intent = Intent(this, ScreenTranslatorService::class.java)
                stopService(intent)
            }
        }
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

    // ব্যাক বাটনে ক্লিক করলে Exit অপশন দেখানোর ফাংশন
    private fun setupExitDialog() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("অ্যাপ বন্ধ করবেন?")
                    .setMessage("আপনি কি অ্যাপটি পুরোপুরি বন্ধ করতে চান? 'Exit' করলে ব্যাকগ্রাউন্ড সার্ভিস বন্ধ হয়ে RAM ও ব্যাটারি বাঁচবে।")
                    .setPositiveButton("Exit") { _, _ ->
                        // সার্ভিস পুরোপুরি বন্ধ করে RAM ক্লিয়ার করবে
                        val intent = Intent(this@MainActivity, ScreenTranslatorService::class.java)
                        stopService(intent)
                        finishAffinity() 
                    }
                    .setNegativeButton("Non Exit") { _, _ ->
                        // সার্ভিস চালু রেখে শুধু মিনিমাইজ করবে
                        moveTaskToBack(true)
                    }
                    .setCancelable(true)
                    .show()
            }
        })
    }
}
