package demo.idev.imagestory.renderer.v1

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.media.*
import android.util.Log
import android.view.Surface
import demo.idev.imagestory.renderer.Recorder
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer

class MediaCodecRecorder(
    var mimeType: String = "video/avc",
    var bitRate: Int = DEFAULT_BITRATE,
    var iFrameInterval: Int = DEFAULT_IFRAME_INTERVAL,
    var framesPerSecond: Int = DEFAULT_FPS,
    var width: Int,
    var height: Int,
    var videoOutput: File,
    val audioFile: File
) : Closeable, Recorder {

    // video
    private val videoBufferInfo: MediaCodec.BufferInfo = MediaCodec.BufferInfo()
    private lateinit var videoEncoder: MediaCodec
    private lateinit var muxer: MediaMuxer
    private lateinit var inputSurface: Surface
    private var trackIndex: Int = 0
    private var muxerStarted: Boolean = false
    private var fakePts: Long = 0
    private var videoLengthInMs: Long = 0

    // audio
    private val audioExtractor = MediaExtractor()
    private lateinit var audioFormat: MediaFormat
    private var audioTrackIndex = 0
    private val audioBufferInfo = MediaCodec.BufferInfo()

    companion object {
        private const val VERBOSE = false
        private const val DEFAULT_IFRAME_INTERVAL = 5
        private const val DEFAULT_BITRATE = 4 * 1000 * 1000
        private const val DEFAULT_FPS = 60
        private const val TIMEOUT_USEC = 10000
    }

    override fun nextFrame(currentFrame: Drawable) {
        drainEncoder(false)
        val canvas = inputSurface.lockCanvas(null)
        try {
            canvas.drawColor(
                Color.TRANSPARENT,
                PorterDuff.Mode.CLEAR
            )  // Here you need to set some kind of background. Could be any color
            currentFrame.draw(canvas)
        } finally {
            inputSurface.unlockCanvasAndPost(canvas)
        }
    }

    override fun start() {
        if (width < 0) {
            throw IllegalArgumentException("You must set a positive width")
        }
        if (height < 0) {
            throw IllegalArgumentException("You must set a positive height")
        }
        if (framesPerSecond < 0) {
            throw IllegalArgumentException("You must set a positive number of frames per second")
        }

        val videoFormat = createMediaFormat(mimeType, width, height, bitRate, iFrameInterval)

        // Create a MediaCodec videoEncoder, and configure it with our format.  Get a Surface
        // we can use for input and wrap it with a class that handles the EGL work.
        startVideoEncoder(mimeType, videoFormat)

        // Create a MediaMuxer.  We can't add the video track and start() the muxer here,
        // because our MediaFormat doesn't have the Magic Goodies.  These can only be
        // obtained from the videoEncoder after it has started processing data.
        //
        // We're not actually interested in multiplexing audio.  We just want to convert
        // the raw H.264 elementary stream we get from MediaCodec into a .mp4 file.
        createMediaMuxer(videoOutput)


        audioExtractor.setDataSource(audioFile.absolutePath)
        audioExtractor.selectTrack(0)
        audioFormat = audioExtractor.getTrackFormat(0)
    }

    override fun end() {
        drainEncoder(true)
        close()
    }

    private fun createMediaMuxer(output: File) {
        if (VERBOSE) Log.d("Recorder", "inputSurface will go to $output")

        muxer = MediaMuxer(
            output.toString(),
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )

        trackIndex = -1
        audioTrackIndex = -1
        muxerStarted = false
    }

    private fun startVideoEncoder(mimeType: String, videoFormat: MediaFormat) {
        videoEncoder = MediaCodec.createEncoderByType(mimeType)
        videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = videoEncoder.createInputSurface()
        videoEncoder.start()
    }

    private fun createMediaFormat(
        mimeType: String,
        width: Int,
        height: Int,
        bitRate: Int,
        iFrameInterval: Int
    ): MediaFormat {
        val videoFormat = MediaFormat.createVideoFormat(mimeType, width, height)
        videoFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, framesPerSecond)
        videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
        if (VERBOSE) Log.d("Recorder", "format: $videoFormat")
        return videoFormat
    }

    /**
     * Extracts all pending data from the videoEncoder.
     *
     *
     * If endOfStream is not set, this returns when there is no more data to drain.  If it
     * is set, we send EOS to the videoEncoder, and then iterate until we see EOS on the inputSurface.
     * Calling this with endOfStream set should be done once, right before stopping the muxer.
     */
    @SuppressLint("SwitchIntDef")
    private fun drainEncoder(endOfStream: Boolean) {
        if (VERBOSE) Log.d("Recorder", "drain encoder($endOfStream)")

        if (endOfStream) {
            Log.d("Recorder", "sending end of stream to videoEncoder")
            videoEncoder.signalEndOfInputStream()
        }

        drainEncoderPostLollipop(endOfStream)
    }

    private fun drainEncoderPostLollipop(endOfStream: Boolean) {
        encodeLoop@ while (true) {
            val outputBufferIndex =
                videoEncoder.dequeueOutputBuffer(videoBufferInfo, TIMEOUT_USEC.toLong())
            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    // no inputSurface available yet
                    if (!endOfStream) {
                        break@encodeLoop // out of while
                    } else {
                        if (VERBOSE) Log.d(
                            "Recorder",
                            "no inputSurface available, spinning to await EOS"
                        )
                    }
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    startMuxer()
                }
                outputBufferIndex > 0 -> {
                    if (encodeVideoData(
                            videoEncoder.getOutputBuffer(outputBufferIndex)!!,
                            outputBufferIndex,
                            endOfStream
                        )
                    ) break@encodeLoop
                }
                else -> Log.w(
                    "Recorder",
                    "unexpected result from videoEncoder.dequeueOutputBuffer: $outputBufferIndex"
                )
            }
        }
    }

    private fun startMuxer() {
        // should happen before receiving buffers, and should only happen once
        if (muxerStarted) {
            throw RuntimeException("format changed twice")
        }
        val newFormat = videoEncoder.outputFormat
        Log.d("Recorder", "videoEncoder inputSurface format changed: $newFormat")

        trackIndex = muxer.addTrack(newFormat)
        audioTrackIndex = muxer.addTrack(audioFormat)
        muxer.start()
        muxerStarted = true
    }

    private fun encodeVideoData(
        encodedData: ByteBuffer,
        outputBufferIndex: Int,
        endOfStream: Boolean
    ): Boolean {
        if (videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            // The codec config data was pulled out and fed to the muxer when we got
            // the INFO_OUTPUT_FORMAT_CHANGED status. Ignore it.
            if (VERBOSE) Log.d("Recorder", "ignoring BUFFER_FLAG_CODEC_CONFIG")
            videoBufferInfo.size = 0
        }

        if (videoBufferInfo.size != 0) {
            if (!muxerStarted) {
                throw RuntimeException("muxer hasn't started")
            }

            // write video
            // adjust the ByteBuffer values to match BufferInfo
            encodedData.position(videoBufferInfo.offset)
            encodedData.limit(videoBufferInfo.offset + videoBufferInfo.size)
            videoBufferInfo.presentationTimeUs = fakePts
            // we save ms length of the video before buffer is disposed
            if (endOfStream) videoLengthInMs = videoBufferInfo.presentationTimeUs
            fakePts += 1000000L / framesPerSecond

            muxer.writeSampleData(trackIndex, encodedData, videoBufferInfo)


            // write video
            var buffer = ByteBuffer.allocate(encodedData.capacity())
            val chunkSize = audioExtractor.readSampleData(buffer, 0)
            if (chunkSize >= 0) {
                audioBufferInfo.presentationTimeUs = audioExtractor.sampleTime
                audioBufferInfo.flags = audioExtractor.sampleFlags
                audioBufferInfo.size = chunkSize

                muxer.writeSampleData(audioTrackIndex, buffer, audioBufferInfo)
                audioExtractor.advance()
            }

            if (VERBOSE) Log.d("Recorder", "sent ${videoBufferInfo.size} bytes to muxer")
        }

        videoEncoder.releaseOutputBuffer(outputBufferIndex, false)

        if (videoBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            if (!endOfStream) {
                Log.w("Recorder", "reached endRecording of stream unexpectedly")
            } else {
                if (VERBOSE) Log.d("Recorder", "endRecording of stream reached")
            }
            return true // out of while
        }
        return false
    }

    /**
     * Releases videoEncoder resources. May be called after partial / failed initialization.
     */
    override fun close() {
        if (VERBOSE) Log.d("Recorder", "releasing videoEncoder objects")
        videoEncoder.stop()
        videoEncoder.release()
        inputSurface.release()

        audioExtractor.release()

        muxer.stop()
        muxer.release()
    }
}