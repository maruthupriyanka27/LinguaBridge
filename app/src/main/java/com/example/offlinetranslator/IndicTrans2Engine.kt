package com.example.offlinetranslator

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * IndicTrans2 Indic -> Indic INT8 ONNX engine.
 *
 * Model bundle:
 *
 *   tokenizer_src.json
 *          ↓
 *   encoder_model.onnx
 *          ↓
 *   decoder_model.onnx
 *          ↓
 *   decoder_with_past_model.onnx
 *          ↓
 *   tokenizer_tgt.json vocabulary
 *          ↓
 *   target text
 *
 * Current verified target:
 *
 *   Hindi   = hin_Deva
 *   Santali = sat_Olck
 *
 * IMPORTANT:
 * - Uses the NEW three-graph INT8 model.
 * - Does NOT use indictrans2_merged_int8_v2.onnx.
 * - Does NOT use the old SentencePiece mapping path.
 * - Keeps the existing HFTokenizer.kt unchanged.
 */
class IndicTrans2Engine(
    private val context: Context
) {

    companion object {

        private const val TAG =
            "IndicTrans2Engine"

        private const val ASSET_DIR =
            "indictrans2"

        private const val SRC_TOKENIZER_ASSET =
            "$ASSET_DIR/tokenizer_src.json"

        private const val TGT_TOKENIZER_ASSET =
            "$ASSET_DIR/tokenizer_tgt.json"

        private const val ENCODER_ASSET =
            "$ASSET_DIR/encoder_model.onnx"

        private const val ENCODER_DATA_ASSET =
            "$ASSET_DIR/encoder_model.onnx.data"

        private const val DECODER_ASSET =
            "$ASSET_DIR/decoder_model.onnx"

        private const val DECODER_DATA_ASSET =
            "$ASSET_DIR/decoder_shared.onnx.data"

        private const val DECODER_WITH_PAST_ASSET =
            "$ASSET_DIR/decoder_with_past_model.onnx"

        private const val CONFIG_ASSET =
            "$ASSET_DIR/config.json"

        private const val GENERATION_CONFIG_ASSET =
            "$ASSET_DIR/generation_config.json"

        private const val SOURCE_DICT_SIZE =
            122706

        private const val TARGET_DICT_SIZE =
            122672

        private const val MAX_SOURCE_LENGTH =
            256

        private const val MAX_NEW_TOKENS =
            128

        private const val BOS_ID =
            0L

        private const val PAD_ID =
            1L

        private const val EOS_ID =
            2L

        private const val UNK_ID =
            3L

        private const val DECODER_START_ID =
            2L

        private const val HINDI =
            "hin_Deva"

        private const val SANTALI =
            "sat_Olck"
    }

    private val ortEnvironment =
        OrtEnvironment.getEnvironment()

    private var encoderSession:
            OrtSession? = null

    private var decoderSession:
            OrtSession? = null

    private var decoderWithPastSession:
            OrtSession? = null

    private var sourceTokenizer:
            HFTokenizer? = null

    /**
     * Target tokenizer model vocabulary.
     *
     * Index = token ID
     * Value = token string
     */
    private var targetIdToToken:
            Array<String?>? = null

    private var decoderLayerCount =
        0

    // ============================================================
    // ASSET COPY
    // ============================================================

    private fun copyAssetToInternalStorage(
        assetName: String
    ): File {

        val relativeName =
            assetName.removePrefix(
                "$ASSET_DIR/"
            )

        val outputDir =
            File(
                context.filesDir,
                ASSET_DIR
            )

        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val outputFile =
            File(
                outputDir,
                relativeName
            )

        if (
            !outputFile.exists() ||
            outputFile.length() == 0L
        ) {

            context.assets
                .open(assetName)
                .use { input ->

                    FileOutputStream(
                        outputFile
                    ).use { output ->

                        input.copyTo(
                            output,
                            1024 * 1024
                        )
                    }
                }

            Log.d(
                TAG,
                "Copied $assetName -> ${outputFile.length()} bytes"
            )
        }

        return outputFile
    }

    // ============================================================
    // TARGET VOCABULARY
    // ============================================================

    private fun loadTargetVocabulary(
        tokenizerFile: File
    ) {

        Log.d(
            TAG,
            "Loading tokenizer_tgt.json..."
        )

        val jsonText =
            tokenizerFile
                .inputStream()
                .bufferedReader(
                    Charsets.UTF_8
                )
                .use {
                    it.readText()
                }

        val root =
            JSONObject(
                jsonText
            )

        val model =
            root.getJSONObject(
                "model"
            )

        val vocab =
            model.getJSONObject(
                "vocab"
            )

        val idToToken =
            arrayOfNulls<String>(
                TARGET_DICT_SIZE
            )

        val keys =
            vocab.keys()

        var loaded =
            0

        while (
            keys.hasNext()
        ) {

            val token =
                keys.next()

            val id =
                vocab.getInt(
                    token
                )

            /*
             * IndicTrans2 model output vocabulary is smaller
             * than the complete tokenizer vocabulary.
             *
             * The official Python helper clamps IDs >= tgt_dict_size
             * to <unk>.
             */
            if (
                id >= 0 &&
                id < TARGET_DICT_SIZE
            ) {

                idToToken[id] =
                    token

                loaded++
            }
        }

        targetIdToToken =
            idToToken

        Log.d(
            TAG,
            "Loaded target vocabulary entries = $loaded"
        )
    }

    // ============================================================
    // SOURCE PREPROCESSING
    // ============================================================

    /**
     * For the currently verified Hindi -> Santali pipeline.
     *
     * The verified Python IndicProcessor produced:
     *
     *   hin_Deva sat_Olck बच्चों , आज हम एक नया पाठ पढ़ेंगे ।
     *
     * before tokenizer.encode().
     */
    private fun preprocessSourceText(
        text: String
    ): String {

        var result =
            text
                .replace(
                    "\u00A0",
                    " "
                )
                .trim()

        if (
            result.isEmpty()
        ) {
            return ""
        }

        /*
         * Separate common punctuation.
         *
         * Example:
         *
         *   बच्चों,
         *
         * becomes:
         *
         *   बच्चों ,
         */
        result =
            result.replace(
                Regex(
                    """([,!?;:])"""
                ),
                " $1 "
            )

        /*
         * Hindi/Devanagari danda.
         */
        result =
            result.replace(
                "।",
                " । "
            )

        result =
            result.replace(
                Regex(
                    """\s+"""
                ),
                " "
            )

        return result.trim()
    }

    // ============================================================
    // SOURCE TOKENIZATION
    // ============================================================

    private fun buildSourceIds(
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): LongArray {

        require(
            sourceLanguage == HINDI ||
                    sourceLanguage == SANTALI
        ) {
            "Unsupported source language: $sourceLanguage"
        }

        require(
            targetLanguage == HINDI ||
                    targetLanguage == SANTALI
        ) {
            "Unsupported target language: $targetLanguage"
        }

        val cleanedText =
            preprocessSourceText(
                text
            )

        require(
            cleanedText.isNotEmpty()
        ) {
            "Input text is empty."
        }

        /*
         * Exact format verified by the Python reference:
         *
         *   hin_Deva sat_Olck <text>
         */
        val prefixedText =
            "$sourceLanguage $targetLanguage $cleanedText"

        Log.d(
            TAG,
            "PREPROCESSED TEXT = $prefixedText"
        )

        val tokenizer =
            sourceTokenizer
                ?: error(
                    "Source tokenizer is not loaded."
                )

        val rawIds =
            tokenizer.encode(
                prefixedText
            )

        require(
            rawIds.isNotEmpty()
        ) {
            "Source tokenizer returned no IDs."
        }

        Log.d(
            TAG,
            "SOURCE TOKENIZER IDS = ${
                rawIds.joinToString(",")
            }"
        )

        val limit =
            minOf(
                rawIds.size,
                MAX_SOURCE_LENGTH
            )

        val sourceIds =
            ArrayList<Long>(
                limit + 1
            )

        for (
        index in 0 until limit
        ) {

            val id =
                rawIds[index]
                    .toLong()

            /*
             * Match the official Python helper:
             *
             *   if i < src_dict_size
             *   else <unk>
             */
            val safeId =
                if (
                    id >= 0L &&
                    id < SOURCE_DICT_SIZE
                ) {
                    id
                } else {
                    UNK_ID
                }

            sourceIds.add(
                safeId
            )
        }

        /*
         * The verified tokenizer already produced EOS.
         *
         * This only protects us if the native wrapper behaves
         * differently in another input case.
         */
        if (
            sourceIds.isEmpty() ||
            sourceIds.last() != EOS_ID
        ) {

            if (
                sourceIds.size <
                MAX_SOURCE_LENGTH
            ) {

                sourceIds.add(
                    EOS_ID
                )

            } else {

                sourceIds[
                    sourceIds.lastIndex
                ] = EOS_ID
            }
        }

        Log.d(
            TAG,
            "FINAL SOURCE IDS = ${
                sourceIds.joinToString(",")
            }"
        )

        return sourceIds.toLongArray()
    }

    // ============================================================
    // TARGET DECODING
    // ============================================================

    /**
     * Decode BPE token IDs into text.
     *
     * tokenizer_tgt.json uses Metaspace:
     *
     *   ▁ = word boundary / space
     *
     * So the token strings are concatenated first and then
     * ▁ is converted to a normal space.
     */
    private fun decodeTargetIds(
        generatedIds: List<Long>,
        targetLanguage: String
    ): String {

        val vocabulary =
            targetIdToToken
                ?: error(
                    "Target vocabulary is not loaded."
                )

        val builder =
            StringBuilder()

        for (
        id in generatedIds
        ) {

            /*
             * Special tokens.
             */
            if (
                id == BOS_ID ||
                id == PAD_ID ||
                id == EOS_ID ||
                id == UNK_ID
            ) {
                continue
            }

            /*
             * Match official target vocabulary limit.
             */
            if (
                id < 0L ||
                id >= TARGET_DICT_SIZE
            ) {
                continue
            }

            val token =
                vocabulary[
                    id.toInt()
                ]

            if (
                token != null
            ) {
                builder.append(
                    token
                )
            }
        }

        var result =
            builder
                .toString()
                .replace(
                    "▁",
                    " "
                )
                .replace(
                    Regex(
                        """\s+"""
                    ),
                    " "
                )
                .trim()

        /*
         * Remove unwanted spaces before normal punctuation.
         */
        result =
            result.replace(
                Regex(
                    """\s+([,!?;:])"""
                ),
                "$1"
            )

        /*
         * Hindi danda handling.
         */
        if (
            targetLanguage == HINDI
        ) {

            result =
                result.replace(
                    Regex(
                        """\s+।"""
                    ),
                    "।"
                )
        }

        return result
            .replace(
                Regex(
                    """\s+"""
                ),
                " "
            )
            .trim()
    }

    // ============================================================
    // ONNX OUTPUT -> TENSOR
    // ============================================================

    /**
     * OrtSession.Result exposes OnnxValue.
     *
     * decoder cache inputs require OnnxTensorLike.
     */
    private fun requireTensorLike(
        value: OnnxValue,
        description: String
    ): OnnxTensorLike {

        return value as? OnnxTensorLike
            ?: error(
                "$description is not an OnnxTensorLike: ${value.javaClass.name}"
            )
    }

    // ============================================================
    // PAST KEY/VALUE CACHE
    // ============================================================

    /**
     * The official ONNX helper stores decoder outputs in this order:
     *
     * layer 0:
     *   decoder.key
     *   decoder.value
     *   encoder.key
     *   encoder.value
     *
     * layer 1:
     *   decoder.key
     *   decoder.value
     *   encoder.key
     *   encoder.value
     *
     * etc.
     */
    private fun buildPastInputs(
        previousDecoderResult:
        OrtSession.Result
    ): MutableMap<String, OnnxTensorLike> {

        val inputs =
            HashMap<String, OnnxTensorLike>(
                decoderLayerCount * 4
            )

        for (
        layer in 0 until decoderLayerCount
        ) {

            val base =
                1 + layer * 4

            inputs[
                "past_key_values.$layer.decoder.key"
            ] =
                requireTensorLike(
                    previousDecoderResult[
                        base
                    ],
                    "past decoder key layer $layer"
                )

            inputs[
                "past_key_values.$layer.decoder.value"
            ] =
                requireTensorLike(
                    previousDecoderResult[
                        base + 1
                    ],
                    "past decoder value layer $layer"
                )

            inputs[
                "past_key_values.$layer.encoder.key"
            ] =
                requireTensorLike(
                    previousDecoderResult[
                        base + 2
                    ],
                    "past encoder key layer $layer"
                )

            inputs[
                "past_key_values.$layer.encoder.value"
            ] =
                requireTensorLike(
                    previousDecoderResult[
                        base + 3
                    ],
                    "past encoder value layer $layer"
                )
        }

        return inputs
    }

    // ============================================================
    // LOAD
    // ============================================================

    fun load() {

        if (
            isLoaded()
        ) {
            return
        }

        Log.d(
            TAG,
            "Starting IndicTrans2 INT8 three-graph load..."
        )

        /*
         * Copy ONNX graphs and sidecar files.
         *
         * Important:
         * The .onnx.data files MUST remain beside their .onnx files.
         */

        val encoderFile =
            copyAssetToInternalStorage(
                ENCODER_ASSET
            )

        copyAssetToInternalStorage(
            ENCODER_DATA_ASSET
        )

        val decoderFile =
            copyAssetToInternalStorage(
                DECODER_ASSET
            )

        copyAssetToInternalStorage(
            DECODER_DATA_ASSET
        )

        val decoderWithPastFile =
            copyAssetToInternalStorage(
                DECODER_WITH_PAST_ASSET
            )

        val sourceTokenizerFile =
            copyAssetToInternalStorage(
                SRC_TOKENIZER_ASSET
            )

        val targetTokenizerFile =
            copyAssetToInternalStorage(
                TGT_TOKENIZER_ASSET
            )

        /*
         * Keep configs in the same private bundle.
         */
        copyAssetToInternalStorage(
            CONFIG_ASSET
        )

        copyAssetToInternalStorage(
            GENERATION_CONFIG_ASSET
        )

        // --------------------------------------------------------
        // Source tokenizer
        // --------------------------------------------------------

        sourceTokenizer =
            HFTokenizer(
                sourceTokenizerFile.readBytes()
            )

        // --------------------------------------------------------
        // Target tokenizer vocabulary
        // --------------------------------------------------------

        loadTargetVocabulary(
            targetTokenizerFile
        )

        // --------------------------------------------------------
        // ONNX sessions
        // --------------------------------------------------------

        val sessionOptions =
            OrtSession.SessionOptions()

        encoderSession =
            ortEnvironment.createSession(
                encoderFile.absolutePath,
                sessionOptions
            )

        decoderSession =
            ortEnvironment.createSession(
                decoderFile.absolutePath,
                sessionOptions
            )

        decoderWithPastSession =
            ortEnvironment.createSession(
                decoderWithPastFile.absolutePath,
                sessionOptions
            )

        val decoder =
            decoderSession
                ?: error(
                    "Decoder session failed to load."
                )

        /*
         * Official structure:
         *
         *   output 0 = logits
         *   output 1.. = four KV tensors per layer
         */
        val decoderOutputCount =
            decoder
                .outputNames
                .size

        require(
            decoderOutputCount > 1
        ) {
            "Decoder produced no KV outputs."
        }

        require(
            (decoderOutputCount - 1) % 4 == 0
        ) {
            "Unexpected decoder output count: $decoderOutputCount"
        }

        decoderLayerCount =
            (decoderOutputCount - 1) / 4

        Log.d(
            TAG,
            "Decoder output count = $decoderOutputCount"
        )

        Log.d(
            TAG,
            "Decoder layer count = $decoderLayerCount"
        )

        Log.d(
            TAG,
            "ENCODER INPUTS = ${
                encoderSession!!
                    .inputNames
                    .toList()
            }"
        )

        Log.d(
            TAG,
            "ENCODER OUTPUTS = ${
                encoderSession!!
                    .outputNames
                    .toList()
            }"
        )

        Log.d(
            TAG,
            "DECODER INPUTS = ${
                decoderSession!!
                    .inputNames
                    .toList()
            }"
        )

        Log.d(
            TAG,
            "DECODER OUTPUTS = ${
                decoderSession!!
                    .outputNames
                    .toList()
            }"
        )

        Log.d(
            TAG,
            "DECODER_WITH_PAST INPUTS = ${
                decoderWithPastSession!!
                    .inputNames
                    .toList()
            }"
        )

        Log.d(
            TAG,
            "DECODER_WITH_PAST OUTPUTS = ${
                decoderWithPastSession!!
                    .outputNames
                    .toList()
            }"
        )

        Log.d(
            TAG,
            "IndicTrans2 three-graph engine READY"
        )
    }

    // ============================================================
    // LOADED STATUS
    // ============================================================

    fun isLoaded(): Boolean {

        return encoderSession != null &&
                decoderSession != null &&
                decoderWithPastSession != null &&
                sourceTokenizer != null &&
                targetIdToToken != null
    }

    // ============================================================
    // INPUT/OUTPUT INFORMATION
    // ============================================================

    fun getInputNames(): List<String> {

        return encoderSession
            ?.inputNames
            ?.toList()
            ?: emptyList()
    }

    fun getOutputNames(): List<String> {

        return decoderSession
            ?.outputNames
            ?.toList()
            ?: emptyList()
    }

    // ============================================================
    // TRANSLATE
    // ============================================================

    @Synchronized
    fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): String {

        check(
            isLoaded()
        ) {
            "IndicTrans2 engine is not loaded."
        }

        require(
            text.trim().isNotEmpty()
        ) {
            "Input text is empty."
        }

        val encoder =
            encoderSession
                ?: error(
                    "Encoder session is not loaded."
                )

        val decoder =
            decoderSession
                ?: error(
                    "Decoder session is not loaded."
                )

        val decoderWithPast =
            decoderWithPastSession
                ?: error(
                    "Decoder-with-past session is not loaded."
                )

        // --------------------------------------------------------
        // SOURCE IDS
        // --------------------------------------------------------

        val sourceIds =
            buildSourceIds(
                text =
                    text,
                sourceLanguage =
                    sourceLanguage,
                targetLanguage =
                    targetLanguage
            )

        val sourceAttentionMask =
            LongArray(
                sourceIds.size
            ) {
                1L
            }

        Log.d(
            TAG,
            "SOURCE IDS = ${
                sourceIds.joinToString(",")
            }"
        )

        // --------------------------------------------------------
        // ENCODER TENSORS
        // --------------------------------------------------------

        val inputIdsTensor =
            OnnxTensor.createTensor(
                ortEnvironment,
                arrayOf(sourceIds)
            )

        val attentionMaskTensor =
            OnnxTensor.createTensor(
                ortEnvironment,
                arrayOf(
                    sourceAttentionMask
                )
            )

        var encoderResult:
                OrtSession.Result? = null

        var previousDecoderResult:
                OrtSession.Result? = null

        try {

            // ----------------------------------------------------
            // ENCODER
            // ----------------------------------------------------

            encoderResult =
                encoder.run(
                    mapOf(
                        "input_ids" to
                                inputIdsTensor,

                        "attention_mask" to
                                attentionMaskTensor
                    )
                )

            val encoderHiddenStates =
                requireTensorLike(
                    encoderResult[0],
                    "Encoder last_hidden_state"
                )

            Log.d(
                TAG,
                "ENCODER COMPLETE"
            )

            // ----------------------------------------------------
            // DECODER LOOP
            // ----------------------------------------------------

            val generatedIds =
                ArrayList<Long>(
                    MAX_NEW_TOKENS + 1
                )

            generatedIds.add(
                DECODER_START_ID
            )

            var nextToken =
                DECODER_START_ID

            for (
            step in 0 until MAX_NEW_TOKENS
            ) {

                val decoderInputIds =
                    longArrayOf(
                        nextToken
                    )

                val decoderInputTensor =
                    OnnxTensor.createTensor(
                        ortEnvironment,
                        arrayOf(
                            decoderInputIds
                        )
                    )

                var currentDecoderResult:
                        OrtSession.Result? = null

                var transferredOwnership =
                    false

                try {

                    // ------------------------------------------------
                    // FIRST STEP
                    // ------------------------------------------------

                    if (
                        step == 0
                    ) {

                        currentDecoderResult =
                            decoder.run(
                                mapOf(
                                    "input_ids" to
                                            decoderInputTensor,

                                    "encoder_hidden_states" to
                                            encoderHiddenStates,

                                    "encoder_attention_mask" to
                                            attentionMaskTensor
                                )
                            )

                    } else {

                        // --------------------------------------------
                        // SUBSEQUENT STEPS
                        // --------------------------------------------

                        val pastInputs =
                            buildPastInputs(
                                previousDecoderResult
                                    ?: error(
                                        "Missing previous decoder KV cache."
                                    )
                            )

                        val decoderPastInputs =
                            HashMap<String, OnnxTensorLike>()

                        decoderPastInputs[
                            "input_ids"
                        ] =
                            decoderInputTensor

                        decoderPastInputs[
                            "encoder_attention_mask"
                        ] =
                            attentionMaskTensor

                        decoderPastInputs.putAll(
                            pastInputs
                        )

                        currentDecoderResult =
                            decoderWithPast.run(
                                decoderPastInputs
                            )
                    }

                    // ------------------------------------------------
                    // READ LOGITS
                    // ------------------------------------------------

                    @Suppress(
                        "UNCHECKED_CAST"
                    )
                    val logits =
                        currentDecoderResult[0]
                            .value as Array<Array<FloatArray>>

                    val lastLogits =
                        logits[0][
                            logits[0].lastIndex
                        ]

                    // ------------------------------------------------
                    // ARGMAX
                    // ------------------------------------------------

                    var bestId =
                        0

                    var bestScore =
                        Float.NEGATIVE_INFINITY

                    val vocabularyLimit =
                        minOf(
                            lastLogits.size,
                            TARGET_DICT_SIZE
                        )

                    for (
                    tokenId in 0 until vocabularyLimit
                    ) {

                        val score =
                            lastLogits[
                                tokenId
                            ]

                        if (
                            score > bestScore
                        ) {

                            bestScore =
                                score

                            bestId =
                                tokenId
                        }
                    }

                    nextToken =
                        bestId.toLong()

                    generatedIds.add(
                        nextToken
                    )

                    val tokenText =
                        targetIdToToken
                            ?.getOrNull(
                                bestId
                            )

                    Log.d(
                        TAG,
                        "step=$step token=$bestId score=$bestScore tokenText=$tokenText"
                    )

                    // ------------------------------------------------
                    // KEEP CURRENT RESULT FOR NEXT STEP
                    // ------------------------------------------------

                    previousDecoderResult
                        ?.close()

                    previousDecoderResult =
                        currentDecoderResult

                    currentDecoderResult =
                        null

                    transferredOwnership =
                        true

                    // ------------------------------------------------
                    // EOS
                    // ------------------------------------------------

                    if (
                        nextToken == EOS_ID
                    ) {
                        break
                    }

                } finally {

                    decoderInputTensor.close()

                    if (
                        !transferredOwnership
                    ) {
                        currentDecoderResult
                            ?.close()
                    }
                }
            }

            // --------------------------------------------------------
            // TARGET IDS
            // --------------------------------------------------------

            Log.d(
                TAG,
                "TARGET IDS = ${
                    generatedIds.joinToString(",")
                }"
            )

            // --------------------------------------------------------
            // TARGET TEXT
            // --------------------------------------------------------

            val translated =
                decodeTargetIds(
                    generatedIds =
                        generatedIds,
                    targetLanguage =
                        targetLanguage
                )

            Log.d(
                TAG,
                "FINAL TRANSLATION = $translated"
            )

            return translated

        } finally {

            previousDecoderResult
                ?.close()

            encoderResult
                ?.close()

            inputIdsTensor.close()
            attentionMaskTensor.close()
        }
    }

    // ============================================================
    // CLOSE
    // ============================================================

    fun close() {

        Log.d(
            TAG,
            "Closing IndicTrans2 engine..."
        )

        try {

            sourceTokenizer
                ?.tearDown()

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "Error closing source tokenizer",
                e
            )
        }

        try {

            encoderSession
                ?.close()

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "Error closing encoder session",
                e
            )
        }

        try {

            decoderSession
                ?.close()

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "Error closing decoder session",
                e
            )
        }

        try {

            decoderWithPastSession
                ?.close()

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "Error closing decoder-with-past session",
                e
            )
        }

        sourceTokenizer =
            null

        encoderSession =
            null

        decoderSession =
            null

        decoderWithPastSession =
            null

        targetIdToToken =
            null

        decoderLayerCount =
            0

        Log.d(
            TAG,
            "IndicTrans2 engine closed."
        )
    }
}