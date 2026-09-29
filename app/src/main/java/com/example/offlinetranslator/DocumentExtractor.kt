package com.example.offlinetranslator

import android.content.Context
import android.net.Uri

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

import java.io.File
import java.io.FileOutputStream

import java.util.zip.ZipInputStream


// =========================================================
// EXTRACTED IMAGE
// =========================================================

data class ExtractedImage(
    val name: String,
    val file: File
)


// =========================================================
// EXTRACTED DOCUMENT
// =========================================================

data class ExtractedDocument(
    val fileName: String,
    val type: String,
    val texts: List<String>,
    val images: List<ExtractedImage>
)


// =========================================================
// DOCUMENT EXTRACTOR
// =========================================================

object DocumentExtractor {


    // -----------------------------------------------------
    // MAIN ENTRY POINT
    // -----------------------------------------------------

    fun extract(
        context: Context,
        uri: Uri,
        fileName: String
    ): ExtractedDocument {

        val extension =
            fileName
                .substringAfterLast(
                    ".",
                    ""
                )
                .lowercase()


        return when (extension) {

            "docx" -> {

                extractDocx(
                    context,
                    uri,
                    fileName
                )
            }

            "pptx" -> {

                extractPptx(
                    context,
                    uri,
                    fileName
                )
            }

            else -> {

                throw IllegalArgumentException(
                    "Only DOCX and PPTX files are supported."
                )
            }
        }
    }


    // =====================================================
    // DOCX EXTRACTION
    // =====================================================

    private fun extractDocx(
        context: Context,
        uri: Uri,
        fileName: String
    ): ExtractedDocument {

        val texts =
            mutableListOf<String>()

        val images =
            mutableListOf<ExtractedImage>()


        context.contentResolver
            .openInputStream(uri)
            .use { input ->

                requireNotNull(input) {
                    "Could not open the selected DOCX file."
                }


                ZipInputStream(input)
                    .use { zip ->


                        while (true) {

                            val entry =
                                zip.nextEntry
                                    ?: break


                            if (!entry.isDirectory) {


                                // ---------------------------------
                                // MAIN DOCUMENT XML
                                // ---------------------------------

                                if (
                                    entry.name ==
                                    "word/document.xml"
                                ) {

                                    val xmlBytes =
                                        zip.readBytes()

                                    extractDocxText(
                                        xmlBytes,
                                        texts
                                    )
                                }


                                // ---------------------------------
                                // EMBEDDED IMAGES
                                // ---------------------------------

                                else if (
                                    entry.name.startsWith(
                                        "word/media/"
                                    )
                                ) {

                                    val imageName =
                                        entry.name
                                            .substringAfterLast(
                                                "/"
                                            )

                                    val imageFile =
                                        saveImage(
                                            context,
                                            zip,
                                            imageName
                                        )

                                    images.add(
                                        ExtractedImage(
                                            name = imageName,
                                            file = imageFile
                                        )
                                    )
                                }
                            }

                            zip.closeEntry()
                        }
                    }
            }


        return ExtractedDocument(
            fileName = fileName,
            type = "DOCX",
            texts = texts,
            images = images
        )
    }


    // -----------------------------------------------------
    // EXTRACT DOCX TEXT
    // -----------------------------------------------------

    private fun extractDocxText(
        xmlBytes: ByteArray,
        texts: MutableList<String>
    ) {

        val factory =
            XmlPullParserFactory
                .newInstance()

        factory.isNamespaceAware = true


        val parser =
            factory.newPullParser()


        parser.setInput(
            xmlBytes.inputStream(),
            "UTF-8"
        )


        val paragraph =
            StringBuilder()


        var eventType =
            parser.eventType


        while (
            eventType !=
            XmlPullParser.END_DOCUMENT
        ) {


            // ---------------------------------------------
            // START TAG
            // ---------------------------------------------

            if (
                eventType ==
                XmlPullParser.START_TAG
            ) {

                if (
                    parser.name == "t"
                ) {

                    val text =
                        parser.nextText()

                    paragraph.append(
                        text
                    )
                }
            }


            // ---------------------------------------------
            // END PARAGRAPH
            // ---------------------------------------------

            else if (
                eventType ==
                XmlPullParser.END_TAG &&
                parser.name == "p"
            ) {

                val result =
                    paragraph
                        .toString()
                        .trim()


                if (
                    result.isNotEmpty()
                ) {

                    texts.add(
                        result
                    )
                }


                paragraph.clear()
            }


            eventType =
                parser.next()
        }
    }


    // =====================================================
    // PPTX EXTRACTION
    // =====================================================

    private fun extractPptx(
        context: Context,
        uri: Uri,
        fileName: String
    ): ExtractedDocument {

        val texts =
            mutableListOf<String>()

        val images =
            mutableListOf<ExtractedImage>()


        context.contentResolver
            .openInputStream(uri)
            .use { input ->

                requireNotNull(input) {
                    "Could not open the selected PPTX file."
                }


                ZipInputStream(input)
                    .use { zip ->


                        while (true) {

                            val entry =
                                zip.nextEntry
                                    ?: break


                            if (!entry.isDirectory) {


                                // ---------------------------------
                                // SLIDE XML
                                // ---------------------------------

                                if (
                                    entry.name.startsWith(
                                        "ppt/slides/slide"
                                    ) &&
                                    entry.name.endsWith(
                                        ".xml"
                                    )
                                ) {

                                    val xmlBytes =
                                        zip.readBytes()

                                    extractPptxText(
                                        xmlBytes,
                                        texts
                                    )
                                }


                                // ---------------------------------
                                // EMBEDDED IMAGES
                                // ---------------------------------

                                else if (
                                    entry.name.startsWith(
                                        "ppt/media/"
                                    )
                                ) {

                                    val imageName =
                                        entry.name
                                            .substringAfterLast(
                                                "/"
                                            )

                                    val imageFile =
                                        saveImage(
                                            context,
                                            zip,
                                            imageName
                                        )

                                    images.add(
                                        ExtractedImage(
                                            name = imageName,
                                            file = imageFile
                                        )
                                    )
                                }
                            }


                            zip.closeEntry()
                        }
                    }
            }


        return ExtractedDocument(
            fileName = fileName,
            type = "PPTX",
            texts = texts,
            images = images
        )
    }


    // -----------------------------------------------------
    // EXTRACT PPTX TEXT
    // -----------------------------------------------------

    private fun extractPptxText(
        xmlBytes: ByteArray,
        texts: MutableList<String>
    ) {

        val factory =
            XmlPullParserFactory
                .newInstance()

        factory.isNamespaceAware = true


        val parser =
            factory.newPullParser()


        parser.setInput(
            xmlBytes.inputStream(),
            "UTF-8"
        )


        val textBuilder =
            StringBuilder()


        var eventType =
            parser.eventType


        while (
            eventType !=
            XmlPullParser.END_DOCUMENT
        ) {


            if (
                eventType ==
                XmlPullParser.START_TAG
            ) {

                if (
                    parser.name == "t"
                ) {

                    val text =
                        parser.nextText()

                    textBuilder.append(
                        text
                    )

                    textBuilder.append(
                        " "
                    )
                }
            }


            eventType =
                parser.next()
        }


        val result =
            textBuilder
                .toString()
                .replace(
                    Regex("\\s+"),
                    " "
                )
                .trim()


        if (
            result.isNotEmpty()
        ) {

            texts.add(
                result
            )
        }
    }


    // =====================================================
    // SAVE IMAGE TO CACHE
    // =====================================================

    private fun saveImage(
        context: Context,
        zip: ZipInputStream,
        imageName: String
    ): File {


        // -----------------------------------------------
        // CREATE IMAGE DIRECTORY
        // -----------------------------------------------

        val imageDirectory =
            File(
                context.cacheDir,
                "extracted_images"
            )


        if (
            !imageDirectory.exists()
        ) {

            imageDirectory.mkdirs()
        }


        // -----------------------------------------------
        // OUTPUT FILE
        // -----------------------------------------------

        val outputFile =
            File(
                imageDirectory,
                imageName
            )


        // -----------------------------------------------
        // COPY ZIP ENTRY → FILE
        // -----------------------------------------------

        FileOutputStream(
            outputFile
        ).use { output ->


            val buffer =
                ByteArray(8192)


            while (true) {

                val count =
                    zip.read(buffer)


                if (count <= 0) {
                    break
                }


                output.write(
                    buffer,
                    0,
                    count
                )
            }
        }


        return outputFile
    }
}