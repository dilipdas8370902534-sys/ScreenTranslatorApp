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

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

import kotlinx.coroutines.*
import kotlin.math.abs

class ScreenTranslatorService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: ImageView? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    private var fullscreenOverlayContainer: FrameLayout? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var sourceLangCode = "en"
    private var targetLangCode = "bn"
    private var translator: Translator? = null
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var isCapturing = false
    private val currentOverlays = mutableListOf<TextView>()

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_translator_channel"
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            stopCapture()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
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
            targetLangCode = it.getStringExtra("TARGET_LANG") ?: "bn"
            setupTranslator()

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
                mediaProjection?.registerCallback(projectionCallback, mainHandler)
            }
        }

        if (mediaProjection != null) {
            showFloatingIcon()
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun setupTranslator() {
        val sourceLang = TranslateLanguage.fromLanguageTag(sourceLangCode)
        val targetLang = TranslateLanguage.fromLanguageTag(targetLangCode)
        if (sourceLang != null && targetLang != null) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(targetLang)
                .build()
            translator = Translation.getClient(options)
        }
    }

    private fun showFloatingIcon() {
        if (floatingView != null) return

        val shape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.RED)
            setStroke(5, Color.WHITE)
        }

        floatingView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            setColorFilter(Color.WHITE)
            background = shape
            setPadding(40, 40, 40, 40)
            elevation = 10f
        }

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)

        floatingParams = WindowManager.LayoutParams(
            180,
            180,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = metrics.heightPixels / 3
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isMoved = false

        floatingView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = floatingParams!!.x
                    initialY = floatingParams!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - initialTouchX).toInt()
                    val deltaY = (event.rawY - initialTouchY).toInt()
                    if (abs(deltaX) > 10 || abs(deltaY) > 10) {
                        isMoved = true
                        floatingParams!!.x = initialX + deltaX
                        floatingParams!!.y = initialY + deltaY
                        windowManager.updateViewLayout(floatingView, floatingParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isMoved) {
                        clearOverlays() // আগের অনুবাদ মুছে স্ক্রিন রিফ্রেশ করবে
                        startScreenCapture()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(floatingView, floatingParams)
    }

    private fun startLoadingAnimation() {
        mainHandler.post {
            val rotate = RotateAnimation(
                0f, 360f,
                Animation.RELATIVE_TO_SELF, 0.5f,
                Animation.RELATIVE_TO_SELF, 0.5f
            ).apply {
                duration = 1000
                repeatCount = Animation.INFINITE
                interpolator = LinearInterpolator()
            }
            floatingView?.startAnimation(rotate)
        }
    }

    private fun stopLoadingAnimation() {
        mainHandler.post {
            floatingView?.clearAnimation()
        }
    }

    private fun startScreenCapture() {
        if (isCapturing) return
        if (mediaProjection == null) {
            Toast.makeText(this, "স্ক্রিন রেকর্ড পারমিশন নেই!", Toast.LENGTH_SHORT).show()
            return
        }
        
        isCapturing = true
        startLoadingAnimation()
        
        // ২০০ মিলি-সেকেন্ড পর স্ক্যান শুরু করবে, যাতে আগের লেখা মুছে নতুন স্ক্রিনটা আসে
        mainHandler.postDelayed({
            try {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                val width = metrics.widthPixels
                val height = metrics.heightPixels
                val density = metrics.densityDpi

                imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "ScreenCapture",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface, null, null
                )

                var frameCaptured = false

                imageReader?.setOnImageAvailableListener({ reader ->
                    if (frameCaptured) return@setOnImageAvailableListener
                    
                    try {
                        val image = reader.acquireLatestImage()
                        if (image != null) {
                            frameCaptured = true
                            val bitmap = imageToBitmap(image)
                            image.close()
                            
                            stopCapture() // ফ্রেম পাওয়া গেলেই ক্যাপচার বন্ধ করবে
                            
                            if (bitmap != null) {
                                processBitmap(bitmap)
                            } else {
                                resetCapture()
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        resetCapture()
                        stopCapture()
                    }
                }, mainHandler)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "স্ক্রিনশট এরর: ${e.message}", Toast.LENGTH_LONG).show()
                resetCapture()
            }
        }, 200)
    }

    private fun resetCapture() {
        isCapturing = false
        stopLoadingAnimation()
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bmpWidth = image.width + rowPadding / pixelStride
            val bmpHeight = image.height

            val bitmap = Bitmap.createBitmap(bmpWidth, bmpHeight, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)

            val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
            if (bitmap != croppedBitmap) {
                bitmap.recycle()
            }
            croppedBitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun processBitmap(bitmap: Bitmap) {
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            textRecognizer.process(inputImage)
                .addOnSuccessListener { text ->
                    val textBlocks = text.textBlocks
                    if (textBlocks.isEmpty()) {
                        Toast.makeText(this, "স্ক্রিনে কোনো লেখা পাওয়া যায়নি!", Toast.LENGTH_SHORT).show()
                        bitmap.recycle()
                        resetCapture()
                        return@addOnSuccessListener
                    }

                    backgroundScope.launch {
                        val translatedBlocks = mutableListOf<Pair<Rect, String>>()
                        for (block in textBlocks) {
                            val boundingBox = block.boundingBox ?: continue
                            val originalText = block.text
                            val translated = translateText(originalText)
                            if (translated != null) {
                                translatedBlocks.add(boundingBox to translated)
                            }
                        }

                        withContext(Dispatchers.Main) {
                            showOverlays(translatedBlocks)
                            bitmap.recycle()
                            resetCapture()
                        }
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "স্ক্যান ফেইল: ${e.message}", Toast.LENGTH_SHORT).show()
                    bitmap.recycle()
                    resetCapture()
                }
        } catch (e: Exception) {
            e.printStackTrace()
            bitmap.recycle()
            resetCapture()
        }
    }

    private suspend fun translateText(text: String): String? {
        if (translator == null) return null
        return suspendCancellableCoroutine { cont ->
            translator?.translate(text)
                ?.addOnSuccessListener { translated -> cont.resume(translated) {} }
                ?.addOnFailureListener { cont.resume(null) {} }
        }
    }

    private fun showOverlays(blocks: List<Pair<Rect, String>>) {
        clearOverlays()

        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, _ ->
                clearOverlays()
                true
            }
        }
        val containerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        windowManager.addView(container, containerParams)
        fullscreenOverlayContainer = container

        for ((rect, translatedText) in blocks) {
            val textView = TextView(this).apply {
                text = translatedText
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#E6000000"))
                gravity = Gravity.CENTER
                setPadding(4, 4, 4, 4)
                
                // অরিজিনাল সাইজ মেলানোর জন্য অটো-সাইজিং এবং ক্যালকুলেশন
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setAutoSizeTextTypeUniformWithConfiguration(
                        10, 100, 1, TypedValue.COMPLEX_UNIT_SP
                    )
                } else {
                    val lineCount = translatedText.split("\n").size.coerceAtLeast(1)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, (rect.height() / lineCount) * 0.7f)
                }
                
                layoutParams = FrameLayout.LayoutParams(rect.width(), rect.height()).apply {
                    setMargins(rect.left, rect.top, 0, 0)
                }
            }
            container.addView(textView)
            currentOverlays.add(textView)
        }
    }

    private fun clearOverlays() {
        fullscreenOverlayContainer?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) { }
        }
        fullscreenOverlayContainer = null
        currentOverlays.clear()
    }

    private fun stopCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Translator Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, ScreenTranslatorService::class.java).apply {
            action = "STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Translator")
            .setContentText("Tap floating icon to translate screen")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    fun stopServiceAndCleanup() {
        clearOverlays()
        stopCapture()
        try {
            floatingView?.let {
                windowManager.removeView(it)
            }
        } catch (e: Exception) { }
        floatingView = null
        
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
        
        translator?.close()
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundScope.cancel()
        stopServiceAndCleanup()
    }
}
