package demo.idev.imagestory

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieDrawable
import demo.idev.imagestory.databinding.ActivityMainBinding
import demo.idev.imagestory.renderer.FrameCreator
import demo.idev.imagestory.renderer.RecordingOperation
import demo.idev.imagestory.renderer.v1.MediaCodecRecorder
import demo.idev.imagestory.renderer.v2.FFmpegRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.Executors
import kotlin.io.path.Path
import kotlin.math.ceil

class MainActivity : AppCompatActivity() {

    companion object {
        private val TAG = MainActivity::class.java.simpleName
        private val templateUrl =
            "https://download1522.mediafire.com/qxouy2ho2emgXHz_o-LONxnw0TqqfAXfqgqeW6KM5V0_9iJsxzYN0hR8PQiK_KCu9It8_urEag38E_eXmY5AFXnfoA/uhvos5o56evfun7/template_01.zip"
        private val REQUEST_PERMISSION_CODE = 5432
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var downloadManager: DownloadManager
    private lateinit var templateFileZip: File
    private lateinit var templateDir: File
    private lateinit var animationFile: File
    private lateinit var imageAssetFile: File

    private var VIDEO_WIDHT: Int? = null
    private var VIDEO_HEIGHT: Int? = null
    private var FPS: Int? = null

    private val exportVideoExecutor = Executors.newSingleThreadExecutor()

    private var templateDataJsonObject: JSONObject? = null

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // bind to view
        binding = ActivityMainBinding.inflate(layoutInflater)

        // set view to activity
        setContentView(binding.root)

        // int template data
        templateFileZip = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "template_01.zip"
        )
        templateDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            "template_01"
        )
        animationFile = File("$templateDir/data.json")
        imageAssetFile = File("$templateDir/images")

        // set up view
        // repeat when end of animation
        binding.preview.repeatCount = LottieDrawable.INFINITE
        binding.btnLoad.setOnClickListener {

            if (!templateDir.exists()) {
                Toast.makeText(applicationContext, "Template not found", Toast.LENGTH_LONG).show()
            }

            if (animationFile.exists() && animationFile.isFile) {
                Log.d(TAG, "Load animation to view ${animationFile.absolutePath}")
                binding.preview.setAnimation(FileInputStream(animationFile), null)
                // This delegate is middle when animation load image in asset
                // Image asset information (id, height, width,...) is read from data.json
                binding.preview.setImageAssetDelegate { asset ->
                    Log.d(TAG, "Load asset ${asset.id}")
                    // load bitmap by asset id
                    BitmapFactory.decodeFile("${imageAssetFile.absolutePath}/${asset.fileName}")
                }
                // play animation
                binding.preview.playAnimation()
            } else {
                Toast.makeText(applicationContext, "Animation file not found!", Toast.LENGTH_LONG)
                    .show()
            }
        }

        // export
        binding.btnExport.setOnClickListener {

            if (VIDEO_HEIGHT == null || VIDEO_WIDHT == null || FPS == null) {
                throw IllegalArgumentException("You must read animation data from data.json before convert it!")
            }

            val exportTask = ExportVideoWorker(
                onStart = {
                    runOnUiThread {
                        Toast.makeText(applicationContext, "Exporting...", Toast.LENGTH_LONG)
                            .show()
                    }
                },
                onCompleted = {
                    runOnUiThread {
                        Toast.makeText(
                            applicationContext,
                            "Save to ${it.absolutePath}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                binding.preview.composition!!,
                VIDEO_WIDHT!!,
                VIDEO_HEIGHT!!,
                FPS!!
            )

            lifecycleScope.launch(Dispatchers.Default) {
                exportTask.run()
            }
        }

        // change other image
        binding.btnChange.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val b =
                    BitmapFactory.decodeStream(URL("https://picsum.photos/200/300").openStream())
                // get image info in lottie assets
                // and scale new bitmap to fit it
                val height = binding.preview.composition?.images?.get("image_0")?.height
                val width = binding.preview.composition?.images?.get("image_0")?.width
                var scaledBitmap = Bitmap.createScaledBitmap(b, width ?: 0, height ?: 0, false)
                // load it in animation
                withContext(Dispatchers.Main) {
                    binding.preview.updateBitmap("image_0", scaledBitmap)
                }
            }
        }

        // check permission, request if it is denied
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    applicationContext,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED -> {
                    validateTemplateData()
                }
                else -> {
                    requestPermissions(
                        arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        REQUEST_PERMISSION_CODE
                    )
                }
            }
        } else {
            validateTemplateData()
        }
    }

    /**
     * Listener when user change permission
     * */
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSION_CODE) {
            // if it is granted, download data
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                validateTemplateData()
            }
        }
    }

    /**
     * Check template data is exist
     * If data is not exist, download it from server
     * */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun validateTemplateData() {
        // download data
        downloadManager = getSystemService(DownloadManager::class.java)

        if (!templateDir.exists()) {
            Log.d(TAG, "Template is not exist! Download it")
            downloadTemplateFile(downloadManager)
        } else {
            // read with and height
            Log.d(TAG, "Read animation data")
            templateDataJsonObject =
                JSONObject(String(Files.readAllBytes(Path(animationFile.absolutePath))))
            templateDataJsonObject?.run {
                VIDEO_WIDHT = this.getInt("w")
                VIDEO_HEIGHT = this.getInt("h")
                FPS = ceil(this.getDouble("fr")).toInt()
            }
        }
    }

    /**
     * Download animation template file from server
     * */
    private fun downloadTemplateFile(downloadManager: DownloadManager) {

        runOnUiThread {
            Toast.makeText(applicationContext, "Dowloading....", Toast.LENGTH_LONG).show()
        }

        var request = DownloadManager.Request(templateUrl.toUri())
        request.apply {
            title = "Download template file"
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setDestinationUri(templateFileZip.toUri())
        }
        var downloadId = downloadManager.enqueue(request)
        // listener when download complete
        registerReceiver(
            DownloadTemplateCompleteReceiver(downloadId),
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        )
    }

    /**
     * Unzip template file
     * */
    private fun unzipTemplate() {
        if (!templateFileZip.exists()) {
            throw IllegalArgumentException("Template not found! Download it")
        }

        val destination = templateDir.parentFile
            ?: File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath)
        Log.d(TAG, "Extract template to ${destination.absolutePath}")
        ZipUtils.unzip(templateFileZip.absolutePath, destination.absolutePath)
    }

    /**
     * Listener download template from download manager
     * */
    inner class DownloadTemplateCompleteReceiver(val downloadId: Long) : BroadcastReceiver() {

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)

            // on download template success
            // unzip file
            if (downloadId == id) {
                Log.d(
                    DownloadTemplateCompleteReceiver::class.java.simpleName,
                    "Download template success!"
                )
                unzipTemplate()
                // read with and height
                Log.d(TAG, "Read animation data")
                templateDataJsonObject =
                    JSONObject(String(Files.readAllBytes(Path(animationFile.absolutePath))))
                templateDataJsonObject?.run {
                    VIDEO_WIDHT = this.getInt("w")
                    VIDEO_HEIGHT = this.getInt("h")
                    FPS = ceil(this.getDouble("fr")).toInt()
                }
            }
        }
    }
}

/**
 * Execute export video task
 * Because exporting is a heavy task
 * Should put it in worker
 * */
class ExportVideoWorker(
    val onStart: () -> Unit,
    val onCompleted: (videoOutput: File) -> Unit,
    val lottieComposition: LottieComposition,
    val videoWidth: Int,
    val videoHeight: Int,
    val fps: Int
) : Runnable {

    override fun run() {
        onStart()
        var lottieVideoFile = convertLottieToVideo()
        addAudioToVideo(lottieVideoFile, File(""))
        onCompleted(lottieVideoFile)
    }

    /**
     * Convert from lottie drawable to video format
     * */
    private fun convertLottieToVideo(): File {
        Log.d("ExportVideoWorker", "Convert lottie to video...") // add log to metric time
        val outputFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "${System.currentTimeMillis()}.mp4"
        )

        val lottieDrawable = LottieDrawable().apply {
            composition = lottieComposition
        }

        val recordingOperation = RecordingOperation(
            MediaCodecRecorder(
                videoOutput = outputFile,
                width = videoWidth,
                height = videoHeight,
                framesPerSecond = fps
            ),
//            FFmpegRecorder(
//                outputFile,
//                videoWidth,
//                videoHeight
//            ),
            FrameCreator(lottieDrawable, videoWidth, videoHeight)
        )

        // execute record
        recordingOperation.start()

        Log.d("ExportVideoWorker", "Convert end!")
        return outputFile
    }

    /**
     * Add audio to video
     * */
    fun addAudioToVideo(video: File, audio: File) {
        Log.d("ExportVideoWorker", "Add audio to video")
        // TODO implement here
    }
}
