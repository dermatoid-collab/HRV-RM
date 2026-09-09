package com.hrvrm.app.ppg

import android.content.Context
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/**
 * Drives the rear camera + torch to sample raw PPG intensity values.
 *
 * The finger is expected to cover both the lens and the flash. Each analyzed frame is
 * reduced to the mean **red channel** value (red carries the strongest, cleanest
 * pulsatile signal under flash transillumination — see e.g. Kumar et al. and the
 * iPhysioMeter validation work) and emitted as a [PpgSample] via [onSample]. Frame
 * processing runs on a dedicated single-thread executor so analysis never blocks the
 * camera pipeline or the UI thread.
 */
class PpgCameraSource(
    private val context: Context,
) {
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var startTimestampNs: Long? = null

    var onSample: ((PpgSample) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    fun start(lifecycleOwner: LifecycleOwner) {
        startTimestampNs = null
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    // RGBA gives direct access to the red channel; the default YUV
                    // luma output mixes in green/blue, which under flash occlusion
                    // carry mostly noise and dilute the pulsatile (AC) component.
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()

                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    processFrame(imageProxy)
                }

                val selector = CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                camera = provider.bindToLifecycle(lifecycleOwner, selector, analysis)
                camera?.cameraControl?.enableTorch(true)
            } catch (t: Throwable) {
                onError?.invoke(t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Locks auto-exposure and auto-white-balance at their current (converged) values.
     * Auto-exposure actively fights a PPG reading: it's a slow AGC loop that keeps
     * renormalizing brightness, which suppresses or distorts the very amplitude
     * variation we're trying to measure. Call this once the image has had a moment to
     * settle after the finger covers the lens (the app does this at the end of its
     * stabilization countdown, right before it starts collecting samples).
     */
    @OptIn(ExperimentalCamera2Interop::class)
    fun lockExposure() {
        val currentCamera = camera ?: return
        val camera2Control = Camera2CameraControl.from(currentCamera.cameraControl)
        val options = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
            .build()
        camera2Control.setCaptureRequestOptions(options)
    }

    fun stop() {
        camera?.cameraControl?.enableTorch(false)
        cameraProvider?.unbindAll()
        camera = null
        cameraProvider = null
    }

    fun shutdown() {
        stop()
        analysisExecutor.shutdown()
    }

    private fun processFrame(imageProxy: ImageProxy) {
        try {
            val plane = imageProxy.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val width = imageProxy.width
            val height = imageProxy.height

            // Sample a sparse grid instead of every pixel: plenty for a stable mean
            // while keeping per-frame CPU cost negligible even at 30fps.
            val stepX = maxOf(1, width / 40)
            val stepY = maxOf(1, height / 40)

            var sum = 0L
            var count = 0
            var y = 0
            while (y < height) {
                var x = 0
                val rowStart = y * rowStride
                while (x < width) {
                    // RGBA_8888: red is the first byte of each pixel.
                    val index = rowStart + x * pixelStride
                    if (index < buffer.capacity()) {
                        sum += buffer.get(index).toInt() and 0xFF
                        count++
                    }
                    x += stepX
                }
                y += stepY
            }

            if (count > 0) {
                val now = imageProxy.imageInfo.timestamp
                val start = startTimestampNs ?: now.also { startTimestampNs = it }
                val elapsedMs = (now - start) / 1_000_000
                val meanIntensity = sum.toDouble() / count
                onSample?.invoke(PpgSample(timestampMs = elapsedMs, intensity = meanIntensity))
            }
        } catch (t: Throwable) {
            onError?.invoke(t)
        } finally {
            imageProxy.close()
        }
    }

    /** True if the device exposes a torch on the back camera; checked before starting a measurement. */
    fun hasFlash(): Boolean {
        return context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_FLASH)
    }
}
