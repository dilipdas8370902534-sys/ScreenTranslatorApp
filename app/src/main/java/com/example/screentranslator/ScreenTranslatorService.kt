package com.example.screentranslator

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.*
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.*
import androidx.core.app.NotificationCompat

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class ScreenTranslatorService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: ImageView? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    
    // পার্সেন্টেজ ভিউ
    private var progressTextView: TextView? = null
    private var progressParams: WindowManager.LayoutParams? = null

    private var fullscreenOverlayContainer: FrameLayout? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var sourceLangCode = "en"
    private var textRecognizer: TextRecognizer? = null

    private var apiUrl = ""
    private var modelName = ""
    private var apiKey = ""

    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var captureRequest = false
    private val currentOverlays = mutableListOf<TextView>()

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_translator_channel"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        loadAiSettings()
    }

    private fun loadAiSettings() {
        val sharedPref = getSharedPreferences("AiSettings", Context.MODE_PRIVATE)
        apiUrl = sharedPref.getString("apiUrl", "") ?: ""
        modelName = sharedPref.getString("modelName", "") ?: ""
        apiKey = sharedPref.getString("apiKey", "") ?: ""
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            stopServiceAndCleanup()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }

        intent?.let {
            sourceLangCode = it.getStringExtra("SOURCE_LANG") ?: "en"
            setupScanner()

            val resultCode = it.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
            val dataIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableExtra("DATA_INTENT", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableExtra<Intent>("DATA_INTENT")
            }

            if (resultCode == Activity.RESULT_OK && dataIntent != null && mediaProjection == null) {
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, dataIntent)
                setupVirtualDisplay()
            }
        }

        if (mediaProjection != null) {
            showFloatingIcon()
            showProgressIndicator()
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun setupScanner() {
        val recognizerOptions = when (sourceLangCode) {
            "zh" -> ChineseTextRecognizerOptions.Builder().build()
            "hi" -> DevanagariTextRecognizerOptions.Builder().build()
            "ja" -> JapaneseTextRecognizerOptions.Builder().build()
            "ko" -> KoreanTextRecognizerOptions.Builder().build()
            else -> TextRecognizerOptions.DEFAULT_OPTIONS
        }
        textRecognizer = TextRecognition.getClient(recognizerOptions)
    }

    // পার্সেন্টেজ দেখানোর ভিউ তৈরি করা (ডান কোণায়)
    private fun showProgressIndicator() {
        if (progressTextView != null) return

        progressTextView = TextView(this).apply {
            text = ""
            setTextColor(Color.YELLOW)
            setBackgroundColor(Color.parseColor("#80000000"))
            setPadding(20, 10, 20, 10)
            textSize = 12f
            visibility = View.GONE
        }

        progressParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 100 // স্ট্যাটাস বারের ঠিক নিচে
        }

        windowManager.addView(progressTextView, progressParams)
    }

    private fun updateProgress(percent: Int) {
        mainHandler.post {
            progressTextView?.apply {
                if (percent in 1..99) {
                    visibility = View.VISIBLE
                    text = "অনুবাদ হচ্ছে... $percent%"
                } else {
                    visibility = View.GONE
                }
            }
        }
    }

    private fun setupVirtualDisplay() {
        try {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null
            )
            
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    if (captureRequest) {
                        captureRequest = false
                        val bitmap = imageToBitmap(image)
                        image.close()
                        if (bitmap != null) processBitmap(bitmap) else resetCapture()
                    } else {
                        image.close()
                    }
                }
            }, mainHandler)
        } catch (e: Exception) { }
    }

    private fun showFloatingIcon() {
        if (floatingView != null) return
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.BLUE)
            setStroke(15, Color.RED)
        }
        floatingView = ImageView(this).apply {
            background = shape
            elevation = 10f
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)

        floatingParams = WindowManager.LayoutParams(
            90, 90,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = metrics.heightPixels / 3
        }

        var initialX = 0; var initialY = 0; var initialTouchX = 0f; var initialTouchY = 0f; var isMoved = false
        floatingView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = floatingParams!!.x; initialY = floatingParams!!.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    isMoved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt(); val deltaY = (event.rawY - initialTouchY).toInt()
                    if (abs(deltaX) > 10 || abs(deltaY) > 10) {
                        isMoved = true
                        floatingParams!!.x = initialX + deltaX; floatingParams!!.y = initialY + deltaY
                        windowManager.updateViewLayout(floatingView, floatingParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isMoved) triggerCapture() 
                    true
                }
                else -> false
            }
        }
        windowManager.addView(floatingView, floatingParams)
    }

    private fun triggerCapture() {
        if (mediaProjection == null || virtualDisplay == null) return
        if (captureRequest) return 
        clearOverlays()
        startLoadingAnimation()
        updateProgress(10) // ১০% শুরু
        mainHandler.postDelayed({ captureRequest = true }, 200)
    }

    private fun startLoadingAnimation() {
        mainHandler.post {
            val rotate = RotateAnimation(0f, 360f, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f).apply {
                duration = 1000; repeatCount = Animation.INFINITE; interpolator = LinearInterpolator()
            }
            floatingView?.startAnimation(rotate)
        }
    }

    private fun stopLoadingAnimation() = mainHandler.post { floatingView?.clearAnimation() }

    private fun resetCapture() {
        captureRequest = false
        stopLoadingAnimation()
        updateProgress(0)
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmap = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind(); bitmap.copyPixelsFromBuffer(buffer)
        val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        if (bitmap != cropped) bitmap.recycle()
        return cropped
    }

    private fun processBitmap(bitmap: Bitmap) {
        updateProgress(30) // ৩০% স্ক্যানিং শুরু
        try {
            textRecognizer?.process(InputImage.fromBitmap(bitmap, 0))
                ?.addOnSuccessListener { text ->
                    val blocks = text.textBlocks
                    if (blocks.isEmpty()) {
                        resetCapture()
                        return@addOnSuccessListener
                    }
                    updateProgress(50) // ৫০% স্ক্যান শেষ
                    backgroundScope.launch {
                        val translatedBlocks = mutableListOf<Pair<Rect, String>>()
                        blocks.forEachIndexed { index, block ->
                            val translated = translateWithAI(block.text)
                            if (translated != null) translatedBlocks.add((block.boundingBox!!) to translated)
                            
                            // পার্সেন্টেজ আপডেট (৫০ থেকে ৯০ এর মধ্যে)
                            val p = 50 + ((index + 1) * 40 / blocks.size)
                            updateProgress(p)
                        }
                        withContext(Dispatchers.Main) {
                            showOverlays(translatedBlocks)
                            updateProgress(100) // শেষ
                            bitmap.recycle(); resetCapture()
                        }
                    }
                }
                ?.addOnFailureListener { resetCapture() }
        } catch (e: Exception) { resetCapture() }
    }

    private suspend fun translateWithAI(text: String): String? = withContext(Dispatchers.IO) {
        try {
            val jsonBody = JSONObject().apply {
                put("model", modelName)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "user"); put("content", "Translate to natural Bengali: $text") })
                })
            }
            val request = Request.Builder().url(apiUrl).addHeader("Authorization", "Bearer $apiKey")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType())).build()
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string()
            if (response.isSuccessful && body != null) {
                JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            } else null
        } catch (e: Exception) { null }
    }

    private fun showOverlays(blocks: List<Pair<Rect, String>>) {
        clearOverlays()
        val container = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT); setOnTouchListener { _, _ -> clearOverlays(); true } }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(container, params)
        fullscreenOverlayContainer = container
        val statusBar = resources.getDimensionPixelSize(resources.getIdentifier("status_bar_height", "dimen", "android"))

        blocks.forEach { (rect, text) ->
            val tv = TextView(this).apply {
                this.text = text; setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#E6000000"))
                gravity = Gravity.CENTER; setPadding(0, 0, 0, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setAutoSizeTextTypeUniformWithConfiguration(8, 100, 1, TypedValue.COMPLEX_UNIT_SP)
                layoutParams = FrameLayout.LayoutParams(rect.width(), rect.height()).apply { setMargins(rect.left, rect.top - statusBar, 0, 0) }
            }
            container.addView(tv)
        }
    }

    private fun clearOverlays() {
        fullscreenOverlayContainer?.let { try { windowManager.removeView(it) } catch (e: Exception) { } }
        fullscreenOverlayContainer = null
    }

    private fun stopServiceAndCleanup() {
        clearOverlays()
        try { 
            floatingView?.let { windowManager.removeView(it) }
            progressTextView?.let { windowManager.removeView(it) }
        } catch (e: Exception) { }
        floatingView = null; progressTextView = null
        mediaProjection?.stop(); stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Translator", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Screen Translator").setSmallIcon(android.R.drawable.ic_menu_camera).setOngoing(true).build()

    override fun onDestroy() { super.onDestroy(); backgroundScope.cancel(); stopServiceAndCleanup() }
}
