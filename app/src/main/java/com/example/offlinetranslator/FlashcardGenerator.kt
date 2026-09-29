package com.example.offlinetranslator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.FileOutputStream
import kotlin.math.min

private enum class FlashcardGrade(val label: String) {
    BALVATIKA("Balvatika"),
    GRADE_1("Grade 1"),
    GRADE_2("Grade 2"),
    GRADE_3("Grade 3")
}

private enum class FlashcardDifficulty(val label: String) {
    EASY("Easy"),
    MEDIUM("Medium"),
    CHALLENGING("Challenging")
}

private enum class FlashcardTemplate(val label: String) {
    PICTURE_VOCAB("Picture vocabulary"),
    QUESTION_ANSWER("Question / Answer"),
    CONCEPT("Concept / Example"),
    MIXED("Mixed FLN")
}

private enum class FlashcardTopic(
    val label: String,
    val key: String
) {
    NUMBERS("Numbers & Counting", "numbers"),
    ANIMALS("Animals", "animals"),
    FRUITS("Fruits", "fruits"),
    SHAPES("Shapes", "shapes"),
    COLORS("Colors", "colors"),
    BODY_PARTS("Body Parts", "body"),
    CLASSROOM("Classroom Objects", "classroom"),
    LETTERS("Letters & Sounds", "letters"),
    OPPOSITES("Opposites", "opposites"),
    CUSTOM("Custom Topic", "custom")
}

private enum class FlashcardPicture {
    APPLE,
    ANIMAL,
    FRUIT,
    SHAPE,
    COLOR,
    FLOWER,
    BODY,
    BOOK,
    LETTER,
    OPPOSITE,
    NUMBER,
    NONE
}

private data class FlashcardConfig(
    val grade: FlashcardGrade,
    val topic: FlashcardTopic,
    val customTopic: String,
    val difficulty: FlashcardDifficulty,
    val cardCount: Int,
    val template: FlashcardTemplate
)

private data class FlashcardDraft(
    val number: Int,
    val frontHindi: String,
    val answerHindi: String,
    val skill: String,
    val picture: FlashcardPicture,
    val pictureValue: Int = 1
)

private data class FlashcardItem(
    val number: Int,
    val frontHindi: String,
    val answerHindi: String,
    val santali: String,
    val skill: String,
    val picture: FlashcardPicture,
    val pictureValue: Int
)

private data class FlashcardSeed(
    val value: String,
    val prompt: String,
    val skill: String,
    val picture: FlashcardPicture,
    val pictureValue: Int = 1
)

@SuppressLint("InlinedApi")
@Composable
fun LearningStudioScreen(
    onBack: () -> Unit,
    onNeuralTranslate: (String, String, (String) -> Unit, (String) -> Unit) -> Unit
) {
    var mode by remember { mutableStateOf("home") }

    when (mode) {
        "worksheet" -> {
            WorksheetGeneratorScreen(
                onBack = { mode = "home" },
                onNeuralTranslate = onNeuralTranslate
            )
        }

        "flashcards" -> {
            FlashcardGeneratorScreen(
                onBack = { mode = "home" },
                onNeuralTranslate = onNeuralTranslate
            )
        }

        else -> {
            LearningStudioHome(
                onBack = onBack,
                onWorksheet = { mode = "worksheet" },
                onFlashcards = { mode = "flashcards" }
            )
        }
    }
}

@Composable
private fun LearningStudioHome(
    onBack: () -> Unit,
    onWorksheet: () -> Unit,
    onFlashcards: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Learning Studio",
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF18221D)
                )
                Text(
                    text = "FLN worksheets and bilingual flashcards",
                    fontSize = 13.sp,
                    color = Color(0xFF68736C)
                )
            }
            OutlinedButton(onClick = onBack) {
                Text("Back")
            }
        }

        Spacer(Modifier.height(18.dp))

        LearningToolCard(
            icon = "▤",
            title = "Worksheet Studio",
            subtitle = "Generate printable Hindi + Santali worksheets with questions, pictures and FLN skill focus.",
            buttonText = "CREATE WORKSHEET",
            onClick = onWorksheet
        )

        Spacer(Modifier.height(12.dp))

        LearningToolCard(
            icon = "◆",
            title = "Flashcard Studio",
            subtitle = "Create topic-based picture flashcards with Hindi on the front and Santali on the back.",
            buttonText = "CREATE FLASHCARDS",
            onClick = onFlashcards
        )
    }
}

@Composable
private fun LearningToolCard(
    icon: String,
    title: String,
    subtitle: String,
    buttonText: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        shadowElevation = 2.dp
    ) {
        Column(Modifier.padding(18.dp)) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(Color(0xFFE6F2EA)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = icon,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF285943)
                )
            }
            Spacer(Modifier.height(13.dp))
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1D2923)
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = subtitle,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = Color(0xFF68736C)
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(buttonText)
            }
        }
    }
}

@Composable
fun FlashcardGeneratorScreen(
    onBack: () -> Unit,
    onNeuralTranslate: (String, String, (String) -> Unit, (String) -> Unit) -> Unit
) {
    val context = LocalContext.current

    var grade by remember { mutableStateOf(FlashcardGrade.GRADE_1) }
    var topic by remember { mutableStateOf(FlashcardTopic.ANIMALS) }
    var customTopic by remember { mutableStateOf("") }
    var difficulty by remember { mutableStateOf(FlashcardDifficulty.EASY) }
    var cardCount by remember { mutableStateOf(10) }
    var template by remember { mutableStateOf(FlashcardTemplate.MIXED) }

    var generatedCards by remember { mutableStateOf<List<FlashcardItem>>(emptyList()) }
    var isGenerating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var pendingPdfBytes by remember { mutableStateOf<ByteArray?>(null) }

    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        if (uri != null && pendingPdfBytes != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    output.write(pendingPdfBytes)
                    output.flush()
                }
                status = "Flashcard PDF saved successfully."
            } catch (e: Exception) {
                status = "Could not save PDF: ${e.message ?: "unknown error"}"
            }
        }
    }

    fun generate() {
        val config = FlashcardConfig(
            grade = grade,
            topic = topic,
            customTopic = customTopic.trim(),
            difficulty = difficulty,
            cardCount = cardCount,
            template = template
        )

        val drafts = buildFlashcardDrafts(config)
        if (drafts.isEmpty()) {
            status = "Choose a topic or enter a custom topic."
            return
        }

        generatedCards = emptyList()
        isGenerating = true
        status = "Generating ${drafts.size} cards and translating to Santali..."

        fun translateAt(index: Int, completed: List<FlashcardItem>) {
            if (index >= drafts.size) {
                generatedCards = completed
                isGenerating = false
                status = "Flashcards ready • ${completed.size} bilingual cards"
                return
            }

            val draft = drafts[index]

            // Translate the card's learning word/concept itself.
            // The Hindi prompt stays on the front; the Santali translation is the back.
            onNeuralTranslate(
                draft.answerHindi,
                "Hindi",
                { santali ->
                    val next = completed + FlashcardItem(
                        number = draft.number,
                        frontHindi = draft.frontHindi,
                        answerHindi = draft.answerHindi,
                        santali = santali.trim(),
                        skill = draft.skill,
                        picture = draft.picture,
                        pictureValue = draft.pictureValue
                    )
                    generatedCards = next
                    status = "Translated ${index + 1} / ${drafts.size}"
                    translateAt(index + 1, next)
                },
                { error ->
                    isGenerating = false
                    status = "Translation stopped at card ${index + 1}: $error"
                }
            )
        }

        translateAt(0, emptyList())
    }

    fun exportPdf() {
        if (generatedCards.isEmpty()) return
        pendingPdfBytes = buildFlashcardPdf(
            context = context,
            grade = grade,
            topicLabel = if (topic == FlashcardTopic.CUSTOM) customTopic.trim() else topic.label,
            difficulty = difficulty,
            template = template,
            cards = generatedCards
        )
        savePdfLauncher.launch("LinguaBridge_Bilingual_Flashcards.pdf")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Flashcard Studio",
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF18221D)
                )
                Text(
                    text = "Hindi ↔ Santali • picture-supported FLN",
                    fontSize = 13.sp,
                    color = Color(0xFF68736C)
                )
            }
            OutlinedButton(onClick = onBack) {
                Text("Back")
            }
        }

        Spacer(Modifier.height(14.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = Color.White,
            shadowElevation = 1.dp
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "CREATE FLASHCARDS",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF557060)
                )
                Spacer(Modifier.height(10.dp))

                FlashcardDropdown(
                    label = "Grade",
                    value = grade.label,
                    options = FlashcardGrade.entries.map { it.label },
                    onSelected = { selected ->
                        grade = FlashcardGrade.entries.first { it.label == selected }
                    }
                )

                Spacer(Modifier.height(10.dp))

                FlashcardDropdown(
                    label = "Topic",
                    value = topic.label,
                    options = FlashcardTopic.entries.map { it.label },
                    onSelected = { selected ->
                        topic = FlashcardTopic.entries.first { it.label == selected }
                    }
                )

                if (topic == FlashcardTopic.CUSTOM) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = customTopic,
                        onValueChange = { customTopic = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Custom topic") },
                        placeholder = { Text("e.g. plants, transport, community helpers") },
                        singleLine = true
                    )
                }

                Spacer(Modifier.height(10.dp))

                FlashcardDropdown(
                    label = "Difficulty",
                    value = difficulty.label,
                    options = FlashcardDifficulty.entries.map { it.label },
                    onSelected = { selected ->
                        difficulty = FlashcardDifficulty.entries.first { it.label == selected }
                    }
                )

                Spacer(Modifier.height(10.dp))

                FlashcardDropdown(
                    label = "Number of cards",
                    value = cardCount.toString(),
                    options = listOf("5", "10", "15"),
                    onSelected = { selected -> cardCount = selected.toInt() }
                )

                Spacer(Modifier.height(10.dp))

                FlashcardDropdown(
                    label = "Template",
                    value = template.label,
                    options = FlashcardTemplate.entries.map { it.label },
                    onSelected = { selected ->
                        template = FlashcardTemplate.entries.first { it.label == selected }
                    }
                )

                Spacer(Modifier.height(12.dp))

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(13.dp),
                    color = Color(0xFFF2F8F3)
                ) {
                    Column(Modifier.padding(13.dp)) {
                        Text(
                            text = "FLN use",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF285943)
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = inferFlashcardFlnFocus(topic, customTopic),
                            fontSize = 12.sp,
                            color = Color(0xFF4E5E55)
                        )
                    }
                }

                Spacer(Modifier.height(13.dp))

                Button(
                    onClick = { generate() },
                    enabled = !isGenerating,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(if (isGenerating) "Generating..." else "GENERATE BILINGUAL FLASHCARDS")
                }
            }
        }

        if (status.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = status,
                fontSize = 12.sp,
                color = Color(0xFF526059)
            )
        }

        if (generatedCards.isNotEmpty()) {
            Spacer(Modifier.height(15.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "FLASHCARD PREVIEW",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF557060)
                )
                OutlinedButton(onClick = { exportPdf() }) {
                    Text("Save PDF")
                }
            }

            Spacer(Modifier.height(8.dp))

            generatedCards.forEach { card ->
                FlashcardPreview(card)
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun FlashcardPreview(card: FlashcardItem) {
    var showingBack by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showingBack = !showingBack },
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        shadowElevation = 2.dp
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CARD ${card.number}",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF557060)
                )
                Text(
                    text = if (showingBack) "SANTALI • TAP TO FLIP" else "HINDI • TAP TO FLIP",
                    fontSize = 10.sp,
                    color = Color(0xFF7A847E)
                )
            }

            Spacer(Modifier.height(9.dp))

            FlashcardPictureBox(
                picture = card.picture,
                value = card.pictureValue
            )

            Spacer(Modifier.height(10.dp))

            if (showingBack) {
                Text(
                    text = card.santali,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = OlChikiFont,
                    color = Color(0xFF20332A)
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    text = "${card.skill}",
                    fontSize = 11.sp,
                    fontFamily = OlChikiFont,
                    color = Color(0xFF42564C)
                )
            } else {
                Text(
                    text = card.frontHindi,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF20332A)
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    text = card.answerHindi,
                    fontSize = 17.sp,
                    color = Color(0xFF42564C)
                )
            }

            Spacer(Modifier.height(7.dp))
            Text(
                text = "FLN skill: ${card.skill}",
                fontSize = 10.sp,
                color = Color(0xFF7A847E)
            )
        }
    }
}

@Composable
private fun FlashcardPictureBox(
    picture: FlashcardPicture,
    value: Int
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFF5F8F5)
    ) {
        ComposeCanvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 4f
            when (picture) {
                FlashcardPicture.NUMBER,
                FlashcardPicture.APPLE,
                FlashcardPicture.FRUIT,
                FlashcardPicture.FLOWER,
                FlashcardPicture.ANIMAL,
                FlashcardPicture.BOOK,
                FlashcardPicture.BODY,
                FlashcardPicture.LETTER,
                FlashcardPicture.SHAPE,
                FlashcardPicture.COLOR,
                FlashcardPicture.OPPOSITE,
                FlashcardPicture.NONE -> {
                    drawSimplePicture(this, picture, value, stroke)
                }
            }
        }
    }
}

private fun drawSimplePicture(
    scope: androidx.compose.ui.graphics.drawscope.DrawScope,
    picture: FlashcardPicture,
    value: Int,
    stroke: Float
) {
    val w = scope.size.width
    val h = scope.size.height
    val cx = w / 2f
    val cy = h / 2f

    when (picture) {
        FlashcardPicture.NUMBER -> {
            val count = min(value.coerceAtLeast(1), 10)
            val cols = if (count <= 5) count else 5
            val rows = (count + 4) / 5
            val gap = 30f
            for (i in 0 until count) {
                val col = i % 5
                val row = i / 5
                val totalCols = min(cols, 5)
                val startX = cx - ((totalCols - 1) * gap) / 2f
                val x = startX + col * gap
                val y = cy - ((rows - 1) * gap) / 2f + row * gap
                scope.drawCircle(
                    color = Color(0xFF6E8F76),
                    radius = 9f,
                    center = androidx.compose.ui.geometry.Offset(x, y)
                )
            }
        }

        FlashcardPicture.APPLE,
        FlashcardPicture.FRUIT -> {
            scope.drawCircle(
                color = Color(0xFF7DA46E),
                radius = 48f,
                center = androidx.compose.ui.geometry.Offset(cx, cy + 8f)
            )
            scope.drawLine(
                color = Color(0xFF56704F),
                start = androidx.compose.ui.geometry.Offset(cx, cy - 38f),
                end = androidx.compose.ui.geometry.Offset(cx + 8f, cy - 57f),
                strokeWidth = stroke
            )
        }

        FlashcardPicture.FLOWER -> {
            scope.drawCircle(
                color = Color(0xFFD9A3B0),
                radius = 20f,
                center = androidx.compose.ui.geometry.Offset(cx - 20f, cy - 10f)
            )
            scope.drawCircle(
                color = Color(0xFFD9A3B0),
                radius = 20f,
                center = androidx.compose.ui.geometry.Offset(cx + 20f, cy - 10f)
            )
            scope.drawCircle(
                color = Color(0xFFD9A3B0),
                radius = 20f,
                center = androidx.compose.ui.geometry.Offset(cx, cy - 32f)
            )
            scope.drawCircle(
                color = Color(0xFFD9A3B0),
                radius = 20f,
                center = androidx.compose.ui.geometry.Offset(cx, cy + 12f)
            )
            scope.drawCircle(
                color = Color(0xFFE0A62E),
                radius = 13f,
                center = androidx.compose.ui.geometry.Offset(cx, cy - 10f)
            )
            scope.drawLine(
                color = Color(0xFF6E8F76),
                start = androidx.compose.ui.geometry.Offset(cx, cy + 2f),
                end = androidx.compose.ui.geometry.Offset(cx, cy + 54f),
                strokeWidth = stroke
            )
        }

        FlashcardPicture.ANIMAL -> {
            scope.drawCircle(
                color = Color(0xFFE4C89A),
                radius = 42f,
                center = androidx.compose.ui.geometry.Offset(cx, cy + 5f)
            )
            scope.drawCircle(
                color = Color(0xFFE4C89A),
                radius = 17f,
                center = androidx.compose.ui.geometry.Offset(cx - 34f, cy - 24f)
            )
            scope.drawCircle(
                color = Color(0xFFE4C89A),
                radius = 17f,
                center = androidx.compose.ui.geometry.Offset(cx + 34f, cy - 24f)
            )
            scope.drawCircle(
                color = Color(0xFF26332B),
                radius = 4f,
                center = androidx.compose.ui.geometry.Offset(cx - 15f, cy - 2f)
            )
            scope.drawCircle(
                color = Color(0xFF26332B),
                radius = 4f,
                center = androidx.compose.ui.geometry.Offset(cx + 15f, cy - 2f)
            )
        }

        FlashcardPicture.BOOK -> {
            scope.drawRect(
                color = Color(0xFF6E8F76),
                topLeft = androidx.compose.ui.geometry.Offset(cx - 55f, cy - 40f),
                size = androidx.compose.ui.geometry.Size(110f, 80f),
                style = Stroke(width = stroke)
            )
            scope.drawLine(
                color = Color(0xFF6E8F76),
                start = androidx.compose.ui.geometry.Offset(cx, cy - 40f),
                end = androidx.compose.ui.geometry.Offset(cx, cy + 40f),
                strokeWidth = stroke
            )
        }

        FlashcardPicture.BODY -> {
            scope.drawCircle(
                color = Color(0xFF6E8F76),
                radius = 22f,
                center = androidx.compose.ui.geometry.Offset(cx, cy - 30f)
            )
            scope.drawLine(
                color = Color(0xFF6E8F76),
                start = androidx.compose.ui.geometry.Offset(cx, cy - 5f),
                end = androidx.compose.ui.geometry.Offset(cx, cy + 48f),
                strokeWidth = stroke
            )
            scope.drawLine(
                color = Color(0xFF6E8F76),
                start = androidx.compose.ui.geometry.Offset(cx - 38f, cy + 10f),
                end = androidx.compose.ui.geometry.Offset(cx + 38f, cy + 10f),
                strokeWidth = stroke
            )
        }

        FlashcardPicture.LETTER -> {
            scope.drawRect(
                color = Color(0xFF6E8F76),
                topLeft = androidx.compose.ui.geometry.Offset(cx - 44f, cy - 44f),
                size = androidx.compose.ui.geometry.Size(88f, 88f),
                style = Stroke(width = stroke)
            )
        }

        FlashcardPicture.SHAPE -> {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx, cy - 50f)
                lineTo(cx + 52f, cy + 35f)
                lineTo(cx - 52f, cy + 35f)
                close()
            }
            scope.drawPath(path, color = Color(0xFF6E8F76), style = Stroke(width = stroke))
        }

        FlashcardPicture.COLOR -> {
            scope.drawCircle(
                color = Color(0xFF8BA893),
                radius = 45f,
                center = androidx.compose.ui.geometry.Offset(cx, cy)
            )
        }

        FlashcardPicture.OPPOSITE -> {
            scope.drawLine(
                color = Color(0xFF6E8F76),
                start = androidx.compose.ui.geometry.Offset(cx - 65f, cy),
                end = androidx.compose.ui.geometry.Offset(cx + 65f, cy),
                strokeWidth = stroke
            )
            scope.drawLine(
                color = Color(0xFF6E8F76),
                start = androidx.compose.ui.geometry.Offset(cx - 65f, cy),
                end = androidx.compose.ui.geometry.Offset(cx - 45f, cy - 15f),
                strokeWidth = stroke
            )
            scope.drawLine(
                color = Color(0xFF6E8F76),
                start = androidx.compose.ui.geometry.Offset(cx + 65f, cy),
                end = androidx.compose.ui.geometry.Offset(cx + 45f, cy + 15f),
                strokeWidth = stroke
            )
        }

        FlashcardPicture.NONE -> Unit
    }
}

@Composable
private fun FlashcardDropdown(
    label: String,
    value: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(13.dp)
        ) {
            Text(
                text = "$label: $value",
                modifier = Modifier.fillMaxWidth()
            )
        }

        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    }
                )
            }
        }
    }
}

private fun inferFlashcardFlnFocus(
    topic: FlashcardTopic,
    customTopic: String
): String {
    val normalized = (if (topic == FlashcardTopic.CUSTOM) customTopic else topic.key).lowercase()

    return when {
        normalized.contains("number") || normalized.contains("count") ->
            "Early numeracy • counting, number recognition and quantity-symbol connection"
        normalized.contains("letter") || normalized.contains("sound") || normalized.contains("vowel") ->
            "Foundational literacy • print awareness, letter recognition and sound association"
        normalized.contains("shape") || normalized.contains("color") ->
            "Foundational cognition • classification, visual discrimination and vocabulary"
        normalized.contains("animal") || normalized.contains("fruit") || normalized.contains("body") || normalized.contains("classroom") ->
            "Oral language • vocabulary building, picture naming and comprehension"
        normalized.contains("opposite") ->
            "Language and thinking • vocabulary, comparison and concept relationships"
        else ->
            "Foundational learning • vocabulary, picture association and oral-language practice"
    }
}

private fun buildFlashcardDrafts(config: FlashcardConfig): List<FlashcardDraft> {
    val seeds = when (config.topic) {
        FlashcardTopic.NUMBERS -> listOf(
            FlashcardSeed("1", "गिनो और संख्या पहचानो", "Number recognition", FlashcardPicture.NUMBER, 1),
            FlashcardSeed("2", "गिनो और संख्या पहचानो", "Number recognition", FlashcardPicture.NUMBER, 2),
            FlashcardSeed("3", "गिनो और संख्या पहचानो", "Counting", FlashcardPicture.NUMBER, 3),
            FlashcardSeed("4", "गिनो और संख्या पहचानो", "Counting", FlashcardPicture.NUMBER, 4),
            FlashcardSeed("5", "गिनो और संख्या पहचानो", "Counting", FlashcardPicture.NUMBER, 5),
            FlashcardSeed("6", "गिनो और संख्या पहचानो", "Counting", FlashcardPicture.NUMBER, 6),
            FlashcardSeed("7", "गिनो और संख्या पहचानो", "Counting", FlashcardPicture.NUMBER, 7),
            FlashcardSeed("8", "गिनो और संख्या पहचानो", "Counting", FlashcardPicture.NUMBER, 8),
            FlashcardSeed("9", "गिनो और संख्या पहचानो", "Counting", FlashcardPicture.NUMBER, 9),
            FlashcardSeed("10", "गिनो और संख्या पहचानो", "Counting", FlashcardPicture.NUMBER, 10)
        )

        FlashcardTopic.ANIMALS -> listOf(
            FlashcardSeed("गाय", "यह कौन-सा जानवर है?", "Picture vocabulary", FlashcardPicture.ANIMAL),
            FlashcardSeed("कुत्ता", "यह कौन-सा जानवर है?", "Picture vocabulary", FlashcardPicture.ANIMAL),
            FlashcardSeed("बिल्ली", "यह कौन-सा जानवर है?", "Picture vocabulary", FlashcardPicture.ANIMAL),
            FlashcardSeed("हाथी", "यह कौन-सा जानवर है?", "Picture vocabulary", FlashcardPicture.ANIMAL),
            FlashcardSeed("घोड़ा", "यह कौन-सा जानवर है?", "Picture vocabulary", FlashcardPicture.ANIMAL),
            FlashcardSeed("बकरी", "यह कौन-सा जानवर है?", "Picture vocabulary", FlashcardPicture.ANIMAL),
            FlashcardSeed("शेर", "यह कौन-सा जानवर है?", "Picture vocabulary", FlashcardPicture.ANIMAL),
            FlashcardSeed("मछली", "यह कौन-सा जानवर है?", "Picture vocabulary", FlashcardPicture.ANIMAL),
            FlashcardSeed("पक्षी", "यह क्या है?", "Picture vocabulary", FlashcardPicture.ANIMAL),
            FlashcardSeed("खरगोश", "यह कौन-सा जानवर है?", "Picture vocabulary", FlashcardPicture.ANIMAL)
        )

        FlashcardTopic.FRUITS -> listOf(
            "सेब", "केला", "आम", "अमरूद", "संतरा", "पपीता", "अंगूर", "अनार", "तरबूज", "नाशपाती"
        ).map { FlashcardSeed(it, "यह कौन-सा फल है?", "Picture vocabulary", FlashcardPicture.FRUIT) }

        FlashcardTopic.SHAPES -> listOf(
            "वृत्त", "त्रिभुज", "वर्ग", "आयत", "अंडाकार", "अर्धवृत्त", "हीरा", "तारा", "बड़ा वृत्त", "छोटा वृत्त"
        ).map { FlashcardSeed(it, "यह कौन-सा आकार है?", "Shape recognition", FlashcardPicture.SHAPE) }

        FlashcardTopic.COLORS -> listOf(
            "लाल", "नीला", "हरा", "पीला", "नारंगी", "गुलाबी", "काला", "सफेद", "बैंगनी", "भूरा"
        ).map { FlashcardSeed(it, "यह कौन-सा रंग है?", "Color recognition", FlashcardPicture.COLOR) }

        FlashcardTopic.BODY_PARTS -> listOf(
            "आँख", "कान", "नाक", "मुँह", "हाथ", "पैर", "सिर", "बाल", "दाँत", "उंगली"
        ).map { FlashcardSeed(it, "यह शरीर का कौन-सा अंग है?", "Body-part vocabulary", FlashcardPicture.BODY) }

        FlashcardTopic.CLASSROOM -> listOf(
            "किताब", "पेंसिल", "कापी", "कुर्सी", "मेज़", "बस्ता", "रबर", "स्केल", "बोर्ड", "रंग"
        ).map { FlashcardSeed(it, "यह क्या है?", "Classroom vocabulary", FlashcardPicture.BOOK) }

        FlashcardTopic.LETTERS -> listOf(
            "अ", "आ", "इ", "ई", "उ", "ऊ", "ए", "ऐ", "ओ", "औ"
        ).map { FlashcardSeed(it, "इस अक्षर को पहचानो।", "Letter recognition", FlashcardPicture.LETTER) }

        FlashcardTopic.OPPOSITES -> listOf(
            "बड़ा — छोटा",
            "ऊँचा — नीचा",
            "दिन — रात",
            "गर्म — ठंडा",
            "आगे — पीछे",
            "ऊपर — नीचे",
            "खुला — बंद",
            "तेज़ — धीमा",
            "पास — दूर",
            "भरा — खाली"
        ).map { FlashcardSeed(it, "इन विपरीत शब्दों को पहचानो।", "Concept relationship", FlashcardPicture.OPPOSITE) }

        FlashcardTopic.CUSTOM -> buildCustomFlashcardSeeds(config.customTopic)
    }

    val limited = seeds.take(config.cardCount.coerceAtMost(15))

    return limited.mapIndexed { index, seed ->
        val prompt = when (config.template) {
            FlashcardTemplate.PICTURE_VOCAB -> seed.prompt
            FlashcardTemplate.QUESTION_ANSWER -> seed.prompt
            FlashcardTemplate.CONCEPT -> conceptPrompt(seed, config.difficulty)
            FlashcardTemplate.MIXED -> when (index % 3) {
                0 -> seed.prompt
                1 -> conceptPrompt(seed, config.difficulty)
                else -> seed.prompt
            }
        }

        FlashcardDraft(
            number = index + 1,
            frontHindi = prompt,
            answerHindi = seed.value,
            skill = seed.skill,
            picture = seed.picture,
            pictureValue = seed.pictureValue
        )
    }
}

private fun conceptPrompt(
    seed: FlashcardSeed,
    difficulty: FlashcardDifficulty
): String {
    return when (difficulty) {
        FlashcardDifficulty.EASY -> "बताओ: ${seed.value} क्या है?"
        FlashcardDifficulty.MEDIUM -> "पहचानो और नाम बताओ: ${seed.value}"
        FlashcardDifficulty.CHALLENGING -> "${seed.value} के बारे में बताओ और उसका नाम पहचानो।"
    }
}

private fun buildCustomFlashcardSeeds(topic: String): List<FlashcardSeed> {
    val clean = topic.trim()
    if (clean.isEmpty()) return emptyList()

    val common = when {
        clean.contains("plant", true) || clean.contains("पौध", true) ->
            listOf("पेड़", "पत्ता", "फूल", "जड़", "तना", "बीज", "फल", "पौधा", "बगीचा", "मिट्टी")
        clean.contains("transport", true) || clean.contains("परिवहन", true) ->
            listOf("बस", "कार", "साइकिल", "ट्रेन", "नाव", "हवाई जहाज", "ऑटो", "ट्रक", "रिक्शा", "मोटरसाइकिल")
        clean.contains("community", true) || clean.contains("समुदाय", true) ->
            listOf("डॉक्टर", "शिक्षक", "किसान", "डाकिया", "पुलिस", "दुकानदार", "नर्स", "चालक", "रसोइया", "माली")
        else -> listOf(
            "$clean 1", "$clean 2", "$clean 3", "$clean 4", "$clean 5",
            "$clean 6", "$clean 7", "$clean 8", "$clean 9", "$clean 10"
        )
    }

    return common.map {
        FlashcardSeed(
            value = it,
            prompt = "यह क्या है?",
            skill = "Topic vocabulary",
            picture = when {
                clean.contains("plant", true) || clean.contains("पौध", true) -> FlashcardPicture.FLOWER
                clean.contains("transport", true) || clean.contains("परिवहन", true) -> FlashcardPicture.BOOK
                clean.contains("community", true) || clean.contains("समुदाय", true) -> FlashcardPicture.BODY
                else -> FlashcardPicture.NONE
            }
        )
    }
}


private fun buildFlashcardPdf(
    context: Context,
    grade: FlashcardGrade,
    topicLabel: String,
    difficulty: FlashcardDifficulty,
    template: FlashcardTemplate,
    cards: List<FlashcardItem>
): ByteArray {
    val document = PdfDocument()
    val pageWidth = 595
    val pageHeight = 842
    val margin = 28
    val cardW = (pageWidth - margin * 3) / 2
    val cardH = 245

    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 20f
    }
    val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textSize = 10f
    }
    val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 10f
    }
    val hindiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = 15f
    }
    val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        textSize = 13f
    }
    val santaliTypeface = try {
        androidx.core.content.res.ResourcesCompat.getFont(context, R.font.noto_sans_ol_chiki_regular)
    } catch (_: Exception) {
        null
    }
    val santaliPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = santaliTypeface ?: Typeface.DEFAULT
        textSize = 14f
    }

    var pageNumber = 1
    var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
    var canvas = page.canvas
    drawFlashcardPageHeader(canvas, titlePaint, metaPaint, grade.label, topicLabel, difficulty.label, template.label, margin)

    cards.forEachIndexed { index, card ->
        val position = index % 4
        if (index > 0 && position == 0) {
            document.finishPage(page)
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            canvas = page.canvas
            drawFlashcardPageHeader(canvas, titlePaint, metaPaint, grade.label, topicLabel, difficulty.label, template.label, margin)
        }

        val row = position / 2
        val col = position % 2
        val left = margin + col * (cardW + margin)
        val top = 92 + row * (cardH + 24)
        val rect = RectF(left.toFloat(), top.toFloat(), (left + cardW).toFloat(), (top + cardH).toFloat())
        drawFlashcardPdfCard(
            canvas = canvas,
            rect = rect,
            card = card,
            numberPaint = numberPaint,
            hindiPaint = hindiPaint,
            bodyPaint = bodyPaint,
            santaliPaint = santaliPaint
        )
    }

    document.finishPage(page)
    val output = java.io.ByteArrayOutputStream()
    document.writeTo(output)
    document.close()
    return output.toByteArray()
}

private fun drawFlashcardPageHeader(
    canvas: Canvas,
    titlePaint: Paint,
    metaPaint: Paint,
    grade: String,
    topic: String,
    difficulty: String,
    template: String,
    margin: Int
) {
    canvas.drawText("LINGUABRIDGE", margin.toFloat(), 30f, titlePaint)
    canvas.drawText(
        "$grade • $topic • $difficulty • $template",
        margin.toFloat(),
        48f,
        metaPaint
    )
    canvas.drawText("Bilingual Hindi + Santali • FLN flashcards", margin.toFloat(), 64f, metaPaint)
}

private fun drawFlashcardPdfCard(
    canvas: Canvas,
    rect: RectF,
    card: FlashcardItem,
    numberPaint: Paint,
    hindiPaint: Paint,
    bodyPaint: Paint,
    santaliPaint: Paint
) {
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    canvas.drawRoundRect(rect, 18f, 18f, borderPaint)

    canvas.drawText("CARD ${card.number}", rect.left + 12f, rect.top + 18f, numberPaint)

    canvas.drawText(card.frontHindi, rect.left + 12f, rect.top + 50f, hindiPaint)
    canvas.drawText(card.answerHindi, rect.left + 12f, rect.top + 72f, bodyPaint)

    canvas.drawText("Santali", rect.left + 12f, rect.top + 105f, numberPaint)
    canvas.drawText(card.santali, rect.left + 12f, rect.top + 130f, santaliPaint)

    canvas.drawText("FLN: ${card.skill}", rect.left + 12f, rect.bottom - 18f, numberPaint)
}
