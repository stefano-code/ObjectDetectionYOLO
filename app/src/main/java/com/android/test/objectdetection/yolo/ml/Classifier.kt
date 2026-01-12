package com.android.test.objectdetection.yolo.ml

import android.content.res.AssetFileDescriptor
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Build
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.PriorityQueue

class Classifier(
    assetManager: AssetManager,
    modelFilename: String,
    labelFilename: String
) {

    companion object {
        private const val INPUT_SIZE = 416
        private const val NUM_THREADS = 4
        private const val CONFIDENCE_THRESHOLD = 0.5f
        private const val BATCH_SIZE = 1
        private const val PIXEL_SIZE = 3

        private val OUTPUT_WIDTH_TINY = intArrayOf(2535, 2535)

        private var isNNAPI = false

        @Throws(Exception::class)
        fun loadModelFile(assets: AssetManager, modelFilename: String): MappedByteBuffer {
            val fileDescriptor: AssetFileDescriptor = assets.openFd(modelFilename)
            FileInputStream(fileDescriptor.fileDescriptor).channel.use { channel ->
                return channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    fileDescriptor.startOffset,
                    fileDescriptor.declaredLength
                )
            }
        }
    }

    private val labels: List<String>
    private val interpreter: Interpreter
    private val nmsThreshold = 0.6f

    init {
        val options = Interpreter.Options().apply {
            setNumThreads(NUM_THREADS)

            if (isNNAPI && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val nnApiDelegate = NnApiDelegate()
                addDelegate(nnApiDelegate)
                setAllowFp16PrecisionForFp32(true)
                setAllowBufferHandleOutput(true)
            }
        }

        interpreter = Interpreter(loadModelFile(assetManager, modelFilename), options)
        labels = loadLabelList(assetManager, labelFilename)
    }

    fun recognizeImage(bitmap: Bitmap): List<Recognition> {
        val inputBuffer = convertBitmapToByteBuffer(bitmap)
        return nms(getDetections(inputBuffer, bitmap, OUTPUT_WIDTH_TINY))

    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(
            4 * BATCH_SIZE * INPUT_SIZE * INPUT_SIZE * PIXEL_SIZE
        ).order(ByteOrder.nativeOrder())

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var idx = 0
        repeat(INPUT_SIZE) {
            repeat(INPUT_SIZE) {
                val v = pixels[idx++]
                buffer.putFloat(((v shr 16) and 0xFF) / 255f)
                buffer.putFloat(((v shr 8) and 0xFF) / 255f)
                buffer.putFloat((v and 0xFF) / 255f)
            }
        }
        return buffer
    }

    private fun getDetections(
        input: ByteBuffer,
        bitmap: Bitmap,
        outputWidth: IntArray
    ): ArrayList<Recognition> {
        val detections = ArrayList<Recognition>()

        val outputMap = HashMap<Int, Any>().apply {
            put(0, Array(1) { Array(outputWidth[0]) { FloatArray(4) } })
            put(1, Array(1) { Array(outputWidth[1]) { FloatArray(labels.size) } })
        }

        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputMap)

        val boxes = outputMap[0] as Array<Array<FloatArray>>
        val scores = outputMap[1] as Array<Array<FloatArray>>

        for (i in boxes[0].indices) {
            val classScores = scores[0][i]
            val (bestClass, bestScore) = classScores
                .mapIndexed { idx, score -> idx to score }
                .maxByOrNull { it.second } ?: continue

            if (bestScore > CONFIDENCE_THRESHOLD) {
                val (x, y, w, h) = boxes[0][i]
                val rect = RectF(
                    (x - w / 2).coerceAtLeast(0f),
                    (y - h / 2).coerceAtLeast(0f),
                    (x + w / 2).coerceAtMost(bitmap.width - 1f),
                    (y + h / 2).coerceAtMost(bitmap.height - 1f)
                )
                detections.add(
                    Recognition(i.toString(), labels[bestClass], bestScore, rect, bestClass)
                )
            }
        }
        return detections
    }

    private fun loadLabelList(assetManager: AssetManager, path: String): List<String> {
        return BufferedReader(InputStreamReader(assetManager.open(path))).useLines { it.toList() }
    }

    private fun nms(list: List<Recognition>): ArrayList<Recognition> {
        val result = ArrayList<Recognition>()

        for (k in labels.indices) {
            val pq = PriorityQueue<Recognition> { a, b ->
                b.confidence.compareTo(a.confidence)
            }

            list.filterTo(pq) { it.detectedClass == k }

            while (pq.isNotEmpty()) {
                val max = pq.poll()
                result.add(max)
                val survivors = pq.filter {
                    boxIou(max.getLocation(), it.getLocation()) < nmsThreshold
                }
                pq.clear()
                pq.addAll(survivors)
            }
        }
        return result
    }

    private fun boxIou(a: RectF, b: RectF): Float =
        boxIntersection(a, b) / boxUnion(a, b)

    private fun boxIntersection(a: RectF, b: RectF): Float {
        val w = overlap((a.left + a.right) / 2, a.width(), (b.left + b.right) / 2, b.width())
        val h = overlap((a.top + a.bottom) / 2, a.height(), (b.top + b.bottom) / 2, b.height())
        return if (w < 0 || h < 0) 0f else w * h
    }

    private fun boxUnion(a: RectF, b: RectF): Float {
        val i = boxIntersection(a, b)
        return a.width() * a.height() + b.width() * b.height() - i
    }

    private fun overlap(x1: Float, w1: Float, x2: Float, w2: Float): Float {
        val l = maxOf(x1 - w1 / 2, x2 - w2 / 2)
        val r = minOf(x1 + w1 / 2, x2 + w2 / 2)
        return r - l
    }
}
