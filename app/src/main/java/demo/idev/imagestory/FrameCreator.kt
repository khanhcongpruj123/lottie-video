package demo.idev.imagestory

import android.graphics.drawable.Drawable
import com.airbnb.lottie.LottieDrawable

class FrameCreator(
    private val lottieDrawable: LottieDrawable,
    private val videoWidth: Int,
    private val videoHeight: Int
) {

    init {
        lottieDrawable.scale = videoWidth.toFloat() / lottieDrawable.intrinsicWidth
    }

    private val durationInFrames: Int = lottieDrawable.composition.durationFrames.toInt()
    private var currentFrame: Int = 0

    /**
     * Return current frame
     * and move to next frame
     * */
    fun generateFrame(): Drawable {
        lottieDrawable.frame = currentFrame
        ++currentFrame
        return lottieDrawable
    }

    fun maxFrame() = durationInFrames

    fun currentFrame() = currentFrame

    fun hasEnded() = currentFrame > durationInFrames
}