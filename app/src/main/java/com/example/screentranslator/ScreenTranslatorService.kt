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
    
    private var loadingTextView: TextView? = null
    private var loadingParams: WindowManager.LayoutParams? = null

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
                
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
                        releaseVirtualDisplay()
                    }
                }, mainHandler)
                
                setupVirtualDisplay()
            }
        }

        if (mediaProjection != null) {
            showFloatingIcon()
            showLoadingIndicator()
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

    private fun showLoadingIndicator() {
        if (loadingTextView != null) return

        val shape = GradientDrawable().apply {
            setColor(Color.parseColor("#D9000000")) 
            cornerRadius = 40f 
        }

        loadingTextView = TextView(this).apply {
            text = "অনুবাদ করা হচ্ছে..."
            setTextColor(Color.WHITE)
            background = shape
            setPadding(60, 30, 60, 30)
            textSize = 16f
            gravity = Gravity.CENTER
            visibility = View.GONE
            elevation = 20f
        }

        loadingParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER 
        }

        windowManager.addView(loadingTextView, loadingParams)
    }

    private fun showLoadingText(show: Boolean) {
        mainHandler.post {
            loadingTextView?.visibility = if (show) View.VISIBLE else View.GONE
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
                try {
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
                } catch (e: Exception) {
                    e.printStackTrace()
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
            180, 180,
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
        showLoadingText(true)
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
        showLoadingText(false) 
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width
            val bitmap = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
            buffer.rewind(); bitmap.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            if (bitmap != cropped) bitmap.recycle()
            cropped
        } catch (e: Exception) { null }
    }

    private fun processBitmap(bitmap: Bitmap) {
        try {
            textRecognizer?.process(InputImage.fromBitmap(bitmap, 0))
                ?.addOnSuccessListener { text ->
                    val blocks = text.textBlocks
                    if (blocks.isEmpty()) {
                        mainHandler.post { Toast.makeText(this, "স্ক্রিনে কোনো লেখা পাওয়া যায়নি!", Toast.LENGTH_SHORT).show() }
                        bitmap.recycle(); resetCapture()
                        return@addOnSuccessListener
                    }
                    
                    backgroundScope.launch {
                        val deferredBlocks = blocks.map { block ->
                            async(Dispatchers.IO) {
                                val cleanText = block.text.replace("\n", " ") 
                                val translated = translateWithAI(cleanText)
                                if (!translated.isNullOrEmpty() && !translated.contains("API Error")) {
                                    Pair(block.boundingBox!!, translated)
                                } else null
                            }
                        }
                        
                        val translatedBlocks = deferredBlocks.awaitAll().filterNotNull()
                        
                        withContext(Dispatchers.Main) {
                            showOverlays(translatedBlocks)
                            bitmap.recycle(); resetCapture()
                        }
                    }
                }
                ?.addOnFailureListener { bitmap.recycle(); resetCapture() }
        } catch (e: Exception) { bitmap.recycle(); resetCapture() }
    }

    private suspend fun translateWithAI(text: String): String? = withContext(Dispatchers.IO) {
        if (apiKey.isEmpty() || apiUrl.isEmpty() || modelName.isEmpty()) return@withContext "API Config Missing"

        val prompt = "You are an expert translator. Translate the text into fluent Bengali. If the text is a username, a fragment, a symbol, or incomplete, translate or transliterate it as best as you can. NEVER return warnings, notes, or ask for more context. Return ONLY the translated Bengali text without quotes:\n\n$text"

        try {
            val jsonBody = JSONObject().apply {
                put("model", modelName)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply { put("role", "user"); put("content", prompt) })
                })
                put("temperature", 0.1)
            }
            val request = Request.Builder().url(apiUrl).addHeader("Authorization", "Bearer $apiKey")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType())).build()
            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string()
            if (response.isSuccessful && body != null) {
                JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim().removeSurrounding("\"")
            } else null
        } catch (e: Exception) { null }
    }

    private fun getStatusBarHeight(): Int {
        var result = 0
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) result = resources.getDimensionPixelSize(resourceId)
        return result
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
        
        val statusBarOffset = getStatusBarHeight()
        val displayMetrics = resources.displayMetrics

        val bgShape = GradientDrawable().apply {
            setColor(Color.parseColor("#E6121212")) 
            cornerRadius = 8f 
        }

        for ((rect, translatedText) in blocks) {
            val textView = TextView(this).apply {
                text = translatedText
                setTextColor(Color.WHITE)
                background = bgShape 
                gravity = Gravity.CENTER_VERTICAL
                
                setPadding(10, 6, 10, 6) 
                textSize = 13f 
                
                val finalY = if (rect.top - statusBarOffset < 0) 0 else rect.top - statusBarOffset
                
                val minBoxWidth = 150
                val boxWidth = if (rect.width() < minBoxWidth) minBoxWidth else rect.width()

                layoutParams = FrameLayout.LayoutParams(boxWidth, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(rect.left, finalY, 0, 0)
                }
                
                maxWidth = displayMetrics.widthPixels - rect.left - 20 
            }
            container.addView(textView)
            currentOverlays.add(textView)
        }
    }

    private fun clearOverlays() {
        fullscreenOverlayContainer?.let { try { windowManager.removeView(it) } catch (e: Exception) { } }
        fullscreenOverlayContainer = null
        currentOverlays.clear()
    }

    private fun releaseVirtualDisplay() {
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null); imageReader?.close(); imageReader = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Translator", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Screen Translator").setSmallIcon(android.R.drawable.ic_menu_camera).setOngoing(true).build()

    fun stopServiceAndCleanup() {
        clearOverlays(); releaseVirtualDisplay()
        try { 
            floatingView?.let { windowManager.removeView(it) }
            loadingTextView?.let { windowManager.removeView(it) }
        } catch (e: Exception) { }
        floatingView = null; loadingTextView = null
        mediaProjection?.stop(); stopSelf()
    }

    override fun onDestroy() { super.onDestroy(); backgroundScope.cancel(); stopServiceAndCleanup() }
}
