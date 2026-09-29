package com.example.offlinetranslator

import android.content.Context
import android.net.Uri

import org.w3c.dom.Document
import org.w3c.dom.Element

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult


// =========================================================
// NAMESPACES
// =========================================================

private const val WORD_NS =
    "http://schemas.openxmlformats.org/wordprocessingml/2006/main"

private const val DRAWING_NS =
    "http://schemas.openxmlformats.org/drawingml/2006/main"


// =========================================================
// RESULT TYPES
// =========================================================

data class RebuildResult(
    val outputFile: File,
    val totalParagraphs: Int,
    val translatedCount: Int,
    val unmatchedCount: Int
)

private class RebuildStats {
    var totalParagraphs = 0
    var translatedCount = 0
    var unmatchedCount = 0
}


// =========================================================
// DOCUMENT REBUILDER
// =========================================================
//
// Strategy: never regenerate the docx/pptx from scratch.
// Instead, stream every zip entry from the original file
// straight through to a new zip file unchanged — EXCEPT the
// text-bearing XML parts (word/document.xml for docx,
// ppt/slides/slideN.xml for pptx). For those, we parse the
// XML as a DOM, walk each paragraph, and if the paragraph's
// full text matches a phrase in the phrase bank, we replace
// it with the translation. Every other part of the archive
// (media/images, layouts, styles, relationships, theme,
// etc.) is copied byte-for-byte, so images and structure are
// left completely untouched.
//
object DocumentRebuilder {

    fun rebuild(
        context: Context,
        uri: Uri,
        extension: String,
        phraseBank: List<Phrase>,
        sourceLanguage: String = "Hindi",
        outputFileName: String = "rebuilt_output"
    ): RebuildResult {

        val nsUri: String
        val isTextEntry: (String) -> Boolean

        when (extension.lowercase()) {

            "docx" -> {
                nsUri = WORD_NS
                isTextEntry = { name ->
                    name == "word/document.xml"
                }
            }

            "pptx" -> {
                nsUri = DRAWING_NS
                isTextEntry = { name ->
                    name.startsWith("ppt/slides/slide") &&
                            name.endsWith(".xml")
                }
            }

            else -> {
                throw IllegalArgumentException(
                    "Only DOCX and PPTX files are supported."
                )
            }
        }

        val stats = RebuildStats()

        val outputDir =
            File(context.cacheDir, "rebuilt_documents")

        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val outputFile =
            File(outputDir, "$outputFileName.$extension")

        context.contentResolver
            .openInputStream(uri)
            .use { input ->

                requireNotNull(input) {
                    "Could not open the selected document."
                }

                ZipInputStream(input).use { zipIn ->

                    ZipOutputStream(
                        FileOutputStream(outputFile)
                    ).use { zipOut ->

                        while (true) {

                            val entry =
                                zipIn.nextEntry
                                    ?: break

                            val bytes =
                                zipIn.readBytes()

                            if (
                                !entry.isDirectory &&
                                isTextEntry(entry.name)
                            ) {

                                val doc =
                                    parseXml(bytes)

                                translateParagraphs(
                                    doc = doc,
                                    nsUri = nsUri,
                                    phraseBank = phraseBank,
                                    sourceLanguage = sourceLanguage,
                                    stats = stats
                                )

                                val newBytes =
                                    serializeXml(doc)

                                zipOut.putNextEntry(
                                    ZipEntry(entry.name)
                                )

                                zipOut.write(newBytes)

                                zipOut.closeEntry()

                            } else {

                                // Copy every other part
                                // (images, layouts, styles,
                                // rels, theme, etc.) untouched.

                                zipOut.putNextEntry(
                                    ZipEntry(entry.name)
                                )

                                zipOut.write(bytes)

                                zipOut.closeEntry()
                            }

                            zipIn.closeEntry()
                        }
                    }
                }
            }

        return RebuildResult(
            outputFile = outputFile,
            totalParagraphs = stats.totalParagraphs,
            translatedCount = stats.translatedCount,
            unmatchedCount = stats.unmatchedCount
        )
    }


    // -----------------------------------------------------
    // PARAGRAPH TRANSLATION (shared for docx <w:p>/<w:t>
    // and pptx <a:p>/<a:t> — both use local names "p"/"t",
    // only the namespace differs)
    // -----------------------------------------------------

    private fun translateParagraphs(
        doc: Document,
        nsUri: String,
        phraseBank: List<Phrase>,
        sourceLanguage: String,
        stats: RebuildStats
    ) {

        val paragraphs =
            doc.getElementsByTagNameNS(nsUri, "p")

        for (i in 0 until paragraphs.length) {

            val paragraph =
                paragraphs.item(i) as Element

            val textNodes =
                paragraph.getElementsByTagNameNS(nsUri, "t")

            if (textNodes.length == 0) {
                continue
            }

            val fullText =
                buildString {
                    for (j in 0 until textNodes.length) {
                        append(textNodes.item(j).textContent)
                    }
                }

            val normalized =
                normalizeText(fullText)

            if (normalized.isEmpty()) {
                continue
            }

            stats.totalParagraphs++

            val match: Phrase? =

                if (sourceLanguage == "Hindi") {

                    phraseBank.find {
                        normalizeText(it.hindi) == normalized
                    }

                } else {

                    phraseBank.find {
                        normalizeText(it.santali) == normalized
                    }
                }

            if (match == null) {
                stats.unmatchedCount++
                continue
            }

            val translated =
                if (sourceLanguage == "Hindi") {
                    match.santali
                } else {
                    match.hindi
                }

            // Put the full translation into the first text
            // run (keeps that run's formatting/font) and
            // blank out any remaining runs in the paragraph
            // so nothing is duplicated.

            (textNodes.item(0) as Element).textContent =
                translated

            for (j in 1 until textNodes.length) {
                (textNodes.item(j) as Element).textContent = ""
            }

            stats.translatedCount++
        }
    }


    // -----------------------------------------------------
    // XML HELPERS
    // -----------------------------------------------------

    private fun parseXml(bytes: ByteArray): Document {

        val factory =
            DocumentBuilderFactory.newInstance()

        factory.isNamespaceAware = true

        val builder =
            factory.newDocumentBuilder()

        return builder.parse(
            ByteArrayInputStream(bytes)
        )
    }

    private fun serializeXml(doc: Document): ByteArray {

        val transformer =
            TransformerFactory.newInstance()
                .newTransformer()

        transformer.setOutputProperty(
            OutputKeys.ENCODING,
            "UTF-8"
        )

        transformer.setOutputProperty(
            OutputKeys.OMIT_XML_DECLARATION,
            "no"
        )

        val output =
            ByteArrayOutputStream()

        transformer.transform(
            DOMSource(doc),
            StreamResult(output)
        )

        return output.toByteArray()
    }
}


// =========================================================
// EXTENSION HELPER
// =========================================================

fun fileExtensionOf(fileName: String): String {

    return fileName
        .substringAfterLast(".", "")
        .lowercase()
}