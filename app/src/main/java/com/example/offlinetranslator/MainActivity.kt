package com.example.offlinetranslator

import android.annotation.SuppressLint
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll

import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.core.content.ContextCompat

import org.json.JSONArray

import java.io.FileInputStream


data class Phrase(
    val id: String,
    val hindi: String,
    val santali: String,
    val santaliTranslit: String,
    val verified: Boolean
)


val OlChikiFont = FontFamily(
    Font(
        R.font.noto_sans_ol_chiki_regular,
        FontWeight.Normal
    )
)


class MainActivity : ComponentActivity() {

    // ============================================================
    // INDIC TRANS2 ENGINE
    // ============================================================

    private var indicTrans2Engine: IndicTrans2Engine? = null


    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )


        val phraseBank =
            loadPhraseBank()


        // ========================================================
        // INDIC TRANS2 INT8 MODEL LOAD
        // ========================================================

        Thread {

            try {

                Log.d(
                    "IndicTrans2Test",
                    "Starting IndicTrans2 INT8 model load..."
                )


                val engine =
                    IndicTrans2Engine(
                        this@MainActivity
                    )


                indicTrans2Engine =
                    engine


                val startTime =
                    System.currentTimeMillis()


                engine.load()


                val elapsed =
                    System.currentTimeMillis() -
                            startTime


                Log.d(
                    "IndicTrans2Test",
                    "MODEL LOADED SUCCESSFULLY"
                )


                Log.d(
                    "IndicTrans2Test",
                    "Load time: ${elapsed} ms"
                )


                Log.d(
                    "IndicTrans2Test",
                    "Inputs: ${engine.getInputNames()}"
                )


                Log.d(
                    "IndicTrans2Test",
                    "Outputs: ${engine.getOutputNames()}"
                )


            } catch (
                e: Exception
            ) {

                Log.e(
                    "IndicTrans2Test",
                    "MODEL LOAD FAILED",
                    e
                )
            }

        }.start()


        // ========================================================
        // SENTENCEPIECE TEST REMOVED
        // ========================================================
        //
        // We are leaving the IndicTrans2 SentencePiece JNI
        // tokenizer test for the prototype stage.
        //
        // The failing native tokenizer test is therefore no
        // longer started when the app launches.
        //
        // ========================================================


        // ========================================================
        // HUGGING FACE TOKENIZER + ONNX EMBEDDING TEST
        // ========================================================

        try {

            // ----------------------------------------------------
            // STEP 1: Load Hugging Face tokenizer
            // ----------------------------------------------------

            val tokenizerBytes =
                assets
                    .open(
                        "tokenizer.json"
                    )
                    .readBytes()


            val tokenizer =
                HFTokenizer(
                    tokenizerBytes
                )


            // ----------------------------------------------------
            // STEP 2: Tokenize test sentence
            // ----------------------------------------------------

            val testText =
                "query: क्या आपको समझ में आया?"


            val ids =
                tokenizer.encode(
                    testText
                )


            Log.d(
                "HF_TOKENIZER",
                "IDs = ${ids.joinToString(", ")}"
            )


            // ----------------------------------------------------
            // STEP 3: Create attention mask
            // ----------------------------------------------------

            val attentionMask =
                IntArray(
                    ids.size
                ) {
                    1
                }


            // ----------------------------------------------------
            // STEP 4: Load INT8 ONNX embedding model
            // ----------------------------------------------------

            val embedder =
                SemanticEmbedder(
                    this
                )


            // ----------------------------------------------------
            // STEP 5: Generate 384-dimensional embedding
            // ----------------------------------------------------

            val embedding =
                embedder.embed(
                    inputIds =
                        ids,

                    attentionMask =
                        attentionMask
                )


            // ----------------------------------------------------
            // STEP 6: Verify embedding dimension
            // ----------------------------------------------------

            Log.d(
                "ONNX_EMBEDDER",
                "Embedding size = ${embedding.size}"
            )


            // ----------------------------------------------------
            // STEP 7: Verify L2 normalization
            // ----------------------------------------------------

            var normSquared =
                0.0


            for (
            value in embedding
            ) {

                normSquared +=
                    value.toDouble() *
                            value.toDouble()
            }


            val norm =
                kotlin.math.sqrt(
                    normSquared
                )


            Log.d(
                "ONNX_EMBEDDER",
                "Embedding norm = $norm"
            )


            // ----------------------------------------------------
            // STEP 8: Print embedding in chunks
            // ----------------------------------------------------

            for (
            i in embedding.indices step 20
            ) {

                val end =
                    minOf(
                        i + 20,
                        embedding.size
                    )


                Log.d(
                    "ONNX_VECTOR",

                    "$i-${end - 1}: ${
                        embedding
                            .slice(
                                i until end
                            )
                            .joinToString(",")
                    }"
                )
            }


            // ----------------------------------------------------
            // STEP 9: Release ONNX resources
            // ----------------------------------------------------

            embedder.close()


            // ----------------------------------------------------
            // STEP 10: Release tokenizer
            // ----------------------------------------------------

            tokenizer.tearDown()

        } catch (
            e: Exception
        ) {

            Log.e(
                "ONNX_EMBEDDER",
                "Tokenizer / ONNX test failed",
                e
            )
        }


        setContent {

            MaterialTheme {

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFF7F8FC)
                ) {
                    LinguaBridgeShell(
                        phraseBank = phraseBank,
                        onNeuralTranslate = {
                                text, sourceLanguage, onSuccess, onError ->
                            translateWithIndicTrans2(
                                text,
                                sourceLanguage,
                                onSuccess,
                                onError
                            )
                        }
                    )
                }
            }
        }
    }


    override fun onDestroy() {

        VoiceRecognizer.stopLiveRecognition()


        // ========================================================
        // CLOSE INDIC TRANS2 ENGINE
        // ========================================================

        try {

            indicTrans2Engine?.close()

        } catch (
            e: Exception
        ) {

            Log.e(
                "IndicTrans2Test",
                "Error closing IndicTrans2 engine",
                e
            )
        }


        indicTrans2Engine =
            null


        super.onDestroy()
    }


    private fun translateWithIndicTrans2(
        text: String,
        sourceLanguage: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val engine = indicTrans2Engine
        if (engine == null || !engine.isLoaded()) {
            onError("IndicTrans2 model is still loading.")
            return
        }
        Thread {
            try {
                val translated = if (sourceLanguage == "Hindi") {
                    engine.translate(text, "hin_Deva", "sat_Olck")
                } else {
                    engine.translate(text, "sat_Olck", "hin_Deva")
                }
                runOnUiThread {
                    if (translated.isBlank()) onError("IndicTrans2 returned an empty translation.")
                    else onSuccess(translated)
                }
            } catch (e: Exception) {
                Log.e("IndicTrans2Translate", "Neural translation failed", e)
                runOnUiThread { onError(e.message ?: "Neural translation failed.") }
            }
        }.start()
    }


    private fun loadPhraseBank(): List<Phrase> {

        val jsonText =
            assets
                .open(
                    "phrasebank_master.json"
                )
                .bufferedReader()
                .use {
                    it.readText()
                }


        val jsonArray =
            JSONArray(jsonText)


        val phrases =
            mutableListOf<Phrase>()


        for (
        i in 0 until jsonArray.length()
        ) {

            val obj =
                jsonArray.getJSONObject(i)


            phrases.add(

                Phrase(

                    id =
                        obj.getString(
                            "id"
                        ),

                    hindi =
                        obj.getString(
                            "hindi"
                        ),

                    santali =
                        obj.getString(
                            "santali"
                        ),

                    santaliTranslit =
                        obj.getString(
                            "santali_translit"
                        ),

                    verified =
                        obj.getBoolean(
                            "verified"
                        )
                )
            )
        }


        return phrases
    }
}


@SuppressLint("NewApi")
@Composable
fun TranslatorScreen(
    phraseBank: List<Phrase>,
    onNeuralTranslate: (String, String, (String) -> Unit, (String) -> Unit) -> Unit
) {

    val context =
        LocalContext.current


    // ============================================================
    // SEMANTIC PHRASE MATCHER
    // ============================================================

    val semanticMatcher =
        remember(phraseBank) {

            SemanticPhraseMatcher(

                context =
                    context,

                phraseBank =
                    phraseBank
            )
        }


    // ============================================================
    // CLEAN UP SEMANTIC MATCHER
    // ============================================================

    DisposableEffect(
        semanticMatcher
    ) {

        onDispose {

            semanticMatcher.close()
        }
    }


    // ============================================================
    // TEXT TRANSLATOR STATES
    // ============================================================

    var inputLanguage by remember {
        mutableStateOf("Hindi")
    }


    var inputText by remember {
        mutableStateOf("")
    }


    var outputText by remember {
        mutableStateOf("")
    }


    var status by remember {
        mutableStateOf("")
    }


    var matchedId by remember {
        mutableStateOf("")
    }


    var isNeuralTranslating by remember {
        mutableStateOf(false)
    }


    var neuralTranslationInfo by remember {
        mutableStateOf("")
    }


    var expanded by remember {
        mutableStateOf(false)
    }


    // ============================================================
    // AUDIO PAIR STATES
    // ============================================================

    var audioMatchStatus by remember {
        mutableStateOf("")
    }


    var matchedAudioAsset by remember {
        mutableStateOf<String?>(null)
    }


    // ============================================================
    // DOCUMENT STATES
    // ============================================================

    var selectedUri by remember {
        mutableStateOf<Uri?>(null)
    }


    var selectedFileName by remember {
        mutableStateOf<String?>(null)
    }


    var selectedDocument by remember {
        mutableStateOf<ExtractedDocument?>(null)
    }


    var documentError by remember {
        mutableStateOf<String?>(null)
    }


    var translatedTexts by remember {
        mutableStateOf<List<TranslatedText>>(
            emptyList()
        )
    }


    var rebuildResult by remember {
        mutableStateOf<RebuildResult?>(null)
    }


    var rebuildError by remember {
        mutableStateOf<String?>(null)
    }


    var isRebuilding by remember {
        mutableStateOf(false)
    }


    var saveMessage by remember {
        mutableStateOf<String?>(null)
    }


    // ============================================================
    // VOICE TEST STATES
    // ============================================================

    var modelReady by remember {
        mutableStateOf(false)
    }


    var voiceStatus by remember {
        mutableStateOf("")
    }


    var recognizedText by remember {
        mutableStateOf("")
    }


    // ============================================================
    // LIVE VOICE TRANSLATION STATES
    // ============================================================

    var isLiveListening by remember {
        mutableStateOf(false)
    }


    var liveVoiceStatus by remember {
        mutableStateOf("")
    }


    var liveRecognizedText by remember {
        mutableStateOf("")
    }


    var liveTranslatedText by remember {
        mutableStateOf("")
    }


    var liveLatencyText by remember {
        mutableStateOf("")
    }


    // ============================================================
    // AUTOMATIC HINDI MODEL LOADING
    // ============================================================

    LaunchedEffect(Unit) {

        voiceStatus =
            "Loading Hindi model..."


        VoiceRecognizer.ensureModelLoaded(

            context = context,

            onReady = {

                modelReady = true

                voiceStatus =
                    "Hindi model ready ✓"
            },

            onError = { error ->

                modelReady = false

                voiceStatus =
                    "Error loading Hindi model: $error"
            }
        )
    }


    // ============================================================
    // DOCUMENT FUNCTIONS
    // ============================================================

    fun handleDocumentSelected(
        uri: Uri
    ) {

        val fileName =
            getFileName(
                context = context,
                uri = uri
            )


        selectedUri = uri

        selectedFileName =
            fileName


        selectedDocument =
            null


        translatedTexts =
            emptyList()


        documentError =
            null


        rebuildResult =
            null


        rebuildError =
            null


        saveMessage =
            null
    }


    fun extractSelectedDocument() {

        val uri =
            selectedUri


        if (uri == null) {

            documentError =
                "Please select a document first."

            return
        }


        try {

            val fileName =
                selectedFileName
                    ?: getFileName(
                        context = context,
                        uri = uri
                    )


            selectedDocument =
                DocumentExtractor.extract(

                    context =
                        context,

                    uri =
                        uri,

                    fileName =
                        fileName
                )


            translatedTexts =
                emptyList()


            documentError =
                null

        } catch (
            e: Exception
        ) {

            selectedDocument =
                null


            translatedTexts =
                emptyList()


            documentError =
                e.message
                    ?: "Could not extract the document."
        }
    }


    fun translateExtractedDocument() {

        val document =
            selectedDocument


        if (document == null) {

            documentError =
                "Please extract the document first."

            return
        }


        translatedTexts =
            DocumentTranslator.translateDocument(

                document =
                    document,

                phraseBank =
                    phraseBank,

                sourceLanguage =
                    inputLanguage
            )


        documentError =
            null
    }


    fun rebuildSelectedDocument() {

        val uri =
            selectedUri


        val fileName =
            selectedFileName


        if (
            uri == null ||
            fileName == null
        ) {

            rebuildError =
                "Please select a document first."

            return
        }


        val extension =
            fileExtensionOf(
                fileName
            )


        if (
            extension != "docx" &&
            extension != "pptx"
        ) {

            rebuildError =
                "Only DOCX and PPTX files are supported."

            return
        }


        isRebuilding =
            true


        saveMessage =
            null


        try {

            val baseName =
                fileName.substringBeforeLast(
                    "."
                )


            rebuildResult =
                DocumentRebuilder.rebuild(

                    context =
                        context,

                    uri =
                        uri,

                    extension =
                        extension,

                    phraseBank =
                        phraseBank,

                    sourceLanguage =
                        inputLanguage,

                    outputFileName =
                        "${baseName}_santali"
                )


            rebuildError =
                null

        } catch (
            e: Exception
        ) {

            rebuildResult =
                null


            rebuildError =
                e.message
                    ?: "Could not rebuild the document."

        } finally {

            isRebuilding =
                false
        }
    }


    // ============================================================
    // SAVE DOCUMENT
    // ============================================================

    val saveAsLauncher =
        rememberLauncherForActivityResult(

            contract =
                ActivityResultContracts
                    .CreateDocument("*/*")

        ) { targetUri: Uri? ->

            val result =
                rebuildResult


            if (
                targetUri == null ||
                result == null
            ) {

                return@rememberLauncherForActivityResult
            }


            try {

                context.contentResolver
                    .openOutputStream(
                        targetUri
                    )
                    ?.use { output ->

                        FileInputStream(
                            result.outputFile
                        ).use { input ->

                            input.copyTo(
                                output
                            )
                        }
                    }


                saveMessage =
                    "Saved successfully."

            } catch (
                e: Exception
            ) {

                saveMessage =
                    "Could not save the file: ${e.message}"
            }
        }


    // ============================================================
    // AUDIO PAIR PICKER
    // ============================================================

    val audioPairPicker =
        rememberLauncherForActivityResult(

            contract =
                ActivityResultContracts
                    .OpenDocument()

        ) { uri: Uri? ->

            if (uri != null) {

                try {

                    val result =
                        AudioPairMatcher.match(
                            context,
                            uri
                        )


                    if (result == null) {

                        audioMatchStatus =
                            "No match found in the known audio pairs."

                        matchedAudioAsset =
                            null

                    } else {

                        audioMatchStatus =
                            "Matched ${result.pairId} (${result.matchedSide} input)"


                        matchedAudioAsset =
                            result.playbackAsset
                    }

                } catch (
                    e: Exception
                ) {

                    audioMatchStatus =
                        "Error: ${e.message}"


                    matchedAudioAsset =
                        null
                }
            }
        }


    // ============================================================
    // DOCUMENT PICKER
    // ============================================================

    val documentPicker =
        rememberLauncherForActivityResult(

            contract =
                ActivityResultContracts
                    .OpenDocument()

        ) { uri: Uri? ->

            if (uri == null) {

                return@rememberLauncherForActivityResult
            }


            handleDocumentSelected(
                uri
            )
        }


    // ============================================================
    // AUDIO PICKER — VOICE TEST
    // ============================================================

    val audioPicker =
        rememberLauncherForActivityResult(

            contract =
                ActivityResultContracts
                    .OpenDocument()

        ) { uri: Uri? ->

            if (uri == null) {

                return@rememberLauncherForActivityResult
            }


            try {

                voiceStatus =
                    "Recognizing..."


                recognizedText =
                    VoiceRecognizer
                        .recognizeWavFile(

                            context =
                                context,

                            uri =
                                uri
                        )


                voiceStatus =
                    "Done"

            } catch (
                e: Exception
            ) {

                voiceStatus =
                    "Error: ${e.message}"


                recognizedText =
                    ""
            }
        }


    // ============================================================
    // MICROPHONE PERMISSION
    // ============================================================

    val microphonePermissionLauncher =
        rememberLauncherForActivityResult(

            contract =
                ActivityResultContracts
                    .RequestPermission()

        ) { granted ->

            if (granted) {

                if (!modelReady) {

                    liveVoiceStatus =
                        "Hindi model is still loading."

                    return@rememberLauncherForActivityResult
                }


                startLiveTranslation(
                    context = context,
                    onListeningChanged = {
                        isLiveListening = it
                    },
                    onStatusChanged = {
                        liveVoiceStatus = it
                    },
                    onRecognizedChanged = {
                        liveRecognizedText = it
                    },
                    onTranslatedChanged = {
                        liveTranslatedText = it
                    },
                    onLatencyChanged = {
                        liveLatencyText = it
                    },
                    onNeuralTranslate = onNeuralTranslate
                )

            } else {

                liveVoiceStatus =
                    "Microphone permission denied."
            }
        }


    // ============================================================
    // MAIN UI
    // ============================================================

    Column(

        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(
                    rememberScrollState()
                )
                .padding(24.dp),

        verticalArrangement =
            Arrangement.Top
    ) {


        // ========================================================
        // TITLE
        // ========================================================

        Text(
            text = "LinguaBridge",

            style =
                MaterialTheme
                    .typography
                    .headlineMedium
        )


        Spacer(
            modifier =
                Modifier.height(8.dp)
        )


        Text(
            text =
                "Offline Hindi ↔ Santali Translator",

            style =
                MaterialTheme
                    .typography
                    .bodyLarge
        )


        // ========================================================
        // INPUT LANGUAGE
        // ========================================================

        Spacer(
            modifier =
                Modifier.height(24.dp)
        )


        Text(
            text = "Input Language",

            style =
                MaterialTheme
                    .typography
                    .titleMedium
        )


        Spacer(
            modifier =
                Modifier.height(8.dp)
        )


        Row(
            modifier =
                Modifier.fillMaxWidth()
        ) {

            OutlinedButton(
                onClick = {
                    expanded = true
                }
            ) {

                Text(
                    text =
                        inputLanguage
                )
            }


            DropdownMenu(

                expanded =
                    expanded,

                onDismissRequest = {
                    expanded = false
                }
            ) {

                DropdownMenuItem(

                    text = {
                        Text("Hindi")
                    },

                    onClick = {

                        inputLanguage =
                            "Hindi"


                        expanded =
                            false


                        inputText =
                            ""


                        outputText =
                            ""


                        status =
                            ""


                        matchedId =
                            ""
                    }
                )


                DropdownMenuItem(

                    text = {
                        Text("Santali")
                    },

                    onClick = {

                        inputLanguage =
                            "Santali"


                        expanded =
                            false


                        inputText =
                            ""


                        outputText =
                            ""


                        status =
                            ""


                        matchedId =
                            ""
                    }
                )
            }
        }


        // ========================================================
        // TEXT INPUT
        // ========================================================

        Spacer(
            modifier =
                Modifier.height(20.dp)
        )


        Text(
            text = "Enter Text",

            style =
                MaterialTheme
                    .typography
                    .titleMedium
        )


        Spacer(
            modifier =
                Modifier.height(8.dp)
        )


        OutlinedTextField(

            value =
                inputText,

            onValueChange = {
                inputText = it
            },

            modifier =
                Modifier.fillMaxWidth(),

            label = {

                Text(

                    if (
                        inputLanguage ==
                        "Hindi"
                    )
                        "Enter Hindi"
                    else
                        "Enter Santali"
                )
            },

            minLines = 4
        )


        Spacer(
            modifier =
                Modifier.height(16.dp)
        )


        // ========================================================
        // TRANSLATE
        // ========================================================

        Button(

            onClick = {

                val cleanedInput =
                    normalizeText(
                        inputText
                    )


                if (
                    cleanedInput.isEmpty()
                ) {

                    outputText =
                        ""


                    status =
                        "NO MATCH"


                    matchedId =
                        ""

                } else {

                    // ==================================================
                    // SEMANTIC MATCHING
                    // ==================================================

                    val result =

                        if (
                            inputLanguage ==
                            "Hindi"
                        ) {

                            semanticMatcher
                                .matchHindi(
                                    cleanedInput
                                )

                        } else {

                            semanticMatcher
                                .matchSantali(
                                    cleanedInput
                                )
                        }


                    // --------------------------------------------------
                    // Convert matcher status to UI status
                    // --------------------------------------------------

                    status =

                        when (
                            result.status
                        ) {

                            SemanticPhraseMatcher
                                .MatchStatus
                                .VERIFIED ->

                                "VERIFIED"


                            SemanticPhraseMatcher
                                .MatchStatus
                                .AMBIGUOUS ->

                                "AMBIGUOUS"


                            SemanticPhraseMatcher
                                .MatchStatus
                                .NO_MATCH ->

                                "NO MATCH"
                        }


                    // --------------------------------------------------
                    // Store matched phrase ID
                    // --------------------------------------------------

                    matchedId =

                        result
                            .matchedPhrase
                            ?.id
                            ?: ""


                    // --------------------------------------------------
                    // Generate translation
                    // --------------------------------------------------

                    if (
                        result.status !=
                        SemanticPhraseMatcher
                            .MatchStatus
                            .NO_MATCH
                    ) {
                        isNeuralTranslating = false
                        neuralTranslationInfo = ""
                        val match = result.matchedPhrase
                        outputText = if (match != null) {
                            if (inputLanguage == "Hindi") match.santali else match.hindi
                        } else {
                            ""
                        }
                    } else {
                        outputText = ""
                        isNeuralTranslating = true
                        neuralTranslationInfo = "Phrase not in bank — running IndicTrans2..."
                        onNeuralTranslate(
                            cleanedInput,
                            inputLanguage,
                            { translated ->
                                isNeuralTranslating = false
                                neuralTranslationInfo = "Translated with IndicTrans2"
                                outputText = translated
                            },
                            { error ->
                                isNeuralTranslating = false
                                neuralTranslationInfo = error
                                outputText = ""
                            }
                        )
                    }
                }
            },

            modifier =
                Modifier.fillMaxWidth(),

            enabled = !isNeuralTranslating
        ) {

            Text(
                text =
                    if (isNeuralTranslating) "TRANSLATING…" else "TRANSLATE"
            )
        }


        Spacer(
            modifier =
                Modifier.height(20.dp)
        )


        // ========================================================
        // STATUS
        // ========================================================

        if (
            status.isNotEmpty()
        ) {

            Text(
                text = "Status",

                style =
                    MaterialTheme
                        .typography
                        .titleMedium
            )


            Spacer(
                modifier =
                    Modifier.height(6.dp)
            )


            Text(
                text =
                    status,

                style =
                    MaterialTheme
                        .typography
                        .headlineSmall,

                color =

                    when (
                        status
                    ) {

                        "VERIFIED" ->
                            Color(
                                0xFF2E7D32
                            )

                        "AMBIGUOUS" ->
                            Color(
                                0xFFF9A825
                            )

                        "NO MATCH" ->
                            Color(
                                0xFFC62828
                            )

                        else ->
                            MaterialTheme
                                .colorScheme
                                .onBackground
                    }
            )
        }


        if (
            matchedId.isNotEmpty()
        ) {

            Spacer(
                modifier =
                    Modifier.height(8.dp)
            )


            Text(
                text =
                    "Phrase ID: $matchedId"
            )
        }


        // ========================================================
        // TRANSLATION
        // ========================================================

        Spacer(
            modifier =
                Modifier.height(16.dp)
        )


        if (neuralTranslationInfo.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = neuralTranslationInfo,
                style = MaterialTheme.typography.bodyMedium
            )
        }


        Text(
            text = "Translation",

            style =
                MaterialTheme
                    .typography
                    .titleMedium
        )


        Spacer(
            modifier =
                Modifier.height(8.dp)
        )


        OutlinedTextField(

            value =
                outputText,

            onValueChange = {},

            readOnly = true,

            modifier =
                Modifier.fillMaxWidth(),

            minLines = 4,

            label = {

                Text(

                    if (
                        inputLanguage ==
                        "Hindi"
                    )
                        "Santali (Ol Chiki)"
                    else
                        "Hindi"
                )
            },

            textStyle =

                if (
                    inputLanguage ==
                    "Hindi"
                ) {

                    MaterialTheme
                        .typography
                        .bodyLarge
                        .copy(
                            fontFamily =
                                OlChikiFont
                        )

                } else {

                    MaterialTheme
                        .typography
                        .bodyLarge
                }
        )


        // ========================================================
        // VOICE TEST — STEP 1
        // ========================================================

        Spacer(
            modifier =
                Modifier.height(40.dp)
        )


        Text(
            text =
                "Voice Test — Step 1",

            style =
                MaterialTheme
                    .typography
                    .titleLarge
        )


        Spacer(
            modifier =
                Modifier.height(8.dp)
        )


        Text(
            text =
                "Hindi WAV → Vosk → Hindi text",

            style =
                MaterialTheme
                    .typography
                    .bodyMedium
        )


        Spacer(
            modifier =
                Modifier.height(16.dp)
        )


        Text(
            text =
                if (modelReady)
                    "Hindi model: READY ✓"
                else
                    "Hindi model: LOADING...",

            style =
                MaterialTheme
                    .typography
                    .bodyLarge
        )


        Spacer(
            modifier =
                Modifier.height(12.dp)
        )


        Button(

            onClick = {

                audioPicker.launch(
                    arrayOf("audio/*")
                )
            },

            modifier =
                Modifier.fillMaxWidth(),

            enabled =
                modelReady
        ) {

            Text(
                text =
                    "PICK HINDI WAV FILE"
            )
        }


        Spacer(
            modifier =
                Modifier.height(12.dp)
        )


        Text(
            text =
                "Status: $voiceStatus"
        )


        Spacer(
            modifier =
                Modifier.height(8.dp)
        )


        Text(
            text =
                "Recognized: $recognizedText",

            style =
                MaterialTheme
                    .typography
                    .bodyLarge
        )


        // ========================================================
        // VOICE-TO-VOICE DEMO
        // ========================================================

        Spacer(
            modifier =
                Modifier.height(40.dp)
        )


        Text(
            text =
                "Voice-to-Voice Demo",

            style =
                MaterialTheme
                    .typography
                    .titleLarge
        )


        Spacer(
            modifier =
                Modifier.height(8.dp)
        )


        Text(
            text =
                "Pick one of the known Hindi or Santali audio files. The matching translation will be prepared for playback.",

            style =
                MaterialTheme
                    .typography
                    .bodyMedium
        )


        Spacer(
            modifier =
                Modifier.height(16.dp)
        )


        Button(

            onClick = {

                audioPairPicker.launch(
                    arrayOf("audio/*")
                )
            },

            modifier =
                Modifier.fillMaxWidth()
        ) {

            Text(
                "PICK AUDIO FILE"
            )
        }


        Spacer(
            modifier =
                Modifier.height(12.dp)
        )


        if (
            audioMatchStatus.isNotEmpty()
        ) {

            Text(
                text =
                    audioMatchStatus,

                style =
                    MaterialTheme
                        .typography
                        .bodyLarge
            )
        }


        if (
            matchedAudioAsset != null
        ) {

            Spacer(
                modifier =
                    Modifier.height(12.dp)
            )


            Button(

                onClick = {

                    try {

                        AudioPairMatcher
                            .playAsset(

                                context =
                                    context,

                                assetPath =
                                    matchedAudioAsset!!
                            )

                    } catch (
                        e: Exception
                    ) {

                        audioMatchStatus =
                            "Playback error: ${e.message}"
                    }
                },

                modifier =
                    Modifier.fillMaxWidth()
            ) {

                Text(
                    text =
                        "▶ PLAY TRANSLATION"
                )
            }
        }


        // ========================================================
        // LIVE HINDI → SANTALI VOICE TRANSLATION
        // ========================================================

        Spacer(
            modifier =
                Modifier.height(40.dp)
        )


        Text(
            text =
                "Live Hindi → Santali",

            style =
                MaterialTheme
                    .typography
                    .titleLarge
        )


        Spacer(
            modifier =
                Modifier.height(8.dp)
        )


        Text(
            text =
                "Speak Hindi naturally. Vosk converts your speech to Hindi text, then IndicTrans2 translates it to Santali offline.",

            style =
                MaterialTheme
                    .typography
                    .bodyMedium
        )


        Spacer(
            modifier =
                Modifier.height(16.dp)
        )


        Text(
            text =
                if (modelReady)
                    "Hindi model: READY ✓"
                else
                    "Hindi model: LOADING...",

            style =
                MaterialTheme
                    .typography
                    .bodyLarge
        )


        Spacer(
            modifier =
                Modifier.height(12.dp)
        )


        Button(

            onClick = {

                if (
                    isLiveListening
                ) {

                    VoiceRecognizer
                        .stopLiveRecognition()


                    isLiveListening =
                        false


                    liveVoiceStatus =
                        "Stopped listening."

                } else {

                    if (!modelReady) {

                        liveVoiceStatus =
                            "Hindi model is still loading."

                        return@Button
                    }


                    val permissionGranted =

                        ContextCompat
                            .checkSelfPermission(

                                context,

                                Manifest.permission
                                    .RECORD_AUDIO

                            ) ==
                                PackageManager
                                    .PERMISSION_GRANTED


                    if (
                        permissionGranted
                    ) {

                        startLiveTranslation(

                            context =
                                context,

                            onListeningChanged = {
                                isLiveListening =
                                    it
                            },

                            onStatusChanged = {
                                liveVoiceStatus =
                                    it
                            },

                            onRecognizedChanged = {
                                liveRecognizedText =
                                    it
                            },

                            onTranslatedChanged = {
                                liveTranslatedText =
                                    it
                            },

                            onLatencyChanged = {
                                liveLatencyText =
                                    it
                            },

                            onNeuralTranslate =
                                onNeuralTranslate
                        )

                    } else {

                        microphonePermissionLauncher
                            .launch(
                                Manifest.permission
                                    .RECORD_AUDIO
                            )
                    }
                }
            },

            modifier =
                Modifier.fillMaxWidth(),

            enabled =
                modelReady ||
                        isLiveListening
        ) {

            Text(

                text =

                    if (
                        isLiveListening
                    )
                        "■ STOP LIVE TRANSLATION"
                    else
                        "🎤 START LIVE TRANSLATION"
            )
        }


        Spacer(
            modifier =
                Modifier.height(16.dp)
        )


        // ========================================================
        // LIVE STATUS
        // ========================================================

        Text(
            text =
                "Live Status",

            style =
                MaterialTheme
                    .typography
                    .titleMedium
        )


        Spacer(
            modifier =
                Modifier.height(6.dp)
        )


        Text(

            text =

                if (
                    liveVoiceStatus
                        .isEmpty()
                )
                    "Ready"
                else
                    liveVoiceStatus,

            style =
                MaterialTheme
                    .typography
                    .bodyLarge
        )


        Spacer(
            modifier =
                Modifier.height(12.dp)
        )


        // ========================================================
        // LIVE RECOGNIZED HINDI
        // ========================================================

        Text(
            text =
                "Recognized Hindi",

            style =
                MaterialTheme
                    .typography
                    .titleMedium
        )


        Spacer(
            modifier =
                Modifier.height(6.dp)
        )


        Text(

            text =

                if (
                    liveRecognizedText
                        .isEmpty()
                )
                    "—"
                else
                    liveRecognizedText,

            style =
                MaterialTheme
                    .typography
                    .bodyLarge
        )


        Spacer(
            modifier =
                Modifier.height(12.dp)
        )


        // ========================================================
        // LIVE SANTALI OUTPUT
        // ========================================================

        Text(
            text =
                "Santali Output",

            style =
                MaterialTheme
                    .typography
                    .titleMedium
        )


        Spacer(
            modifier =
                Modifier.height(6.dp)
        )


        OutlinedTextField(

            value =
                liveTranslatedText,

            onValueChange = {},

            readOnly = true,

            modifier =
                Modifier.fillMaxWidth(),

            minLines = 3,

            label = {
                Text("Santali (Ol Chiki)")
            },

            textStyle =
                MaterialTheme
                    .typography
                    .bodyLarge
                    .copy(
                        fontFamily =
                            OlChikiFont
                    )
        )


        if (
            liveLatencyText.isNotEmpty()
        ) {

            Spacer(
                modifier =
                    Modifier.height(8.dp)
            )

            Text(
                text =
                    liveLatencyText,

                style =
                    MaterialTheme
                        .typography
                        .bodyMedium
            )
        }


        // ========================================================
        // DOCUMENT TRANSLATION
        // ========================================================

        Spacer(
            modifier =
                Modifier.height(40.dp)
        )


        Text(
            text =
                "Document Translation",

            style =
                MaterialTheme
                    .typography
                    .titleLarge
        )


        Spacer(
            modifier =
                Modifier.height(8.dp)
        )


        Text(
            text =
                "Upload a Hindi DOCX/PPTX and get back the same file, fully translated to Santali, with images and layout untouched.",

            style =
                MaterialTheme
                    .typography
                    .bodyMedium
        )


        Spacer(
            modifier =
                Modifier.height(16.dp)
        )


        Surface(

            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .border(

                        border =
                            BorderStroke(
                                2.dp,
                                MaterialTheme
                                    .colorScheme
                                    .outline
                            ),

                        shape =
                            RoundedCornerShape(
                                16.dp
                            )
                    ),

            shape =
                RoundedCornerShape(
                    16.dp
                )
        ) {

            Column(

                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(20.dp),

                verticalArrangement =
                    Arrangement.Center
            ) {

                Text(

                    text =

                        if (
                            selectedFileName !=
                            null
                        )
                            "✓ Document selected"
                        else
                            "Upload teaching document",

                    style =
                        MaterialTheme
                            .typography
                            .titleMedium
                )


                Spacer(
                    modifier =
                        Modifier.height(6.dp)
                )


                Text(

                    text =
                        selectedFileName
                            ?: "Choose a DOCX or PPTX file",

                    style =
                        MaterialTheme
                            .typography
                            .bodyMedium
                )


                Spacer(
                    modifier =
                        Modifier.height(12.dp)
                )


                OutlinedButton(

                    onClick = {

                        documentPicker.launch(
                            arrayOf("*/*")
                        )
                    }
                ) {

                    Text(
                        text =
                            "CHOOSE DOCUMENT"
                    )
                }
            }
        }


        Spacer(
            modifier =
                Modifier.height(8.dp)
        )


        Text(
            text =
                "Supported formats: DOCX • PPTX",

            style =
                MaterialTheme
                    .typography
                    .bodySmall
        )


        if (
            selectedUri != null
        ) {

            Spacer(
                modifier =
                    Modifier.height(16.dp)
            )


            Button(

                onClick = {
                    rebuildSelectedDocument()
                },

                modifier =
                    Modifier.fillMaxWidth()
            ) {

                Text(

                    text =

                        if (
                            isRebuilding
                        )
                            "TRANSLATING…"
                        else
                            "TRANSLATE & REBUILD DOCUMENT"
                )
            }
        }


        rebuildResult?.let { result ->

            Spacer(
                modifier =
                    Modifier.height(20.dp)
            )


            Text(
                text =
                    "REBUILD RESULT",

                style =
                    MaterialTheme
                        .typography
                        .titleMedium
            )


            Spacer(
                modifier =
                    Modifier.height(8.dp)
            )


            Text(

                text =
                    "Translated ${result.translatedCount} of ${result.totalParagraphs} text blocks (${result.unmatchedCount} not found in the phrase bank)."
            )


            Spacer(
                modifier =
                    Modifier.height(16.dp)
            )


            Button(

                onClick = {

                    saveAsLauncher.launch(
                        result.outputFile.name
                    )
                },

                modifier =
                    Modifier.fillMaxWidth()
            ) {

                Text(
                    text =
                        "SAVE AS…"
                )
            }


            saveMessage?.let { message ->

                Spacer(
                    modifier =
                        Modifier.height(8.dp)
                )


                Text(
                    text =
                        message
                )
            }
        }


        rebuildError?.let { error ->

            Spacer(
                modifier =
                    Modifier.height(12.dp)
            )


            Text(
                text =
                    "ERROR: $error"
            )
        }


        // ========================================================
        // TEXT PREVIEW
        // ========================================================

        Spacer(
            modifier =
                Modifier.height(40.dp)
        )


        Text(
            text =
                "Preview extracted text (optional)",

            style =
                MaterialTheme
                    .typography
                    .titleMedium
        )


        Spacer(
            modifier =
                Modifier.height(8.dp)
        )


        if (
            selectedUri != null
        ) {

            Spacer(
                modifier =
                    Modifier.height(8.dp)
            )


            Button(

                onClick = {
                    extractSelectedDocument()
                },

                modifier =
                    Modifier.fillMaxWidth()
            ) {

                Text(
                    text =
                        "EXTRACT DOCUMENT"
                )
            }
        }


        selectedDocument?.let { document ->

            Spacer(
                modifier =
                    Modifier.height(24.dp)
            )


            Text(
                text =
                    "DOCUMENT RESULT",

                style =
                    MaterialTheme
                        .typography
                        .titleMedium
            )


            Spacer(
                modifier =
                    Modifier.height(12.dp)
            )


            Text(
                text =
                    "File: ${document.fileName}"
            )


            Spacer(
                modifier =
                    Modifier.height(6.dp)
            )


            Text(
                text =
                    "Type: ${document.type}"
            )


            Spacer(
                modifier =
                    Modifier.height(6.dp)
            )


            Text(
                text =
                    "Images found: ${document.images.size}"
            )


            Spacer(
                modifier =
                    Modifier.height(20.dp)
            )


            Text(
                text =
                    "EXTRACTED TEXT",

                style =
                    MaterialTheme
                        .typography
                        .titleMedium
            )


            Spacer(
                modifier =
                    Modifier.height(8.dp)
            )


            document.texts
                .forEachIndexed { index, text ->

                    Text(

                        text =
                            "${index + 1}. $text",

                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    vertical = 5.dp
                                ),

                        style =
                            MaterialTheme
                                .typography
                                .bodyLarge
                    )
                }


            Spacer(
                modifier =
                    Modifier.height(20.dp)
            )


            Text(
                text =
                    "EXTRACTED IMAGES",

                style =
                    MaterialTheme
                        .typography
                        .titleMedium
            )


            Spacer(
                modifier =
                    Modifier.height(8.dp)
            )


            document.images
                .forEachIndexed { index, image ->

                    Text(

                        text =
                            "${index + 1}. ${image.name}",

                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(
                                    vertical = 4.dp
                                ),

                        style =
                            MaterialTheme
                                .typography
                                .bodyMedium
                    )
                }


            Spacer(
                modifier =
                    Modifier.height(24.dp)
            )


            Button(

                onClick = {
                    translateExtractedDocument()
                },

                modifier =
                    Modifier.fillMaxWidth()
            ) {

                Text(
                    text =
                        "PREVIEW TRANSLATION (TEXT ONLY)"
                )
            }
        }


        documentError?.let { error ->

            Spacer(
                modifier =
                    Modifier.height(12.dp)
            )


            Text(
                text =
                    "ERROR: $error"
            )
        }


        // ========================================================
        // TRANSLATION PREVIEW
        // ========================================================

        if (
            translatedTexts.isNotEmpty()
        ) {

            Spacer(
                modifier =
                    Modifier.height(28.dp)
            )


            Text(
                text =
                    "TRANSLATION PREVIEW",

                style =
                    MaterialTheme
                        .typography
                        .titleLarge
            )


            Spacer(
                modifier =
                    Modifier.height(16.dp)
            )


            translatedTexts
                .forEachIndexed { index, result ->

                    Text(

                        text =
                            "${index + 1}. ORIGINAL",

                        style =
                            MaterialTheme
                                .typography
                                .titleSmall
                    )


                    Spacer(
                        modifier =
                            Modifier.height(4.dp)
                    )


                    Text(

                        text =
                            result.original,

                        modifier =
                            Modifier.fillMaxWidth(),

                        style =
                            MaterialTheme
                                .typography
                                .bodyLarge
                    )


                    Spacer(
                        modifier =
                            Modifier.height(8.dp)
                    )


                    Text(
                        text =
                            "Status: ${result.status}"
                    )


                    if (
                        result.phraseId != null
                    ) {

                        Spacer(
                            modifier =
                                Modifier.height(4.dp)
                        )


                        Text(

                            text =
                                "Phrase ID: ${result.phraseId}",

                            style =
                                MaterialTheme
                                    .typography
                                    .bodySmall
                        )
                    }


                    Spacer(
                        modifier =
                            Modifier.height(8.dp)
                    )


                    if (
                        result.translated != null
                    ) {

                        Text(

                            text =
                                "TRANSLATION",

                            style =
                                MaterialTheme
                                    .typography
                                    .titleSmall
                        )


                        Spacer(
                            modifier =
                                Modifier.height(4.dp)
                        )


                        Text(

                            text =
                                result.translated,

                            modifier =
                                Modifier.fillMaxWidth(),

                            style =
                                MaterialTheme
                                    .typography
                                    .bodyLarge
                                    .copy(

                                        fontFamily =

                                            if (
                                                inputLanguage ==
                                                "Hindi"
                                            )
                                                OlChikiFont
                                            else
                                                FontFamily.Default
                                    )
                        )

                    } else {

                        Text(
                            text =
                                "Translation unavailable — phrase not found in the phrase bank."
                        )
                    }


                    Spacer(
                        modifier =
                            Modifier.height(20.dp)
                    )
                }
        }


        Spacer(
            modifier =
                Modifier.height(32.dp)
        )
    }
}


// ================================================================
// START LIVE TRANSLATION
// ================================================================

fun startLiveTranslation(

    context: Context,

    onListeningChanged:
        (Boolean) -> Unit,

    onStatusChanged:
        (String) -> Unit,

    onRecognizedChanged:
        (String) -> Unit,

    onTranslatedChanged:
        (String) -> Unit,

    onLatencyChanged:
        (String) -> Unit,

    onNeuralTranslate:
        (
        String,
        String,
        (String) -> Unit,
        (String) -> Unit
    ) -> Unit
) {

    onListeningChanged(
        true
    )

    onTranslatedChanged(
        ""
    )

    onLatencyChanged(
        ""
    )

    onStatusChanged(
        "Listening..."
    )

    VoiceRecognizer.startLiveRecognition(

        context =
            context,

        onPartialResult = { text ->

            onRecognizedChanged(
                text
            )

            onStatusChanged(
                "Listening..."
            )
        },

        onFinalResult = { text ->

            val cleanedText =
                normalizeText(
                    text
                )

            onRecognizedChanged(
                text
            )

            if (
                cleanedText.isEmpty()
            ) {

                onTranslatedChanged(
                    ""
                )

                onLatencyChanged(
                    ""
                )

                onStatusChanged(
                    "No Hindi speech recognized."
                )

                return@startLiveRecognition
            }

            /*
             * Translate every completed Vosk utterance automatically.
             *
             * Partial results are only displayed. We deliberately do
             * not run IndicTrans2 for every partial result because that
             * would start many simultaneous neural inferences while the
             * speaker is still talking.
             */
            onTranslatedChanged(
                ""
            )

            onLatencyChanged(
                ""
            )

            onStatusChanged(
                "Hindi recognized — translating to Santali..."
            )

            val startTime =
                System.currentTimeMillis()

            onNeuralTranslate(

                cleanedText,

                "Hindi",

                { translated ->

                    onTranslatedChanged(
                        translated
                    )

                    val elapsed =
                        System.currentTimeMillis() -
                                startTime

                    onStatusChanged(
                        "Santali translation ready."
                    )

                    onLatencyChanged(
                        "Translation time: ${elapsed} ms"
                    )

                    Log.d(
                        "LiveTranslationLatency",
                        "Hindi → Santali = ${elapsed} ms"
                    )
                },

                { error ->

                    onTranslatedChanged(
                        ""
                    )

                    val elapsed =
                        System.currentTimeMillis() -
                                startTime

                    onStatusChanged(
                        "Translation error: $error"
                    )

                    onLatencyChanged(
                        "Translation failed after ${elapsed} ms"
                    )

                    Log.e(
                        "LiveTranslationLatency",
                        "IndicTrans2 failed after ${elapsed} ms: $error"
                    )
                }
            )
        },

        onError = { error ->

            onListeningChanged(
                false
            )

            onStatusChanged(
                "Error: $error"
            )
        }
    )
}


// ================================================================
// FILE NAME
// ================================================================

fun getFileName(

    context: Context,

    uri: Uri

): String {

    var result: String? =
        null


    val cursor =
        context.contentResolver.query(
            uri,
            arrayOf(
                OpenableColumns.DISPLAY_NAME
            ),
            null,
            null,
            null
        )

    try {
        if (cursor != null && cursor.moveToFirst()) {
            val index =
                cursor.getColumnIndex(
                    OpenableColumns.DISPLAY_NAME
                )

            if (index >= 0) {
                result = cursor.getString(index)
            }
        }
    } finally {
        cursor?.close()
    }


    return result
        ?: "selected_document"
}


// ================================================================
// NORMALIZE TEXT
// ================================================================

fun normalizeText(
    text: String
): String {

    return text
        .trim()
        .replace(
            Regex("\\s+"),
            " "
        )
}


// ================================================================
// LINGUABRIDGE PRESENTATION SHELL
// ================================================================

private enum class LinguaBridgePage {
    HOME,
    TRANSLATE,
    VOICE,
    LEARN,
    MORE
}

@Composable
private fun LinguaBridgeShell(
    phraseBank: List<Phrase>,
    onNeuralTranslate: (String, String, (String) -> Unit, (String) -> Unit) -> Unit
) {
    var page by remember {
        mutableStateOf(LinguaBridgePage.HOME)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7F8FC))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = 70.dp,
                    bottom = 72.dp
                )
        ) {
            when (page) {
                LinguaBridgePage.HOME -> {
                    LinguaBridgeHome(
                        phraseBankSize = phraseBank.size,
                        onTranslate = { page = LinguaBridgePage.TRANSLATE },
                        onVoice = { page = LinguaBridgePage.VOICE },
                        onLearn = { page = LinguaBridgePage.LEARN },
                        onMore = { page = LinguaBridgePage.MORE }
                    )
                }

                LinguaBridgePage.TRANSLATE -> {
                    TranslatorScreen(
                        phraseBank = phraseBank,
                        onNeuralTranslate = onNeuralTranslate
                    )
                }

                LinguaBridgePage.VOICE -> {
                    LinguaBridgeModuleLanding(
                        title = "Voice Bridge",
                        subtitle = "Hindi ↔ Santali classroom interaction",
                        icon = "●",
                        description = "Use the existing offline Hindi speech pipeline, verified classroom phrase matching, and paired Santali audio from this workspace.",
                        actionText = "OPEN TRANSLATION WORKSPACE",
                        onAction = { page = LinguaBridgePage.TRANSLATE }
                    )
                }

                LinguaBridgePage.LEARN -> {
                    LearningStudioScreen(
                        onBack = { page = LinguaBridgePage.HOME },
                        onNeuralTranslate = onNeuralTranslate
                    )
                }

                LinguaBridgePage.MORE -> {
                    LinguaBridgeModuleLanding(
                        title = "Classroom Tools",
                        subtitle = "Documents • Audio • Video",
                        icon = "⋮",
                        description = "The document, paired-audio, video subtitle and export workflows stay connected to the existing translation engine.",
                        actionText = "OPEN TRANSLATION WORKSPACE",
                        onAction = { page = LinguaBridgePage.TRANSLATE }
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        ) {
            LinguaBridgeTopBar()
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            LinguaBridgeBottomBar(
                selectedPage = page,
                onPageSelected = { page = it }
            )
        }
    }
}

@Composable
private fun LinguaBridgeTopBar() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shadowElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 20.dp,
                    vertical = 13.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFE6F2EA)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "L",
                    color = Color(0xFF285943),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.padding(horizontal = 5.dp))

            Column(Modifier.fillMaxWidth(0.62f)) {
                Text(
                    text = "LinguaBridge",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1D2923)
                )
                Text(
                    text = "Offline Classroom Assistant",
                    fontSize = 12.sp,
                    color = Color(0xFF66716B)
                )
            }

            Surface(
                shape = RoundedCornerShape(50.dp),
                color = Color(0xFFEAF5EC)
            ) {
                Row(
                    modifier = Modifier.padding(
                        horizontal = 11.dp,
                        vertical = 7.dp
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "●",
                        color = Color(0xFF2E7D32),
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.padding(horizontal = 3.dp))
                    Text(
                        text = "Offline",
                        color = Color(0xFF285943),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun LinguaBridgeHome(
    phraseBankSize: Int,
    onTranslate: () -> Unit,
    onVoice: () -> Unit,
    onLearn: () -> Unit,
    onMore: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = 20.dp,
                vertical = 20.dp
            )
    ) {
        Text(
            text = "Classroom communication,\nwithout the language barrier.",
            fontSize = 27.sp,
            lineHeight = 33.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF18221D)
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "A local-first bridge for Hindi-speaking teachers and Santali-speaking children.",
            fontSize = 15.sp,
            lineHeight = 21.sp,
            color = Color(0xFF66716B)
        )

        Spacer(Modifier.height(18.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF285943)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = "HINDI  ↔  SANTALI",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Ready for the classroom",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    text = "$phraseBankSize verified/local classroom phrases available in the knowledge pack.",
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 13.sp
                )
            }
        }

        Spacer(Modifier.height(22.dp))

        Text(
            text = "COMMUNICATION",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF65736B)
        )

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            LinguaBridgeFeatureCard(
                modifier = Modifier.fillMaxWidth(0.48f),
                icon = "↔",
                title = "Two-way Text Translation",
                description = "Hindi ↔ Santali with verified phrases and neural fallback.",
                onClick = onTranslate
            )
        }

        Spacer(Modifier.height(11.dp))

        Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            LinguaBridgeFeatureCard(
                modifier = Modifier.fillMaxWidth(),
                icon = "●",
                title = "Voice Bridge",
                description = "Offline speech recognition, phrase matching and classroom audio.",
                onClick = onVoice
            )
        }

        Spacer(Modifier.height(22.dp))

        Text(
            text = "LEARNING & MEDIA",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF65736B)
        )

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            LinguaBridgeSmallCard(
                modifier = Modifier.fillMaxWidth(0.48f),
                icon = "▣",
                title = "Learning",
                subtitle = "Worksheets\n& flashcards",
                onClick = onLearn
            )

            LinguaBridgeSmallCard(
                modifier = Modifier.fillMaxWidth(0.48f),
                icon = "⋮",
                title = "More Tools",
                subtitle = "Documents\nAudio & video",
                onClick = onMore
            )
        }

        Spacer(Modifier.height(20.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = Color.White,
            shadowElevation = 1.dp
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "✓",
                    color = Color(0xFF2E7D32),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.padding(horizontal = 6.dp))
                Column {
                    Text(
                        text = "Local-first classroom workflow",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF26332B)
                    )
                    Text(
                        text = "Core phrase translation works offline.",
                        fontSize = 12.sp,
                        color = Color(0xFF6B766F)
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun LinguaBridgeFeatureCard(
    modifier: Modifier = Modifier,
    icon: String,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(19.dp),
        color = Color.White,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(17.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFE6F2EA)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = icon,
                    color = Color(0xFF285943),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.padding(horizontal = 6.dp))

            Column(Modifier.fillMaxWidth(0.80f)) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF26332B)
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = description,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = Color(0xFF68736C)
                )
            }

            Text(
                text = "›",
                fontSize = 27.sp,
                color = Color(0xFF718078)
            )
        }
    }
}

@Composable
private fun LinguaBridgeSmallCard(
    modifier: Modifier,
    icon: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = Color.White,
        shadowElevation = 1.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = icon,
                color = Color(0xFF285943),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF26332B)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = Color(0xFF68736C)
            )
        }
    }
}

@Composable
private fun LinguaBridgeModuleLanding(
    title: String,
    subtitle: String,
    icon: String,
    description: String,
    actionText: String,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(38.dp))

        Box(
            modifier = Modifier
                .size(82.dp)
                .clip(RoundedCornerShape(25.dp))
                .background(Color(0xFFE6F2EA)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = icon,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF285943)
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = title,
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF18221D)
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = subtitle,
            fontSize = 15.sp,
            color = Color(0xFF68736C)
        )

        Spacer(Modifier.height(20.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = Color.White,
            shadowElevation = 1.dp
        ) {
            Text(
                text = description,
                modifier = Modifier.padding(20.dp),
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = Color(0xFF4F5B54)
            )
        }

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = onAction,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(15.dp)
        ) {
            Text(actionText)
        }
    }
}

@Composable
private fun LinguaBridgeBottomBar(
    selectedPage: LinguaBridgePage,
    onPageSelected: (LinguaBridgePage) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shadowElevation = 5.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 7.dp,
                    vertical = 7.dp
                ),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            LinguaBridgeNavItem(
                icon = "⌂",
                label = "Home",
                selected = selectedPage == LinguaBridgePage.HOME,
                onClick = { onPageSelected(LinguaBridgePage.HOME) }
            )
            LinguaBridgeNavItem(
                icon = "↔",
                label = "Translate",
                selected = selectedPage == LinguaBridgePage.TRANSLATE,
                onClick = { onPageSelected(LinguaBridgePage.TRANSLATE) }
            )
            LinguaBridgeNavItem(
                icon = "●",
                label = "Voice",
                selected = selectedPage == LinguaBridgePage.VOICE,
                onClick = { onPageSelected(LinguaBridgePage.VOICE) }
            )
            LinguaBridgeNavItem(
                icon = "▣",
                label = "Learn",
                selected = selectedPage == LinguaBridgePage.LEARN,
                onClick = { onPageSelected(LinguaBridgePage.LEARN) }
            )
            LinguaBridgeNavItem(
                icon = "⋮",
                label = "More",
                selected = selectedPage == LinguaBridgePage.MORE,
                onClick = { onPageSelected(LinguaBridgePage.MORE) }
            )
        }
    }
}

@Composable
private fun LinguaBridgeNavItem(
    icon: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val color =
        if (selected) Color(0xFF285943) else Color(0xFF7A847E)

    Column(
        modifier = Modifier
            .width(64.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = icon,
            fontSize = 21.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = color
        )
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = color
        )
    }
}
