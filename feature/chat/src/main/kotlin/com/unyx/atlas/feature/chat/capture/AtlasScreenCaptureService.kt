package com.unyx.atlas.feature.chat.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AtlasScreenCaptureService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val completed = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var resultReceiver: ResultReceiver? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.parcelableIntentExtra(EXTRA_RESULT_DATA)
        resultReceiver = intent?.parcelableResultReceiverExtra(EXTRA_RECEIVER)
        if (code == 0 || data == null || resultReceiver == null) {
            finish(RESULT_ERROR, Bundle().apply { putString(EXTRA_ERROR, "A autorização de captura não foi recebida.") })
            return START_NOT_STICKY
        }

        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(code, data)
            captureThread = HandlerThread("AtlasScreenCapture").also { it.start() }
            captureHandler = Handler(requireNotNull(captureThread).looper)
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    finish(RESULT_ERROR, Bundle().apply { putString(EXTRA_ERROR, "A captura foi interrompida pelo Android.") })
                }
            }
            projectionCallback = callback
            projection?.registerCallback(callback, captureHandler)
            mainHandler.postDelayed(
                {
                    // This runs after onStartCommand's own try/catch has already returned, so an
                    // exception here (e.g. getRealMetrics/createVirtualDisplay failing on a given
                    // device/OEM skin) would otherwise be uncaught on the main thread and crash the
                    // whole app — which, since the Assistant window is made transparent for the
                    // capture, looked to the user like the screen simply closing with nothing
                    // arriving, while the system's screen-recording indicator kept counting until
                    // the dead process was cleaned up.
                    if (!completed.get()) {
                        try {
                            beginSingleFrameCapture()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            Log.e(TAG, "Could not begin the screen capture frame.", failure)
                            finish(RESULT_ERROR, Bundle().apply { putString(EXTRA_ERROR, "Não foi possível iniciar a captura da tela.") })
                        }
                    }
                },
                CAPTURE_START_DELAY_MILLIS,
            )
            mainHandler.postDelayed(
                { finish(RESULT_ERROR, Bundle().apply { putString(EXTRA_ERROR, "O Android não retornou uma imagem da tela.") }) },
                CAPTURE_TIMEOUT_MILLIS,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.e(TAG, "Screen capture could not be started.", failure)
            finish(RESULT_ERROR, Bundle().apply { putString(EXTRA_ERROR, "Não foi possível capturar a tela neste aparelho.") })
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cleanupCapture()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun beginSingleFrameCapture() {
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY))
            .getRealMetrics(displayMetrics)
        val scale = minOf(
            1f,
            MAX_CAPTURE_EDGE.toFloat() / maxOf(displayMetrics.widthPixels, displayMetrics.heightPixels),
        )
        val width = (displayMetrics.widthPixels * scale).toInt().coerceAtLeast(1)
        val height = (displayMetrics.heightPixels * scale).toInt().coerceAtLeast(1)
        val densityDpi = (displayMetrics.densityDpi * scale).toInt().coerceAtLeast(1)
        require(width > 0 && height > 0) { "The display has invalid dimensions." }
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            val bitmap = try {
                image.toBitmap()
            } catch (failure: Exception) {
                Log.e(TAG, "Could not read the captured image buffer.", failure)
                finish(RESULT_ERROR, Bundle().apply { putString(EXTRA_ERROR, "A imagem capturada estava inválida.") })
                return@setOnImageAvailableListener
            } finally {
                image.close()
            }
            serviceScope.launch {
                try {
                    val result = withContext(Dispatchers.Default) {
                        val recognized = recognizeText(bitmap)
                        val jpeg = compressForUpload(bitmap)
                        jpeg to recognized
                    }
                    finish(
                        RESULT_OK,
                        Bundle().apply {
                            putByteArray(EXTRA_JPEG, result.first)
                            putString(EXTRA_OCR_TEXT, result.second)
                        },
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Log.e(TAG, "Could not prepare the captured screen locally.", failure)
                    finish(RESULT_ERROR, Bundle().apply { putString(EXTRA_ERROR, "Não foi possível ler ou preparar a captura.") })
                } finally {
                    bitmap.recycle()
                }
            }
        }, captureHandler)
        virtualDisplay = requireNotNull(projection).createVirtualDisplay(
            "AtlasScreenCapture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler,
        )
    }

    private suspend fun recognizeText(bitmap: Bitmap): String =
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    if (continuation.isActive) {
                        val text = result.text
                        continuation.resume(text.take(MAX_OCR_CHARACTERS) + if (text.length > MAX_OCR_CHARACTERS) "\n[Texto OCR truncado]" else "")
                    }
                }
                .addOnFailureListener { failure ->
                    if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
                }
                .addOnCompleteListener { recognizer.close() }
        }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes.firstOrNull() ?: error("Captured image has no pixel plane.")
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        require(pixelStride >= 4 && rowStride >= width * pixelStride) { "Captured image has invalid row layout." }
        val buffer: ByteBuffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val lastPixelEnd = (height - 1) * rowStride + (width - 1) * pixelStride + 4
        require(bytes.size >= lastPixelEnd) { "Captured image buffer ended before the last pixel." }
        val output = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * rowStride
            for (x in 0 until width) {
                val offset = rowOffset + x * pixelStride
                val red = bytes[offset].toInt() and 0xff
                val green = bytes[offset + 1].toInt() and 0xff
                val blue = bytes[offset + 2].toInt() and 0xff
                val alpha = bytes[offset + 3].toInt() and 0xff
                output[y * width + x] = android.graphics.Color.argb(alpha, red, green, blue)
            }
        }
        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun compressForUpload(source: Bitmap): ByteArray {
        var current = source
        var ownsCurrent = false
        try {
            while (true) {
                val stream = ByteArrayOutputStream()
                current.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                val bytes = stream.toByteArray()
                if (bytes.size <= MAX_JPEG_BYTES) return bytes
                require(current.width > MIN_IMAGE_EDGE && current.height > MIN_IMAGE_EDGE) {
                    "The screen capture is too large to send safely."
                }
                val resized = Bitmap.createScaledBitmap(
                    current,
                    (current.width * RESIZE_FACTOR).toInt().coerceAtLeast(MIN_IMAGE_EDGE),
                    (current.height * RESIZE_FACTOR).toInt().coerceAtLeast(MIN_IMAGE_EDGE),
                    true,
                )
                if (ownsCurrent) current.recycle()
                current = resized
                ownsCurrent = true
            }
        } finally {
            if (ownsCurrent) current.recycle()
        }
    }

    private fun finish(code: Int, data: Bundle) {
        if (!completed.compareAndSet(false, true)) return
        mainHandler.removeCallbacksAndMessages(null)
        cleanupCapture()
        resultReceiver?.send(code, data)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupCapture() {
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        virtualDisplay?.release()
        virtualDisplay = null
        projection?.let { activeProjection ->
            projectionCallback?.let(activeProjection::unregisterCallback)
            activeProjection.stop()
        }
        projection = null
        projectionCallback = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Análise de tela do Atlas")
            .setContentText("Captura única iniciada por você.")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Captura de tela", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun Intent.parcelableIntentExtra(key: String): Intent? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Intent::class.java) else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }

    private fun Intent.parcelableResultReceiverExtra(key: String): ResultReceiver? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, ResultReceiver::class.java) else {
            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }

    companion object {
        private const val TAG = "AtlasScreenCapture"
        private const val CHANNEL_ID = "atlas_screen_capture"
        private const val NOTIFICATION_ID = 7315
        private const val CAPTURE_START_DELAY_MILLIS = 350L
        private const val MAX_CAPTURE_EDGE = 2560
        private const val CAPTURE_TIMEOUT_MILLIS = 12_000L
        private const val MAX_JPEG_BYTES = 650 * 1024
        private const val MAX_OCR_CHARACTERS = 10_000
        private const val JPEG_QUALITY = 82
        private const val MIN_IMAGE_EDGE = 480
        private const val RESIZE_FACTOR = 0.82
        const val RESULT_OK = 1
        const val RESULT_ERROR = 2
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_RECEIVER = "result_receiver"
        const val EXTRA_JPEG = "jpeg"
        const val EXTRA_OCR_TEXT = "ocr_text"
        const val EXTRA_ERROR = "error"

        fun startCapture(context: Context, resultCode: Int, resultData: Intent, receiver: ResultReceiver): Boolean {
            val serviceIntent = Intent(context, AtlasScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
                .putExtra(EXTRA_RECEIVER, receiver)
            return try {
                androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
                true
            } catch (failure: Exception) {
                Log.e(TAG, "Could not start the screen capture foreground service.", failure)
                false
            }
        }
    }
}
