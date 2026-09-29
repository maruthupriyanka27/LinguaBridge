package com.example.offlinetranslator

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.Looper

import androidx.core.content.ContextCompat

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService

object VoiceRecognizer {

    private var model: Model? = null
    private var audioRecord: AudioRecord? = null
    private var recognitionThread: Thread? = null

    @Volatile
    private var isListening = false

    /*
     * Vosk can produce multiple accepted/final segments
     * during one natural spoken sentence.
     *
     * We wait for this amount of silence after the latest
     * accepted segment before sending the complete sentence
     * to MainActivity.
     */
    private const val FINAL_SILENCE_DELAY_MS = 800L

    private val finalHandler =
        Handler(Looper.getMainLooper())

    private var pendingFinalRunnable: Runnable? = null

    private var pendingFinalText = ""

    private var pendingFinalCallback:
            ((String) -> Unit)? = null


    // ============================================================
    // LOAD HINDI VOSK MODEL
    // ============================================================

    fun ensureModelLoaded(
        context: Context,
        onReady: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (model != null) {
            onReady()
            return
        }

        StorageService.unpack(
            context,
            "vosk-model-small-hi-0.22",
            "model",
            { loadedModel: Model ->

                model = loadedModel

                Handler(
                    Looper.getMainLooper()
                ).post {
                    onReady()
                }
            },
            { exception ->

                Handler(
                    Looper.getMainLooper()
                ).post {

                    onError(
                        exception.message
                            ?: "Failed to load Hindi Vosk model."
                    )
                }
            }
        )
    }


    // ============================================================
    // WAV FILE RECOGNITION
    // ============================================================

    fun recognizeWavFile(
        context: Context,
        uri: Uri
    ): String {

        val currentModel =
            model
                ?: throw IllegalStateException(
                    "Hindi Vosk model is not loaded yet."
                )

        val (sampleRate, pcmData) =
            readWavPcm(
                context,
                uri
            )

        val recognizer =
            Recognizer(
                currentModel,
                sampleRate.toFloat()
            )

        val chunkSize = 4096
        var offset = 0

        while (
            offset < pcmData.size
        ) {

            val end =
                minOf(
                    offset + chunkSize,
                    pcmData.size
                )

            val chunk =
                pcmData.copyOfRange(
                    offset,
                    end
                )

            recognizer.acceptWaveForm(
                chunk,
                chunk.size
            )

            offset = end
        }

        val resultJson =
            recognizer.finalResult

        recognizer.close()

        return try {

            JSONObject(resultJson)
                .optString(
                    "text",
                    ""
                )

        } catch (
            e: Exception
        ) {

            resultJson
        }
    }


    // ============================================================
    // START LIVE RECOGNITION
    // ============================================================

    fun startLiveRecognition(
        context: Context,
        onPartialResult: (String) -> Unit,
        onFinalResult: (String) -> Unit,
        onError: (String) -> Unit
    ) {

        if (isListening) {
            return
        }

        val currentModel =
            model

        if (currentModel == null) {

            postError(
                onError,
                "Hindi Vosk model is not loaded yet."
            )

            return
        }

        val permissionGranted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) ==
                    PackageManager.PERMISSION_GRANTED

        if (!permissionGranted) {

            postError(
                onError,
                "Microphone permission is required."
            )

            return
        }

        val sampleRate = 16000

        val minBufferSize =
            AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

        if (
            minBufferSize ==
            AudioRecord.ERROR ||
            minBufferSize ==
            AudioRecord.ERROR_BAD_VALUE
        ) {

            postError(
                onError,
                "Could not initialize microphone."
            )

            return
        }

        val bufferSize =
            maxOf(
                minBufferSize * 2,
                4096
            )

        /*
         * Use the same audio source used by the
         * Vosk Android live recognition approach.
         */
        val recorder =
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

        if (
            recorder.state !=
            AudioRecord.STATE_INITIALIZED
        ) {

            recorder.release()

            postError(
                onError,
                "Microphone could not be initialized."
            )

            return
        }

        audioRecord =
            recorder

        isListening =
            true

        /*
         * Make sure no result from a previous listening session
         * is still waiting.
         */
        clearPendingFinal()


        recognitionThread =
            Thread {

                val recognizer =
                    Recognizer(
                        currentModel,
                        sampleRate.toFloat()
                    )

                val buffer =
                    ByteArray(4096)

                try {

                    recorder.startRecording()

                    if (
                        recorder.recordingState !=
                        AudioRecord.RECORDSTATE_RECORDING
                    ) {

                        postError(
                            onError,
                            "Microphone failed to start recording."
                        )

                        return@Thread
                    }


                    postPartial(
                        onPartialResult,
                        ""
                    )


                    while (
                        isListening
                    ) {

                        val bytesRead =
                            recorder.read(
                                buffer,
                                0,
                                buffer.size
                            )

                        if (
                            bytesRead < 0
                        ) {

                            if (isListening) {

                                postError(
                                    onError,
                                    "Microphone read error: $bytesRead"
                                )
                            }

                            break
                        }


                        if (
                            bytesRead > 0
                        ) {

                            val accepted =
                                recognizer.acceptWaveForm(
                                    buffer,
                                    bytesRead
                                )


                            if (
                                accepted
                            ) {

                                val resultJson =
                                    recognizer.result

                                val text =
                                    extractText(
                                        resultJson
                                    )

                                if (
                                    text.isNotBlank()
                                ) {

                                    /*
                                     * Do not immediately send this
                                     * result to MainActivity.
                                     *
                                     * Add it to the current utterance.
                                     */
                                    addFinalSegment(
                                        text,
                                        onFinalResult
                                    )
                                }

                            } else {

                                val partialJson =
                                    recognizer.partialResult

                                val partialText =
                                    extractPartialText(
                                        partialJson
                                    )

                                if (
                                    partialText.isNotBlank()
                                ) {

                                    postPartial(
                                        onPartialResult,
                                        partialText
                                    )
                                }
                            }
                        }
                    }


                    /*
                     * STOP was pressed.
                     *
                     * Flush anything still inside Vosk.
                     */
                    val finalJson =
                        recognizer.finalResult

                    val finalText =
                        extractText(
                            finalJson
                        )

                    if (
                        finalText.isNotBlank()
                    ) {

                        addFinalSegment(
                            finalText,
                            onFinalResult
                        )
                    }


                    /*
                     * Because STOP means the teacher has explicitly
                     * ended the recording, do not wait another 800 ms.
                     *
                     * Send the accumulated sentence immediately.
                     */
                    flushPendingFinal()


                } catch (
                    e: Exception
                ) {

                    if (
                        isListening
                    ) {

                        postError(
                            onError,
                            e.message
                                ?: "Live recognition failed."
                        )
                    }

                } finally {

                    try {

                        if (
                            recorder.recordingState ==
                            AudioRecord.RECORDSTATE_RECORDING
                        ) {

                            recorder.stop()
                        }

                    } catch (
                        _: Exception
                    ) {
                    }


                    try {

                        recorder.release()

                    } catch (
                        _: Exception
                    ) {
                    }


                    audioRecord =
                        null


                    try {

                        recognizer.close()

                    } catch (
                        _: Exception
                    ) {
                    }
                }

            }.also {
                it.start()
            }
    }


    // ============================================================
    // ADD VOSK FINAL SEGMENT
    // ============================================================

    private fun addFinalSegment(
        text: String,
        callback: (String) -> Unit
    ) {

        val cleaned =
            text.trim()

        if (
            cleaned.isEmpty()
        ) {
            return
        }


        synchronized(this) {

            if (
                pendingFinalText.isEmpty()
            ) {

                pendingFinalText =
                    cleaned

            } else {

                pendingFinalText =
                    "$pendingFinalText $cleaned"
            }

            /*
             * Remember the callback that belongs to this
             * listening session.
             */
            pendingFinalCallback =
                callback
        }


        /*
         * A new segment means the teacher may still be speaking.
         *
         * Cancel the previous 800 ms timer and start a new one.
         */
        cancelPendingFinalTimer()


        val runnable =
            Runnable {

                deliverPendingFinal()
            }


        synchronized(this) {

            pendingFinalRunnable =
                runnable
        }


        finalHandler.postDelayed(
            runnable,
            FINAL_SILENCE_DELAY_MS
        )
    }


    // ============================================================
    // DELIVER ACCUMULATED FINAL SENTENCE
    // ============================================================

    private fun deliverPendingFinal() {

        val finalText: String
        val callback: ((String) -> Unit)?

        synchronized(this) {

            finalText =
                pendingFinalText.trim()

            callback =
                pendingFinalCallback

            pendingFinalText =
                ""

            pendingFinalCallback =
                null

            pendingFinalRunnable =
                null
        }


        if (
            finalText.isEmpty()
        ) {
            return
        }

        if (
            callback == null
        ) {
            return
        }


        postFinal(
            callback,
            finalText
        )
    }


    // ============================================================
    // FLUSH ACCUMULATED FINAL SENTENCE
    // ============================================================

    private fun flushPendingFinal() {

        cancelPendingFinalTimer()

        deliverPendingFinal()
    }


    // ============================================================
    // CANCEL PENDING FINAL TIMER
    // ============================================================

    private fun cancelPendingFinalTimer() {

        val runnable: Runnable?

        synchronized(this) {

            runnable =
                pendingFinalRunnable

            pendingFinalRunnable =
                null
        }


        if (
            runnable != null
        ) {

            finalHandler.removeCallbacks(
                runnable
            )
        }
    }


    // ============================================================
    // CLEAR PENDING FINAL
    // ============================================================

    private fun clearPendingFinal() {

        cancelPendingFinalTimer()

        synchronized(this) {

            pendingFinalText =
                ""

            pendingFinalCallback =
                null
        }
    }


    // ============================================================
    // STOP LIVE RECOGNITION
    // ============================================================

    fun stopLiveRecognition() {

        isListening =
            false

        try {

            audioRecord?.stop()

        } catch (
            _: Exception
        ) {
        }

        audioRecord =
            null

        recognitionThread =
            null
    }


    // ============================================================
    // EXTRACT FINAL TEXT
    // ============================================================

    private fun extractText(
        json: String
    ): String {

        return try {

            JSONObject(json)
                .optString(
                    "text",
                    ""
                )
                .trim()

        } catch (
            _: Exception
        ) {

            ""
        }
    }


    // ============================================================
    // EXTRACT PARTIAL TEXT
    // ============================================================

    private fun extractPartialText(
        json: String
    ): String {

        return try {

            JSONObject(json)
                .optString(
                    "partial",
                    ""
                )
                .trim()

        } catch (
            _: Exception
        ) {

            ""
        }
    }


    // ============================================================
    // POST PARTIAL RESULT ON MAIN THREAD
    // ============================================================

    private fun postPartial(
        callback: (String) -> Unit,
        text: String
    ) {

        Handler(
            Looper.getMainLooper()
        ).post {

            callback(
                text
            )
        }
    }


    // ============================================================
    // POST FINAL RESULT ON MAIN THREAD
    // ============================================================

    private fun postFinal(
        callback: (String) -> Unit,
        text: String
    ) {

        Handler(
            Looper.getMainLooper()
        ).post {

            callback(
                text
            )
        }
    }


    // ============================================================
    // POST ERROR ON MAIN THREAD
    // ============================================================

    private fun postError(
        callback: (String) -> Unit,
        error: String
    ) {

        Handler(
            Looper.getMainLooper()
        ).post {

            callback(
                error
            )
        }
    }


    // ============================================================
    // READ WAV PCM
    // ============================================================

    private fun readWavPcm(
        context: Context,
        uri: Uri
    ): Pair<Int, ByteArray> {

        val inputStream =
            context.contentResolver
                .openInputStream(uri)
                ?: throw IllegalArgumentException(
                    "Could not open audio file."
                )

        val allBytes =
            inputStream.use {
                it.readBytes()
            }


        if (
            allBytes.size < 44
        ) {

            throw IllegalArgumentException(
                "Invalid WAV file."
            )
        }


        val riff =
            String(
                allBytes,
                0,
                4,
                Charsets.US_ASCII
            )


        val wave =
            String(
                allBytes,
                8,
                4,
                Charsets.US_ASCII
            )


        if (
            riff != "RIFF" ||
            wave != "WAVE"
        ) {

            throw IllegalArgumentException(
                "This isn't a valid WAV file. MP3 support is not added yet."
            )
        }


        val sampleRate =
            (allBytes[24].toInt() and 0xFF) or
                    ((allBytes[25].toInt() and 0xFF) shl 8) or
                    ((allBytes[26].toInt() and 0xFF) shl 16) or
                    ((allBytes[27].toInt() and 0xFF) shl 24)


        val channels =
            (allBytes[22].toInt() and 0xFF) or
                    ((allBytes[23].toInt() and 0xFF) shl 8)


        val bitsPerSample =
            (allBytes[34].toInt() and 0xFF) or
                    ((allBytes[35].toInt() and 0xFF) shl 8)


        if (
            bitsPerSample != 16 ||
            channels != 1
        ) {

            throw IllegalArgumentException(
                "Expected 16-bit mono PCM WAV. Found: " +
                        "$bitsPerSample-bit, " +
                        "$channels channel(s)."
            )
        }


        val dataStart =
            44


        if (
            dataStart >= allBytes.size
        ) {

            throw IllegalArgumentException(
                "WAV file contains no audio data."
            )
        }


        val pcmData =
            allBytes.copyOfRange(
                dataStart,
                allBytes.size
            )


        return Pair(
            sampleRate,
            pcmData
        )
    }
}