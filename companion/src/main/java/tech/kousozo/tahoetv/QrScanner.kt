package tech.kousozo.tahoetv

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import tech.kousozo.tahoetv.core.I18n
import tech.kousozo.tahoetv.core.TvLink

/**
 * In-app QR Code scanner (#36) using Camera2 + ImageReader YUV + ZXing MultiFormatReader.
 * Requires no external scanner app or Google Play Services, with manual link paste fallback.
 */
@Composable
fun QrCodeScannerDialog(
    onScanned: (TvLink.QrPayload) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalContext.current
    val lang = PhoneRegion.language
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(c, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val reqPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hasCamera = ok
    }
    LaunchedEffect(Unit) {
        if (!hasCamera) reqPerm.launch(Manifest.permission.CAMERA)
    }
    var manual by remember { mutableStateOf("") }
    var invalidHint by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Tahoe.Surface)
                .border(1.dp, Tahoe.Stroke, RoundedCornerShape(24.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.QrCodeScanner, null, tint = Tahoe.Accent)
                Spacer(Modifier.width(10.dp))
                Text(I18n.get(lang, "qr.scan_title"), fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onDismiss) { Icon(Icons.Rounded.Close, I18n.get(lang, "common.cancel"), tint = Tahoe.Dim) }
            }
            Text(I18n.get(lang, "qr.scan_hint"), fontSize = 13.sp, color = Tahoe.Dim)

            if (hasCamera) {
                Box(
                    Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(18.dp))
                        .background(Color.Black).border(2.dp, Tahoe.Accent.copy(alpha = 0.65f), RoundedCornerShape(18.dp))
                ) {
                    Camera2QrPreview(onDecoded = { payload -> onScanned(payload) })
                }
            } else {
                GlassCard {
                    Text(I18n.get(lang, "qr.need_camera"), fontSize = 13.sp, color = Tahoe.Dim)
                    TextButton({ reqPerm.launch(Manifest.permission.CAMERA) }) {
                        Text(I18n.get(lang, "tv.allow"), color = Tahoe.Accent)
                    }
                }
            }

            Field(
                value = manual,
                onChange = {
                    manual = it
                    invalidHint = false
                    TvLink.parseQrPayload(it)?.let { parsed -> onScanned(parsed) }
                },
                label = "http://<ip-tv>:8765/?code=123456",
            )
            if (invalidHint) Text(I18n.get(lang, "pair.invalid_code"), fontSize = 12.sp, color = Tahoe.Bad)
            PrimaryButton(I18n.get(lang, "tv.connect"), enabled = manual.isNotBlank()) {
                val parsed = TvLink.parseQrPayload(manual)
                if (parsed != null) onScanned(parsed) else invalidHint = true
            }
        }
    }
}

@Composable
private fun Camera2QrPreview(onDecoded: (TvLink.QrPayload) -> Unit) {
    val ctx = LocalContext.current
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var delivered by remember { mutableStateOf(false) }

    var holder by remember { mutableStateOf<CameraHolder?>(null) }
    DisposableEffect(Unit) {
        onDispose { holder?.close() }
    }

    AndroidView(
        factory = { c ->
            TextureView(c).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        holder?.close()
                        holder = CameraHolder(c, st, mainHandler) { payload ->
                            if (!delivered) {
                                delivered = true
                                onDecoded(payload)
                            }
                        }.also { it.start() }
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        holder?.close()
                        holder = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private class CameraHolder(
    private val context: Context,
    private val surfaceTexture: SurfaceTexture,
    private val main: Handler,
    private val onResult: (TvLink.QrPayload) -> Unit,
) {
    private val bgThread = HandlerThread("tahoe-qr-cam").apply { start() }
    private val bgHandler = Handler(bgThread.looper)
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    @Volatile private var closed = false
    @Volatile private var lastScanMs = 0L

    @SuppressLint("MissingPermission")
    fun start() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val camId = cm.cameraIdList.firstOrNull { id ->
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: cm.cameraIdList.firstOrNull() ?: return

        val ir = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
        imageReader = ir
        ir.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                if (closed) return@setOnImageAvailableListener
                val now = System.currentTimeMillis()
                if (now - lastScanMs < 200L) return@setOnImageAvailableListener
                lastScanMs = now
                val w = img.width
                val h = img.height
                val plane = img.planes[0]
                val buf = plane.buffer
                val rowStride = plane.rowStride
                val yData = ByteArray(w * h)
                if (rowStride == w) {
                    buf.get(yData, 0, minOf(yData.size, buf.remaining()))
                } else {
                    val rowBuf = ByteArray(rowStride)
                    for (y in 0 until h) {
                        val rem = buf.remaining()
                        if (rem <= 0) break
                        val toRead = minOf(rowStride, rem)
                        buf.get(rowBuf, 0, toRead)
                        System.arraycopy(rowBuf, 0, yData, y * w, minOf(w, toRead))
                    }
                }
                val src = PlanarYUVLuminanceSource(yData, w, h, 0, 0, w, h, false)
                val bmp = BinaryBitmap(HybridBinarizer(src))
                val res = runCatching { reader.decodeWithState(bmp) }.getOrNull()
                val text = res?.text ?: return@setOnImageAvailableListener
                val parsed = TvLink.parseQrPayload(text) ?: return@setOnImageAvailableListener
                closed = true
                main.post { onResult(parsed) }
            } finally {
                img.close()
                reader.reset()
            }
        }, bgHandler)

        runCatching {
            cm.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    if (closed) { cam.close(); return }
                    device = cam
                    surfaceTexture.setDefaultBufferSize(640, 480)
                    val previewSurface = Surface(surfaceTexture)
                    val irSurface = ir.surface
                    val targets = listOf(previewSurface, irSurface)
                    @Suppress("DEPRECATION")
                    cam.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (closed) { s.close(); return }
                            session = s
                            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(previewSurface)
                                addTarget(irSurface)
                            }.build()
                            runCatching { s.setRepeatingRequest(req, null, bgHandler) }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) {}
                    }, bgHandler)
                }
                override fun onDisconnected(cam: CameraDevice) { cam.close() }
                override fun onError(cam: CameraDevice, error: Int) { cam.close() }
            }, bgHandler)
        }
    }

    fun close() {
        closed = true
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { imageReader?.close() }
        runCatching { bgThread.quitSafely() }
    }
}
