package com.example.offlinetranslator

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Offline Santali TTS engine.
 *
 * Model:
 *   sat_piper_model.onnx
 *
 * Configuration:
 *   sat_piper_model.onnx.json
 *
 * The model uses direct Ol Chiki character -> phoneme ID mapping.
 *
 * The ONNX session is loaded once and kept alive so that subsequent
 * synthesis calls do not repeatedly pay the model-loading cost.
 */
class SantaliTtsEngine(
    private val context: Context
) : AutoCloseable {

    companion object {
        private const val MODEL_FILE = "sat_piper_model.onnx"
        private const val CONFIG_FILE = "sat_piper_model.onnx.json"

        private const val SAMPLE_RATE = 16_000

        private const val SCALE_NOISE = 0.667f
        private const val SCALE_LENGTH = 1.0f
        private const val SCALE_NOISE_W = 0.8f

        private const val LOG_TAG = "SantaliTtsEngine"

        /*
         * Small delay used while waiting for AudioTrack playback
         * to advance. This does not affect the generated audio.
         */
        private const val PLAYBACK_POLL_MS = 20L

        /*
         * Safety margin after the final PCM sample has reached the
         * AudioTrack playback head.
         */
        private const val PLAYBACK_FINISH_MARGIN_MS = 100L
    }

    private val environment: OrtEnvironment =
        OrtEnvironment.getEnvironment()

    private val session: OrtSession

    private val phonemeIdMap: Map<String, Int>

    private var audioTrack: AudioTrack? = null

    init {
        phonemeIdMap =
            loadPhonemeIdMap()

        val modelBytes =
            context.assets
                .open(MODEL_FILE)
                .use { input ->
                    input.readBytes()
                }

        session =
            environment.createSession(
                modelBytes,
                OrtSession.SessionOptions()
            )

        Log.d(
            LOG_TAG,
            "Santali TTS ONNX session loaded successfully."
        )
    }

    /**
     * Returns true when every character in the supplied text exists
     * in the model's phoneme map.
     *
     * Spaces and punctuation are checked too.
     */
    fun supportsText(
        text: String
    ): Boolean {

        for (character in text) {

            val key =
                character.toString()

            if (
                !phonemeIdMap.containsKey(key)
            ) {
                return false
            }
        }

        return text.isNotEmpty()
    }

    /**
     * Returns the first unsupported character, or null if all
     * characters are supported.
     */
    fun findUnsupportedCharacter(
        text: String
    ): Char? {

        for (character in text) {

            if (
                !phonemeIdMap.containsKey(
                    character.toString()
                )
            ) {
                return character
            }
        }

        return null
    }

    /**
     * Converts supported Santali Ol Chiki text into the exact
     * phoneme ID sequence expected by this model.
     *
     * No additional tokenizer or phonemizer is used.
     */
    fun textToPhonemeIds(
        text: String
    ): LongArray {

        require(
            text.isNotEmpty()
        ) {
            "Santali TTS text cannot be empty."
        }

        val ids =
            LongArray(
                text.length
            )

        for (
        index in text.indices
        ) {

            val character =
                text[index]

            val id =
                phonemeIdMap[
                    character.toString()
                ]
                    ?: throw IllegalArgumentException(
                        "Unsupported Santali character: '$character' " +
                                "U+" +
                                character.code
                                    .toString(16)
                                    .uppercase()
                    )

            ids[index] =
                id.toLong()
        }

        return ids
    }

    /**
     * Synthesizes Santali text and returns mono PCM16 audio.
     *
     * Returned PCM:
     * - 16 kHz
     * - mono
     * - signed 16-bit PCM
     */
    fun synthesize(
        text: String
    ): ShortArray {

        val phonemeIds =
            textToPhonemeIds(
                text
            )

        val inputShape =
            longArrayOf(
                1L,
                phonemeIds.size.toLong()
            )

        val inputBuffer =
            LongBuffer.wrap(
                phonemeIds
            )

        val inputTensor =
            OnnxTensor.createTensor(
                environment,
                inputBuffer,
                inputShape
            )

        val inputLengths =
            LongArray(1) {
                phonemeIds.size.toLong()
            }

        val inputLengthsTensor =
            OnnxTensor.createTensor(
                environment,
                LongBuffer.wrap(
                    inputLengths
                ),
                longArrayOf(1L)
            )

        val scales =
            floatArrayOf(
                SCALE_NOISE,
                SCALE_LENGTH,
                SCALE_NOISE_W
            )

        val scalesTensor =
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(
                    scales
                ),
                longArrayOf(3L)
            )

        try {

            val inputs =
                mapOf(
                    "input" to inputTensor,
                    "input_lengths" to inputLengthsTensor,
                    "scales" to scalesTensor
                )

            session
                .run(inputs)
                .use { result ->

                    val outputValue =
                        result[0].value

                    /*
                     * The model currently produces:
                     *
                     * [1, 1, 1, samples]
                     *
                     * Flatten into one FloatArray.
                     */
                    val waveform =
                        flattenFloatOutput(
                            outputValue
                        )

                    if (
                        waveform.isEmpty()
                    ) {

                        throw IllegalStateException(
                            "Santali TTS model returned an empty waveform."
                        )
                    }

                    val pcm =
                        floatToPcm16(
                            waveform
                        )

                    var maxAmplitude =
                        0

                    for (sample in pcm) {

                        val amplitude =
                            abs(
                                sample.toInt()
                            )

                        if (
                            amplitude >
                            maxAmplitude
                        ) {
                            maxAmplitude =
                                amplitude
                        }
                    }

                    val durationMs =
                        pcm.size * 1000L /
                                SAMPLE_RATE

                    Log.d(
                        LOG_TAG,
                        "Synthesized audio: " +
                                "samples=${pcm.size}, " +
                                "duration=${durationMs}ms, " +
                                "maxAmplitude=$maxAmplitude"
                    )

                    return pcm
                }

        } finally {

            inputTensor.close()
            inputLengthsTensor.close()
            scalesTensor.close()
        }
    }

    /**
     * Synthesizes text and plays the complete generated sentence.
     *
     * Calls are serialized so that a new live-translation result
     * cannot interrupt the previous Santali sentence halfway through.
     *
     * The returned value is the elapsed time from the beginning
     * of this method until playback has completed.
     */
    @Synchronized
    fun synthesizeAndPlay(
        text: String
    ): Long {

        val startTime =
            System.currentTimeMillis()

        Log.d(
            LOG_TAG,
            "synthesizeAndPlay() started. Text length=${text.length}"
        )

        val pcm =
            synthesize(
                text
            )

        Log.d(
            LOG_TAG,
            "PCM generated. Starting AudioTrack playback..."
        )

        playPcm16(
            pcm
        )

        val elapsed =
            System.currentTimeMillis() -
                    startTime

        Log.d(
            LOG_TAG,
            "Audio playback completed after ${elapsed} ms."
        )

        return elapsed
    }

    /**
     * Stops currently playing generated Santali audio.
     */
    fun stop() {

        val track =
            audioTrack

        if (track == null) {
            return
        }

        try {

            track.pause()

        } catch (_: Exception) {
        }

        try {

            track.flush()

        } catch (_: Exception) {
        }
    }

    /**
     * Releases AudioTrack and ONNX Runtime session.
     *
     * Because synthesizeAndPlay() is synchronized, close() waits
     * for an active synthesis/playback call to finish before
     * destroying the ONNX session.
     */
    @Synchronized
    override fun close() {

        stop()

        try {

            audioTrack?.release()

        } catch (_: Exception) {
        }

        audioTrack =
            null

        try {

            session.close()

        } catch (_: Exception) {
        }

        Log.d(
            LOG_TAG,
            "Santali TTS engine closed."
        )
    }

    private fun loadPhonemeIdMap():
            Map<String, Int> {

        val jsonText =
            context.assets
                .open(CONFIG_FILE)
                .use { input ->
                    input
                        .bufferedReader(
                            Charsets.UTF_8
                        )
                        .readText()
                }

        val root =
            JSONObject(
                jsonText
            )

        val mapObject =
            root.getJSONObject(
                "phoneme_id_map"
            )

        val map =
            HashMap<String, Int>()

        val keys =
            mapObject.keys()

        while (
            keys.hasNext()
        ) {

            val key =
                keys.next()

            val idArray =
                mapObject.getJSONArray(
                    key
                )

            if (
                idArray.length() > 0
            ) {

                map[key] =
                    idArray.getInt(0)
            }
        }

        Log.d(
            LOG_TAG,
            "Loaded ${map.size} phoneme mappings."
        )

        return map
    }

    private fun flattenFloatOutput(
        value: Any?
    ): FloatArray {

        return when (value) {

            is FloatArray -> {

                value
            }

            is Array<*> -> {

                val output =
                    ArrayList<Float>()

                fun collect(
                    item: Any?
                ) {

                    when (item) {

                        is FloatArray -> {

                            for (
                            number in item
                            ) {

                                output.add(
                                    number
                                )
                            }
                        }

                        is Array<*> -> {

                            for (
                            child in item
                            ) {

                                collect(
                                    child
                                )
                            }
                        }

                        is Float -> {

                            output.add(
                                item
                            )
                        }

                        is Double -> {

                            output.add(
                                item.toFloat()
                            )
                        }

                        else -> {

                            throw IllegalStateException(
                                "Unexpected TTS output element: " +
                                        "${item?.javaClass?.name}"
                            )
                        }
                    }
                }

                collect(
                    value
                )

                output.toFloatArray()
            }

            else -> {

                throw IllegalStateException(
                    "Unexpected TTS output type: " +
                            "${value?.javaClass?.name}"
                )
            }
        }
    }

    private fun floatToPcm16(
        samples: FloatArray
    ): ShortArray {

        val pcm =
            ShortArray(
                samples.size
            )

        for (
        index in samples.indices
        ) {

            val sample =
                samples[index]

            val clipped =
                max(
                    -1.0f,
                    min(
                        1.0f,
                        sample
                    )
                )

            pcm[index] =
                (
                        clipped *
                                Short.MAX_VALUE
                        )
                    .toInt()
                    .toShort()
        }

        return pcm
    }

    /**
     * Plays generated PCM using AudioTrack streaming mode.
     *
     * This method does not return immediately after AudioTrack.write().
     * It waits until the AudioTrack playback head has consumed the
     * complete generated waveform.
     */
    private fun playPcm16(
        pcm: ShortArray
    ) {

        if (
            pcm.isEmpty()
        ) {

            throw IllegalArgumentException(
                "Cannot play empty PCM audio."
            )
        }

        val minBufferSize =
            AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        if (
            minBufferSize <= 0
        ) {

            throw IllegalStateException(
                "Could not determine AudioTrack buffer size."
            )
        }

        val bufferSize =
            max(
                minBufferSize,
                4096
            )

        /*
         * Stop and release any previous generated-audio track.
         *
         * Because synthesizeAndPlay() is synchronized, another
         * synthesizeAndPlay() call cannot reach this section while
         * the previous sentence is still playing.
         */
        audioTrack?.let { oldTrack ->

            try {
                oldTrack.stop()
            } catch (_: Exception) {
            }

            try {
                oldTrack.release()
            } catch (_: Exception) {
            }
        }

        audioTrack =
            null

        /*
         * Create a streaming AudioTrack.
         */
        val track =
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(
                            AudioAttributes.USAGE_MEDIA
                        )
                        .setContentType(
                            AudioAttributes.CONTENT_TYPE_SPEECH
                        )
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(
                            SAMPLE_RATE
                        )
                        .setEncoding(
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        .setChannelMask(
                            AudioFormat.CHANNEL_OUT_MONO
                        )
                        .build()
                )
                .setBufferSizeInBytes(
                    bufferSize
                )
                .setTransferMode(
                    AudioTrack.MODE_STREAM
                )
                .build()

        if (
            track.state !=
            AudioTrack.STATE_INITIALIZED
        ) {

            try {
                track.release()
            } catch (_: Exception) {
            }

            throw IllegalStateException(
                "AudioTrack failed to initialize. " +
                        "state=${track.state}"
            )
        }

        audioTrack =
            track

        Log.d(
            LOG_TAG,
            "AudioTrack initialized. " +
                    "bufferSize=$bufferSize, " +
                    "pcmSamples=${pcm.size}"
        )

        /*
         * Start the audio output before writing the generated
         * waveform.
         */
        track.play()

        Log.d(
            LOG_TAG,
            "AudioTrack.play() called. " +
                    "playState=${track.playState}"
        )

        /*
         * Write the PCM in chunks.
         *
         * AudioTrack.write() expects the number of Short values,
         * not the number of bytes.
         */
        var offset =
            0

        while (
            offset <
            pcm.size
        ) {

            val remaining =
                pcm.size -
                        offset

            val count =
                min(
                    2048,
                    remaining
                )

            val written =
                track.write(
                    pcm,
                    offset,
                    count,
                    AudioTrack.WRITE_BLOCKING
                )

            if (
                written < 0
            ) {

                throw IllegalStateException(
                    "AudioTrack.write() failed: $written"
                )
            }

            if (
                written == 0
            ) {

                throw IllegalStateException(
                    "AudioTrack.write() wrote 0 samples."
                )
            }

            offset +=
                written
        }

        Log.d(
            LOG_TAG,
            "All PCM written successfully. " +
                    "samples=$offset, " +
                    "playState=${track.playState}"
        )

        /*
         * IMPORTANT:
         *
         * AudioTrack.write() returning does NOT mean the speaker
         * has finished playing the audio. It only means the PCM
         * has been accepted by AudioTrack.
         *
         * Wait until the playback head reaches the end of the
         * generated PCM.
         */
        val expectedSamples =
            pcm.size

        val playbackWaitStart =
            System.currentTimeMillis()

        while (true) {

            val playbackPosition =
                try {
                    track.playbackHeadPosition
                } catch (_: Exception) {
                    0
                }

            /*
             * Playback is complete once the playback head has
             * consumed the entire PCM buffer.
             */
            if (
                playbackPosition >=
                expectedSamples
            ) {
                break
            }

            /*
             * If the track has unexpectedly stopped or paused
             * before reaching the end, stop waiting.
             *
             * This prevents an infinite loop if Android's audio
             * subsystem stops the track unexpectedly.
             */
            if (
                track.playState ==
                AudioTrack.PLAYSTATE_STOPPED ||
                track.playState ==
                AudioTrack.PLAYSTATE_PAUSED
            ) {

                Log.w(
                    LOG_TAG,
                    "AudioTrack stopped before playback completed. " +
                            "position=$playbackPosition, " +
                            "expected=$expectedSamples"
                )

                break
            }

            Thread.sleep(
                PLAYBACK_POLL_MS
            )
        }

        /*
         * Give the Android audio output path a small safety margin
         * after the playback head reaches the final generated
         * sample.
         */
        try {

            Thread.sleep(
                PLAYBACK_FINISH_MARGIN_MS
            )

        } catch (_: InterruptedException) {

            Thread.currentThread().interrupt()
        }

        val playbackElapsed =
            System.currentTimeMillis() -
                    playbackWaitStart

        Log.d(
            LOG_TAG,
            "AudioTrack playback finished. " +
                    "playbackWait=${playbackElapsed}ms, " +
                    "finalPosition=${track.playbackHeadPosition}"
        )
    }
}