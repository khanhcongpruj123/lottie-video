package demo.idev.imagestory.renderer.v2

import android.R.drawable
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import demo.idev.imagestory.renderer.Recorder
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameRecorder
import org.bytedeco.javacv.Java2DFrameConverter
import java.io.File


class FFmpegRecorder(
    val outputFile: File,
    val videoWidth: Int,
    val videoHeight: Int
) : Recorder {

    private val frameRecorder = FFmpegFrameRecorder(outputFile, videoWidth, videoHeight)

    override fun nextFrame(generateFrame: Drawable) {
        val bitmap = generateFrame.toBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)
        val frame = Frame(bitmap.width, bitmap.height, 8, 4)
        frame.image[0].position(0)
        bitmap.copyPixelsToBuffer(frame.image[0])
        frameRecorder.record(frame)
    }

    override fun start() {
        frameRecorder.start()
    }

    override fun end() {
        frameRecorder.stop();
    }
}