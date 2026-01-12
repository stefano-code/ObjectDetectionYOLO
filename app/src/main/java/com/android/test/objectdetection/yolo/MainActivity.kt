package com.android.test.objectdetection.yolo

import android.media.Image
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.Surface
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.android.test.objectdetection.yolo.drawing.BorderedText
import com.android.test.objectdetection.yolo.drawing.MultiBoxTracker
import com.android.test.objectdetection.yolo.drawing.OverlayView
import com.android.test.objectdetection.yolo.livefeed.CameraConnectionFragment
import com.android.test.objectdetection.yolo.livefeed.ImageUtils
import com.android.test.objectdetection.yolo.ml.Classifier
import com.android.test.objectdetection.yolo.ml.Recognition
import java.io.IOException

class MainActivity : AppCompatActivity(), ImageReader.OnImageAvailableListener {

    private var classifier: Classifier? = null
    private lateinit var resultTV: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var frameToCropTransform: Matrix? = null
    private var cropToFrameTransform: Matrix? = null
    private var sensorOrientation: Int = 0

    private var trackingOverlay: OverlayView? = null
    private var borderedText: BorderedText? = null
    private var tracker: MultiBoxTracker? = null

    private var previewWidth = 0
    private var previewHeight = 0
    private var rgbFrameBitmap: Bitmap? = null
    private var croppedBitmap: Bitmap? = null

    private var isProcessingFrame = false
    private val yuvBytes = arrayOfNulls<ByteArray>(3)
    private var rgbBytes: IntArray? = null
    private var yRowStride = 0
    private var postInferenceCallback: Runnable? = null
    private var imageConverter: Runnable? = null

    companion object {
        private const val TF_OD_API_INPUT_SIZE = 416
        private const val MINIMUM_CONFIDENCE_TF_OD_API = 0.5f
        private const val MAINTAIN_ASPECT = false
        private const val TEXT_SIZE_DIP = 10f
        private const val PERMISSION_REQUEST_CODE = 121
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        resultTV = findViewById(R.id.textView)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), PERMISSION_REQUEST_CODE)
            } else {
                setFragment()
            }
        } else {
            setFragment()
        }

        tracker = MultiBoxTracker(this)

        try {
            classifier = Classifier(assets, "yolov4-416.tflite", "labelmap.txt")
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    protected fun setFragment() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = try {
            manager.cameraIdList[0]
        } catch (e: CameraAccessException) {
            e.printStackTrace()
            null
        }

        val camera2Fragment = CameraConnectionFragment.newInstance(
            { size, rotation ->
                previewHeight = size.height
                previewWidth = size.width

                val textSizePx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, resources.displayMetrics
                )
                borderedText = BorderedText(textSizePx).apply {
                    setTypeface(Typeface.MONOSPACE)
                }

                tracker = MultiBoxTracker(this@MainActivity)

                val cropSize = TF_OD_API_INPUT_SIZE
                sensorOrientation = rotation - getScreenOrientation()

                rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
                croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Bitmap.Config.ARGB_8888)

                frameToCropTransform = ImageUtils.getTransformationMatrix(
                    previewWidth, previewHeight,
                    cropSize, cropSize,
                    sensorOrientation, MAINTAIN_ASPECT
                )

                cropToFrameTransform = Matrix()
                frameToCropTransform?.invert(cropToFrameTransform)

                trackingOverlay = findViewById(R.id.tracking_overlay)
                trackingOverlay = findViewById(R.id.tracking_overlay)
                trackingOverlay?.addCallback { canvas ->
                    tracker?.draw(canvas)
                    Log.d("tryDrawRect", "inside draw")
                }

                tracker?.setFrameConfiguration(previewWidth, previewHeight, sensorOrientation)
            },
            this,
            R.layout.camera_fragment,
            Size(640, 480)
        )

        cameraId?.let { camera2Fragment.setCamera(it) }

        supportFragmentManager.beginTransaction()
            .replace(R.id.container, camera2Fragment)
            .commit()
    }

    override fun onImageAvailable(reader: ImageReader) {
        if (previewWidth == 0 || previewHeight == 0) return

        if (rgbBytes == null) {
            rgbBytes = IntArray(previewWidth * previewHeight)
        }

        try {
            val image = reader.acquireLatestImage() ?: return

            if (isProcessingFrame) {
                image.close()
                return
            }

            isProcessingFrame = true
            val planes = image.planes
            fillBytes(planes, yuvBytes)

            yRowStride = planes[0].rowStride
            val uvRowStride = planes[1].rowStride
            val uvPixelStride = planes[1].pixelStride

            imageConverter = Runnable {
                ImageUtils.convertYUV420ToARGB8888(
                    yuvBytes[0]!!, yuvBytes[1]!!, yuvBytes[2]!!,
                    previewWidth, previewHeight,
                    yRowStride, uvRowStride, uvPixelStride,
                    rgbBytes!!
                )
            }

            postInferenceCallback = Runnable {
                image.close()
                isProcessingFrame = false
            }

            processImage()

        } catch (e: Exception) {
            Log.d("tryError", "${e.message} abc ")
        }
    }

    private fun processImage() {
        imageConverter?.run()
        rgbFrameBitmap?.setPixels(rgbBytes!!, 0, previewWidth, 0, 0, previewWidth, previewHeight)

        val canvas = croppedBitmap?.let { Canvas(it) }
        canvas?.drawBitmap(rgbFrameBitmap!!, frameToCropTransform!!, null)

        handler.post {
            val results = classifier?.recognizeImage(croppedBitmap!!)
            if (results != null) {
                Log.d("tryRes", "${results.size}")
            }

            val mappedRecognitions = mutableListOf<Recognition>()

            results?.forEach { result ->
                val location = result.getLocation()
                if (location != null && result.confidence >= MINIMUM_CONFIDENCE_TF_OD_API) {
                    cropToFrameTransform?.mapRect(location)
                    result.setLocation(location)
                    mappedRecognitions.add(result)
                }
            }

            tracker?.trackResults(mappedRecognitions, 10)
            trackingOverlay?.postInvalidate()
            postInferenceCallback?.run()
        }
    }

    private fun fillBytes(planes: Array<Image.Plane>, yuvBytes: Array<ByteArray?>) {
        for (i in planes.indices) {
            val buffer = planes[i].buffer
            if (yuvBytes[i] == null) {
                yuvBytes[i] = ByteArray(buffer.capacity())
            }
            buffer.get(yuvBytes[i]!!)
        }
    }

    private fun getScreenOrientation(): Int {
        return when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_270 -> 270
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_90 -> 90
            else -> 0
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setFragment()
        }
    }
}