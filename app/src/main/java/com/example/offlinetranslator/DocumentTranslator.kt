package com.example.offlinetranslator


// =========================================================
// TRANSLATED TEXT RESULT
// =========================================================

data class TranslatedText(
    val original: String,
    val translated: String?,
    val phraseId: String?,
    val status: String
)


// =========================================================
// DOCUMENT TRANSLATOR
// =========================================================

object DocumentTranslator {

    // -----------------------------------------------------
    // Translate one extracted text
    // -----------------------------------------------------

    fun translateText(
        text: String,
        phraseBank: List<Phrase>,
        sourceLanguage: String
    ): TranslatedText {

        val normalizedInput =
            normalizeText(text)


        // -------------------------------------------------
        // Empty text
        // -------------------------------------------------

        if (normalizedInput.isEmpty()) {

            return TranslatedText(
                original = text,
                translated = null,
                phraseId = null,
                status = "NO MATCH"
            )
        }


        // -------------------------------------------------
        // Find exact phrase
        // -------------------------------------------------

        val match: Phrase? =

            if (sourceLanguage == "Hindi") {

                phraseBank.find {

                    normalizeText(it.hindi) ==
                            normalizedInput
                }

            } else {

                phraseBank.find {

                    normalizeText(it.santali) ==
                            normalizedInput
                }
            }


        // -------------------------------------------------
        // No match
        // -------------------------------------------------

        if (match == null) {

            return TranslatedText(
                original = text,
                translated = null,
                phraseId = null,
                status = "NO MATCH"
            )
        }


        // -------------------------------------------------
        // Translate to opposite language
        // -------------------------------------------------

        val translatedText =

            if (sourceLanguage == "Hindi") {

                match.santali

            } else {

                match.hindi
            }


        // -------------------------------------------------
        // Verification status
        // -------------------------------------------------

        val matchStatus =

            if (match.verified) {

                "VERIFIED"

            } else {

                "AMBIGUOUS"
            }


        return TranslatedText(
            original = text,
            translated = translatedText,
            phraseId = match.id,
            status = matchStatus
        )
    }


    // -----------------------------------------------------
    // Translate complete extracted document
    // -----------------------------------------------------

    fun translateDocument(
        document: ExtractedDocument,
        phraseBank: List<Phrase>,
        sourceLanguage: String
    ): List<TranslatedText> {

        return document.texts.map { text ->

            translateText(
                text = text,
                phraseBank = phraseBank,
                sourceLanguage = sourceLanguage
            )
        }
    }
}