package com.example.offlinetranslator

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import org.json.JSONArray

data class AudioPair(
    val id: String,
    val phraseId: String,
    val hindiFile: String,
    val santaliFile: String
)

object AudioPairMatcher {

    private var pairs: List<AudioPair>? = null

    /**
     * Loads the audio pair manifest from assets.
     *
     * Expected location:
     * assets/audio_pairs/audio_pairs_manifest.json
     *
     * Manifest format:
     *
     * [
     *   {
     *     "id": "AUDIO_01",
     *     "phraseId": "P121",
     *     "hindiFile": "hindi_1.wav",
     *     "santaliFile": "santali_1.wav"
     *   }
     * ]
     */
    fun loadPairs(context: Context): List<AudioPair> {

        pairs?.let {
            return it
        }

        val jsonText =
            context.assets
                .open("audio_pairs/audio_pairs_manifest.json")
                .bufferedReader()
                .use {
                    it.readText()
                }

        val jsonArray =
            JSONArray(jsonText)

        val loaded =
            mutableListOf<AudioPair>()

        for (i in 0 until jsonArray.length()) {

            val obj =
                jsonArray.getJSONObject(i)

            loaded.add(
                AudioPair(
                    id = obj.getString("id"),
                    phraseId = obj.getString("phraseId"),
                    hindiFile = obj.getString("hindiFile"),
                    santaliFile = obj.getString("santaliFile")
                )
            )
        }

        pairs = loaded

        return loaded
    }


    /**
     * Finds an audio pair using the phrase-bank ID.
     *
     * Example:
     *
     * P121
     *   ↓
     * AUDIO_01
     *   ↓
     * santali_1.wav
     *
     * Returns null if the phrase does not have
     * an audio pair configured in the manifest.
     */
    fun findByPhraseId(
        context: Context,
        phraseId: String
    ): AudioPair? {

        val allPairs =
            loadPairs(context)

        return allPairs.firstOrNull {
            it.phraseId.equals(
                phraseId,
                ignoreCase = true
            )
        }
    }


    /**
     * Matches the selected audio file by filename.
     *
     * Hindi input:
     * hindi_1.wav -> santali_1.wav
     *
     * Santali input:
     * santali_1.wav -> hindi_1.wav
     */
    fun match(
        context: Context,
        pickedUri: Uri
    ): MatchResult? {

        val allPairs =
            loadPairs(context)

        /*
         * Get the actual filename from the selected URI.
         *
         * Example:
         *
         * content://.../hindi_1.wav
         *              ↓
         * hindi_1.wav
         */
        val selectedFileName =
            getFileName(
                context,
                pickedUri
            )
                ?: return null

        for (pair in allPairs) {

            // Hindi audio selected
            if (
                selectedFileName.equals(
                    pair.hindiFile,
                    ignoreCase = true
                )
            ) {

                return MatchResult(
                    pairId = pair.id,
                    matchedSide = "Hindi",
                    playbackAsset =
                        "audio_pairs/${pair.santaliFile}"
                )
            }

            // Santali audio selected
            if (
                selectedFileName.equals(
                    pair.santaliFile,
                    ignoreCase = true
                )
            ) {

                return MatchResult(
                    pairId = pair.id,
                    matchedSide = "Santali",
                    playbackAsset =
                        "audio_pairs/${pair.hindiFile}"
                )
            }
        }

        return null
    }


    /**
     * Gets the filename from the URI selected
     * through the Android file picker.
     */
    private fun getFileName(
        context: Context,
        uri: Uri
    ): String? {

        var fileName: String? = null

        context.contentResolver
            .query(
                uri,
                arrayOf(
                    android.provider.OpenableColumns.DISPLAY_NAME
                ),
                null,
                null,
                null
            )
            ?.use { cursor ->

                if (cursor.moveToFirst()) {

                    val nameIndex =
                        cursor.getColumnIndex(
                            android.provider.OpenableColumns.DISPLAY_NAME
                        )

                    if (nameIndex >= 0) {

                        fileName =
                            cursor.getString(
                                nameIndex
                            )
                    }
                }
            }

        return fileName
    }


    /**
     * Plays an audio file bundled inside
     * the application's assets.
     *
     * Example:
     *
     * audio_pairs/santali_1.wav
     */
    fun playAsset(
        context: Context,
        assetPath: String
    ) {

        val afd =
            context.assets.openFd(
                assetPath
            )

        val player =
            MediaPlayer()

        player.setDataSource(
            afd.fileDescriptor,
            afd.startOffset,
            afd.length
        )

        afd.close()

        player.prepare()

        player.start()

        player.setOnCompletionListener {
            it.release()
        }
    }
}


data class MatchResult(
    val pairId: String,
    val matchedSide: String,
    val playbackAsset: String
)