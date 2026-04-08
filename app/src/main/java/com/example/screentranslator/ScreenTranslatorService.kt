package com.example.screentranslator

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
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

    inner class LocalBinder : Binder() {
        fun getService(): ScreenTranslatorService = this@ScreenTranslatorService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            sourceLangCode = it.getStringExtra("SOURCE_LANG") ?: "en"
            targetLangCode = it.getStringExtra("TARGET_LANG") ?: "bn"
            setupTranslator()
        }
        return START_STICKY
    }

    fun setMediaProjection(projection: MediaProjection) {
        this.mediaProjection = projection
        showFloatingIcon()
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
            setColor(Color.RED) // বড় লাল রঙের আইকন
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
        windowManager.defaultDisplay.getMetrics(metrics)

        floatingParams = WindowManager.LayoutParams(
            180, // বড় সাইজ
            180, // বড় সাইজ
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
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
                        // এক ক্লিকে অনুবাদ শুরু
                        startScreenCapture()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(floatingView, floatingParams)
    }

    private fun startScreenCapture() {
        if (isCapturing) return
        if (mediaProjection == null) {
            Toast.makeText(this, "MediaProjection not ready", Toast.LENGTH_SHORT).show()
            return
        }
        isCapturing = true
        Toast.makeText(this, "অনুবাদ হচ্ছে...", Toast.LENGTH_SHORT).show()
        captureScreenAndProcess()
    }

    private fun captureScreenAndProcess() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
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

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage()
            if (image != null) {
                val bitmap = imageToBitmap(image)
                image.close()
                processBitmap(bitmap)
            }
            stopCapture()
        }, mainHandler)
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }

    private fun processBitmap(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(inputImage)
            .addOnSuccessListener { text ->
                val textBlocks = text.textBlocks
                if (textBlocks.isEmpty()) {
                    Toast.makeText(this, "স্ক্রিনে কোনো লেখা পাওয়া যায়নি!", Toast.LENGTH_SHORT).show()
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
                    }
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "স্ক্যান করতে সমস্যা হয়েছে!", Toast.LENGTH_SHORT).show()
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
                WindowManager.LayoutParams.TYPE_PHONE,
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
                setBackgroundColor(Color.parseColor("#E6000000")) // গাড় কালো ব্যাকগ্রাউন্ড
                gravity = Gravity.CENTER
                setPadding(12, 12, 12, 12)
                val width = rect.width()
                val height = rect.height()
                if (width <= 0 || height <= 0) return@apply
                layoutParams = FrameLayout.LayoutParams(width, height).apply {
                    setMargins(rect.left, rect.top, 0, 0)
                }
                fitTextToBounds(this, translatedText, width, height)
            }
            container.addView(textView)
            currentOverlays.add(textView)
        }
    }

    private fun fitTextToBounds(textView: TextView, text: String, maxWidth: Int, maxHeight: Int) {
        textView.text = text
        textView.measure(
            View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST)
        )
        var textSize = 50f
        val minSize = 10f
        var bestSize = minSize

        while (textSize >= minSize) {
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
            textView.measure(
                View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.EXACTLY)
            )
            if (textView.measuredHeight <= maxHeight && textView.measuredWidth <= maxWidth) {
                bestSize = textSize
                break
            }
            textSize -= 2f
        }
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, bestSize)
        textView.requestLayout()
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
        imageReader?.close()
        virtualDisplay = null
        imageReader = null
        isCapturing = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Translator Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows floating translation icon"
            }
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
