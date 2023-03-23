package demo.idev.imagestory

import android.util.Log

class RecordingOperation(
    private val recorder: Recorder,
    private val frameCreator: FrameCreator,
) {

    fun start() {
        while (isRecording()) {
            Log.d(
                "RecordingOperation",
                "Process ${frameCreator.currentFrame()}/${frameCreator.maxFrame()}"
            )
            recorder.nextFrame(frameCreator.generateFrame())
        }
        recorder.end()
    }

    private fun isRecording() = !frameCreator.hasEnded()
}