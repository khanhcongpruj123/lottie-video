package demo.idev.imagestory.renderer

import android.graphics.drawable.Drawable

interface Recorder {
    fun nextFrame(generateFrame: Drawable)
    fun start();
    fun end()
}