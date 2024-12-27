package ir.masoudsoft.aiyes

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.SeekBar
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import ir.masoudsoft.aiyes.R
import ir.masoudsoft.aiyes.Constants.LABELS_PATH
import ir.masoudsoft.aiyes.Constants.MODEL_PATH
import ir.masoudsoft.aiyes.databinding.ActivityMainBinding
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity(), Detector.DetectorListener,TextToSpeech.OnInitListener { //
    private lateinit var binding: ActivityMainBinding
    private val isFrontCamera = false


    private var tts: TextToSpeech? = null
    private var accessibilityManager: AccessibilityManager? = null
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var detector: Detector? = null

    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)


        // TextToSpeech(Context: this, OnInitListener: this)
        tts = TextToSpeech(this, this, tts?.defaultEngine)
        accessibilityManager = this.getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager?


        enableEdgeToEdge()
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        cameraExecutor.execute {

            detector = Detector(baseContext, MODEL_PATH, LABELS_PATH, this)

        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }
        setTitle(" ");
        bindListeners()
    }

    private fun bindListeners() {
        binding.apply {
            isGpu.setOnCheckedChangeListener { buttonView, isChecked ->
                cameraExecutor.submit {
                    detector?.restart(isGpu = isChecked)
                }
                if (isChecked) {
                    buttonView.setBackgroundColor(
                        ContextCompat.getColor(
                            baseContext,
                            R.color.orange
                        )
                    )
                } else {
                    buttonView.setBackgroundColor(ContextCompat.getColor(baseContext, R.color.gray))
                }
            }
            sbIo.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener{
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        // here, you react to the value being set in seekBar
                        cameraExecutor.submit{
                            detector?.set_io_thresholds(progress)
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) {
                        // you can probably leave this empty
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        // you can probably leave this empty
                    }
                }
            )
            sbConf.setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener{
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        // here, you react to the value being set in seekBar
                        cameraExecutor.submit{
                            detector?.set_conf_thresholds(progress)
                        }
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar) {
                        // you can probably leave this empty
                    }

                    override fun onStopTrackingTouch(seekBar: SeekBar) {
                        // you can probably leave this empty
                    }
                }
            )
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(baseContext)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider =
            cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val rotation = binding.viewFinder.display.rotation

        val cameraSelector = CameraSelector
            .Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetRotation(binding.viewFinder.display.rotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalyzer?.setAnalyzer(cameraExecutor) { imageProxy ->
            val bitmapBuffer =
                Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
            imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
            imageProxy.close()

            val matrix = Matrix().apply {
                postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

                if (isFrontCamera) {
                    postScale(
                        -1f,
                        1f,
                        imageProxy.width.toFloat(),
                        imageProxy.height.toFloat()
                    )
                }
            }

            val rotatedBitmap = Bitmap.createBitmap(
                bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height,
                matrix, true
            )

            detector?.detect(rotatedBitmap)
        }

        cameraProvider.unbindAll()

        try {
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                imageAnalyzer
            )

            preview?.surfaceProvider = binding.viewFinder.surfaceProvider
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (it[Manifest.permission.CAMERA] == true) {
            startCamera()
        }
    }

    override fun onDestroy() {
        detector?.close()
        cameraExecutor.shutdown()
        super.onDestroy()

    }

    override fun onResume() {
        super.onResume()
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA
        ).toTypedArray()
    }

    override fun onEmptyDetect() {
        runOnUiThread {
            binding.overlay.clear()
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
        if (!tts!!.isSpeaking) {

            runOnUiThread {
                binding.inferenceTime.text = "${inferenceTime}ms"
                binding.overlay.apply {

                    setResults(boundingBoxes)

                    // Create a mutable map to count occurrences of clsName
                    val myMap = mutableMapOf<String, Int>()

                    // Iterate over the bounding boxes
                    for (it in boundingBoxes) {
                        myMap[it.clsName] = myMap.getOrDefault(it.clsName, 0) + 1
                    }

                    // Sort the map by value in descending order and convert keys to a list
                    val sortedKeys = myMap.entries
                        .sortedByDescending { it.value }
                        .map {it.value.toString() + it.key }



                    speakOut(sortedKeys.joinToString(separator = " "))
                    println(sortedKeys.joinToString(separator = " "))

                    invalidate()

                }
            }
        }
    }
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts!!.setEngineByPackageName(tts!!.defaultEngine)
            val result = tts!!.setLanguage(Locale("fa"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS","The Language not supported!")
            } else {
                // btnSpeak!!.isEnabled = true
            }
        }
    }

    private fun speakOut(text:String) {
        val accessibilityEvent = AccessibilityEvent.obtain()
        accessibilityEvent.eventType = AccessibilityEvent.TYPE_ANNOUNCEMENT
        accessibilityEvent.text.add(text.toString())
        accessibilityManager!!.sendAccessibilityEvent(accessibilityEvent)

//        tts!!.speak(text, TextToSpeech.QUEUE_ADD, null,"")
    }

}
