package lobos.bridge

import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.ContextWrapper
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
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import lobos.MainActivity
import lobos.OsApplication
import lobos.R
import lobos.RuntimeDiagnostics
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ScreenCaptureController(private val host: Service) : ContextWrapper(host) {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    fun start() {
        instance = this
    }

    fun onHostStart(intent: Intent?) {
        if (intent?.action == ACTION_START && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            @Suppress("DEPRECATION")
            val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            if (code == Activity.RESULT_OK && data != null) {
                ScreenCaptureService.start(this, code, data)
            } else {
                RuntimeDiagnostics.append(this, "screenshot", false, "截屏授权被取消", "resultCode=$code")
            }
        }
    }

    private fun startProjection(resultCode: Int, data: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(resultCode, data) ?: run {
                RuntimeDiagnostics.append(this, "screenshot", false, "getMediaProjection 返回 null", "授权数据可能已失效")
                return
            }
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection 被系统停止")
                    teardown()
                }
            }, Handler(android.os.Looper.getMainLooper()))

            projection = p
            instance = this
            RuntimeDiagnostics.append(this, "screenshot", true, "MediaProjection 已就绪", "截屏授权生效")
        } catch (e: Throwable) {
            RuntimeDiagnostics.append(this, "screenshot", false, "启动 MediaProjection 失败", errText(e))
        }
    }

    fun capture(width: Int, height: Int, densityDpi: Int): Bitmap? {
        val p = projection ?: return null
        val latch = CountDownLatch(1)
        val holder = AtomicReference<Bitmap?>(null)

        try {
            setupDisplay(p, width, height, densityDpi)
            val reader = imageReader ?: return null
            reader.setOnImageAvailableListener({ r ->
                var image: Image? = null
                try {
                    image = r.acquireLatestImage()
                    if (image != null) {
                        holder.set(imageToBitmap(image))
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "取帧失败", e)
                } finally {
                    try { image?.close() } catch (_: Throwable) {}
                    latch.countDown()
                }
            }, handler)

            if (!latch.await(8, TimeUnit.SECONDS)) {
                Log.w(TAG, "截屏超时（8s 内未收到帧）")
            }
        } catch (e: Throwable) {
            RuntimeDiagnostics.append(this, "screenshot", false, "截屏失败", errText(e))
        }
        return holder.get()
    }

    private fun setupDisplay(p: MediaProjection, width: Int, height: Int, densityDpi: Int) {
        if (imageReader != null) return
        if (handler == null) {
            val t = HandlerThread("lobos-screen-capture")
            t.start()
            handlerThread = t
            handler = Handler(t.looper)
        }
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        virtualDisplay = p.createVirtualDisplay(
            "lobos-capture",
            width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bmp = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
    }

    private fun teardown() {
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        virtualDisplay = null
        imageReader = null
        projection = null
        if (instance === this) instance = null
        ScreenCaptureService.stop(this)
    }

    fun shutdown() {
        teardown()
        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }

    private fun errText(e: Throwable): String =
        "${e::class.java.simpleName}: ${e.message}"

    companion object {
        const val TAG = "ScreenCaptureController"
        const val ACTION_START = "lobos.SCREEN_CAPTURE_START"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        const val GRANT_FILE = "screen-capture-grant.json"

        @Volatile
        var instance: ScreenCaptureController? = null
            private set

        fun isReady(): Boolean = instance?.projection != null

        fun startProjectionFromService(ctx: Context, resultCode: Int, data: Intent) {
            instance?.startProjection(resultCode, data)
        }

        fun saveGrant(ctx: Context, resultCode: Int, data: Intent) {
            try {
                val parcel = android.os.Parcel.obtain()
                data.writeToParcel(parcel, 0)
                val bytes = parcel.marshall()
                parcel.recycle()
                val obj = org.json.JSONObject().apply {
                    put("resultCode", resultCode)
                    put("intentBase64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
                    put("savedAt", System.currentTimeMillis())
                }
                lobos.os.StateFiles.writeAtomic(File(ctx.filesDir, GRANT_FILE), obj.toString())
            } catch (e: Throwable) {
                Log.w(TAG, "保存截屏授权失败", e)
            }
        }

        fun loadGrant(ctx: Context): Pair<Int, Intent>? {
            return try {
                val f = File(ctx.filesDir, GRANT_FILE)
                if (!f.exists()) return null
                val obj = org.json.JSONObject(f.readText())
                val bytes = android.util.Base64.decode(obj.getString("intentBase64"), android.util.Base64.DEFAULT)
                val parcel = android.os.Parcel.obtain()
                parcel.unmarshall(bytes, 0, bytes.size)
                parcel.setDataPosition(0)
                @Suppress("DEPRECATION")
                val intent = Intent.CREATOR.createFromParcel(parcel)
                parcel.recycle()
                obj.getInt("resultCode") to intent
            } catch (e: Throwable) {
                Log.w(TAG, "读取截屏授权失败", e)
                null
            }
        }
    }
}
