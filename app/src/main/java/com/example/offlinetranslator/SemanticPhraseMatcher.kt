package com.example.offlinetranslator

import android.content.Context
import android.util.Log

/**
 * Semantic phrase matcher for Hindi <-> Santali.
 *
 * Pipeline:
 *
 * User text
 *     ↓
 * HFTA HuggingFace tokenizer
 *     ↓
 * multilingual-e5-small INT8 ONNX
 *     ↓
 * 384-dimensional embedding
 *     ↓
 * cosine similarity
 *     ↓
 * phrase bank
 *     ↓
 * VERIFIED / AMBIGUOUS / NO MATCH
 */
class SemanticPhraseMatcher(
    context: Context,
    private val phraseBank: List<Phrase>
) {

    companion object {
        private const val TAG = "SemanticMatcher"

        /*
         * Temporary thresholds.
         * We will tune these using actual phrase-bank tests later.
         */
        const val SIMILARITY_THRESHOLD = 0.90f
        const val MARGIN_THRESHOLD = 0.03f

        private const val EMBEDDING_SIZE = 384
        private const val MAX_SEQUENCE_LENGTH = 512
    }

    enum class Language {
        HINDI,
        SANTALI
    }

    enum class MatchStatus {
        VERIFIED,
        AMBIGUOUS,
        NO_MATCH
    }

    data class MatchResult(
        val matchedPhrase: Phrase?,
        val score: Float,
        val secondBestScore: Float,
        val margin: Float,
        val status: MatchStatus
    )

    private data class CachedPhrase(
        val phrase: Phrase,
        val language: Language,
        val embedding: FloatArray
    )

    private val tokenizer: HFTokenizer
    private val embedder: SemanticEmbedder

    private val hindiEmbeddings = mutableListOf<CachedPhrase>()
    private val santaliEmbeddings = mutableListOf<CachedPhrase>()

    init {
        Log.d(TAG, "Initializing semantic phrase matcher...")

        /*
         * Load the same tokenizer.json that was verified
         * against the Python HuggingFace tokenizer.
         */
        val tokenizerBytes = context.assets
            .open("tokenizer.json")
            .use { it.readBytes() }

        tokenizer = HFTokenizer(tokenizerBytes)

        /*
         * Loads:
         *
         * assets/model_int8.onnx
         */
        embedder = SemanticEmbedder(context)

        /*
         * Calculate phrase-bank embeddings once.
         */
        buildPhraseEmbeddings()

        Log.d(
            TAG,
            "Semantic matcher ready. " +
                    "Hindi=${hindiEmbeddings.size}, " +
                    "Santali=${santaliEmbeddings.size}"
        )
    }

    /**
     * Precomputes embeddings for every phrase in the phrase bank.
     *
     * Hindi:
     *     passage: Hindi phrase
     *
     * Santali:
     *     passage: Santali phrase
     */
    private fun buildPhraseEmbeddings() {

        val startTime = System.currentTimeMillis()

        for ((index, phrase) in phraseBank.withIndex()) {

            // -----------------------------------------
            // Hindi phrase embedding
            // -----------------------------------------

            val hindiText = "passage: ${phrase.hindi}"

            val hindiEmbedding = createEmbedding(hindiText)

            hindiEmbeddings.add(
                CachedPhrase(
                    phrase = phrase,
                    language = Language.HINDI,
                    embedding = hindiEmbedding
                )
            )

            // -----------------------------------------
            // Santali phrase embedding
            // -----------------------------------------

            val santaliText = "passage: ${phrase.santali}"

            val santaliEmbedding = createEmbedding(santaliText)

            santaliEmbeddings.add(
                CachedPhrase(
                    phrase = phrase,
                    language = Language.SANTALI,
                    embedding = santaliEmbedding
                )
            )

            /*
             * Progress logging.
             */
            if ((index + 1) % 10 == 0 || index == phraseBank.lastIndex) {
                Log.d(
                    TAG,
                    "Embedded ${index + 1}/${phraseBank.size} phrases"
                )
            }
        }

        val elapsed = System.currentTimeMillis() - startTime

        Log.d(
            TAG,
            "Phrase embedding cache built in ${elapsed} ms"
        )
    }

    /**
     * Creates one normalized 384-dimensional embedding.
     */
    private fun createEmbedding(text: String): FloatArray {

        var tokenIds = tokenizer.encode(text)

        /*
         * Safety limit.
         */
        if (tokenIds.size > MAX_SEQUENCE_LENGTH) {
            tokenIds = tokenIds.copyOf(MAX_SEQUENCE_LENGTH)
        }

        /*
         * Every token is a real token because we are not padding.
         */
        val attentionMask = IntArray(tokenIds.size) { 1 }

        val embedding = embedder.embed(
            inputIds = tokenIds,
            attentionMask = attentionMask
        )

        require(embedding.size == EMBEDDING_SIZE) {
            "Expected $EMBEDDING_SIZE-dimensional embedding, " +
                    "but received ${embedding.size}"
        }

        return embedding
    }

    /**
     * Match Hindi input against the Hindi phrase bank.
     */
    fun matchHindi(text: String): MatchResult {
        return match(
            text = text,
            language = Language.HINDI
        )
    }

    /**
     * Match Santali input against the Santali phrase bank.
     */
    fun matchSantali(text: String): MatchResult {
        return match(
            text = text,
            language = Language.SANTALI
        )
    }

    /**
     * Main semantic matching function.
     */
    private fun match(
        text: String,
        language: Language
    ): MatchResult {

        val cleanedText = normalizeText(text)

        /*
         * Empty input.
         */
        if (cleanedText.isEmpty()) {
            return MatchResult(
                matchedPhrase = null,
                score = 0f,
                secondBestScore = 0f,
                margin = 0f,
                status = MatchStatus.NO_MATCH
            )
        }

        /*
         * ---------------------------------------------
         * STEP 1: Exact match
         * ---------------------------------------------
         *
         * If the phrase is already present exactly,
         * accept it immediately.
         */
        val exactPhrase = findExactMatch(
            cleanedText,
            language
        )

        if (exactPhrase != null) {

            Log.d(
                TAG,
                "EXACT MATCH: ${exactPhrase.id}"
            )

            return MatchResult(
                matchedPhrase = exactPhrase,
                score = 1.0f,
                secondBestScore = 0f,
                margin = 1.0f,
                status = MatchStatus.VERIFIED
            )
        }

        /*
         * ---------------------------------------------
         * STEP 2: Create query embedding
         * ---------------------------------------------
         *
         * E5 query format:
         *
         *     query: user text
         */
        val queryEmbedding = createEmbedding(
            "query: $cleanedText"
        )

        /*
         * Select the appropriate cached phrase bank.
         */
        val candidates = when (language) {
            Language.HINDI -> hindiEmbeddings
            Language.SANTALI -> santaliEmbeddings
        }

        if (candidates.isEmpty()) {
            return MatchResult(
                matchedPhrase = null,
                score = 0f,
                secondBestScore = 0f,
                margin = 0f,
                status = MatchStatus.NO_MATCH
            )
        }

        /*
         * ---------------------------------------------
         * STEP 3: Calculate similarity
         * ---------------------------------------------
         *
         * SemanticEmbedder already L2-normalizes
         * the embeddings.
         *
         * Therefore:
         *
         * cosine similarity = dot product
         */
        val scoredCandidates = candidates
            .map { candidate ->
                val score = dotProduct(
                    queryEmbedding,
                    candidate.embedding
                )

                candidate to score
            }
            .sortedByDescending { pair ->
                pair.second
            }

        /*
         * IMPORTANT:
         *
         * scoredCandidates[0] is:
         *
         * Pair<CachedPhrase, Float>
         *
         * Therefore:
         *
         * best.first  = CachedPhrase
         * best.second = similarity score
         */
        val best = scoredCandidates[0]

        val secondBestScore =
            if (scoredCandidates.size > 1) {
                scoredCandidates[1].second
            } else {
                0f
            }

        val bestScore = best.second

        val margin = bestScore - secondBestScore

        /*
         * ---------------------------------------------
         * STEP 4: Decision
         * ---------------------------------------------
         */
        val status = when {

            bestScore < SIMILARITY_THRESHOLD -> {
                MatchStatus.NO_MATCH
            }

            margin < MARGIN_THRESHOLD -> {
                MatchStatus.AMBIGUOUS
            }

            else -> {
                MatchStatus.VERIFIED
            }
        }

        val matchedPhrase = best.first.phrase

        Log.d(
            TAG,
            "Semantic match | " +
                    "language=$language | " +
                    "best=${matchedPhrase.id} | " +
                    "score=$bestScore | " +
                    "second=$secondBestScore | " +
                    "margin=$margin | " +
                    "status=$status"
        )

        return MatchResult(
            matchedPhrase = matchedPhrase,
            score = bestScore,
            secondBestScore = secondBestScore,
            margin = margin,
            status = status
        )
    }

    /**
     * Exact normalized phrase match.
     */
    private fun findExactMatch(
        text: String,
        language: Language
    ): Phrase? {

        return when (language) {

            Language.HINDI -> {
                phraseBank.firstOrNull {
                    normalizeText(it.hindi) == text
                }
            }

            Language.SANTALI -> {
                phraseBank.firstOrNull {
                    normalizeText(it.santali) == text
                }
            }
        }
    }

    /**
     * Dot product of two normalized vectors.
     *
     * Because both vectors have approximately unit length,
     * this is equivalent to cosine similarity.
     */
    private fun dotProduct(
        a: FloatArray,
        b: FloatArray
    ): Float {

        require(a.size == b.size) {
            "Embedding dimensions do not match: " +
                    "${a.size} vs ${b.size}"
        }

        var sum = 0.0

        for (i in a.indices) {
            sum += a[i].toDouble() * b[i].toDouble()
        }

        return sum.toFloat()
    }

    /**
     * Basic text normalization for exact matching.
     */
    private fun normalizeText(text: String): String {
        return text
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    /**
     * Release native/model resources.
     */
    fun close() {

        try {
            tokenizer.tearDown()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing tokenizer", e)
        }

        try {
            embedder.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing embedder", e)
        }

        hindiEmbeddings.clear()
        santaliEmbeddings.clear()

        Log.d(TAG, "Semantic matcher closed")
    }
}