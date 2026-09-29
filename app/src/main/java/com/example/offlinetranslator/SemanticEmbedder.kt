package com.example.offlinetranslator

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer
import kotlin.math.sqrt

class SemanticEmbedder(
    context: Context
) {

    private val environment = OrtEnvironment.getEnvironment()

    private val session: OrtSession

    init {
        val modelBytes =
            context.assets.open("model_int8.onnx").use {
                it.readBytes()
            }

        session = environment.createSession(modelBytes)
    }

    fun embed(
        inputIds: IntArray,
        attentionMask: IntArray
    ): FloatArray {

        require(inputIds.size == attentionMask.size) {
            "input_ids and attention_mask must have the same length"
        }

        val sequenceLength = inputIds.size

        val inputIdsLong =
            LongArray(sequenceLength) {
                inputIds[it].toLong()
            }

        val attentionMaskLong =
            LongArray(sequenceLength) {
                attentionMask[it].toLong()
            }

        val tokenTypeIdsLong =
            LongArray(sequenceLength) {
                0L
            }

        val shape =
            longArrayOf(
                1L,
                sequenceLength.toLong()
            )

        val inputIdsTensor =
            OnnxTensor.createTensor(
                environment,
                LongBuffer.wrap(inputIdsLong),
                shape
            )

        val attentionMaskTensor =
            OnnxTensor.createTensor(
                environment,
                LongBuffer.wrap(attentionMaskLong),
                shape
            )

        val tokenTypeIdsTensor =
            OnnxTensor.createTensor(
                environment,
                LongBuffer.wrap(tokenTypeIdsLong),
                shape
            )

        try {

            val inputs =
                mapOf(
                    "input_ids" to inputIdsTensor,
                    "attention_mask" to attentionMaskTensor,
                    "token_type_ids" to tokenTypeIdsTensor
                )

            val results =
                session.run(inputs)

            try {

                val output =
                    results[0].value as Array<*>

                val batch =
                    output[0] as Array<*>

                val embeddingSize = 384

                val embedding =
                    FloatArray(embeddingSize)

                var tokenCount = 0

                for (i in 0 until sequenceLength) {

                    if (attentionMask[i] == 1) {

                        val tokenEmbedding =
                            batch[i] as FloatArray

                        for (j in 0 until embeddingSize) {
                            embedding[j] += tokenEmbedding[j]
                        }

                        tokenCount++
                    }
                }

                if (tokenCount == 0) {
                    throw IllegalArgumentException(
                        "Attention mask contains no valid tokens"
                    )
                }

                // Mean pooling
                for (j in 0 until embeddingSize) {
                    embedding[j] /= tokenCount.toFloat()
                }

                // L2 normalization
                var sumSquares = 0.0

                for (value in embedding) {
                    sumSquares += value.toDouble() * value.toDouble()
                }

                val norm =
                    sqrt(sumSquares).toFloat()

                if (norm == 0f) {
                    throw IllegalStateException(
                        "Embedding norm is zero"
                    )
                }

                for (j in embedding.indices) {
                    embedding[j] /= norm
                }

                return embedding

            } finally {
                results.close()
            }

        } finally {
            inputIdsTensor.close()
            attentionMaskTensor.close()
            tokenTypeIdsTensor.close()
        }
    }

    fun close() {
        session.close()
    }
}