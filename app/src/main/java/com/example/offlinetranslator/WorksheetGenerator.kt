package com.example.offlinetranslator

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import java.io.FileOutputStream
import kotlin.math.min

private enum class WorksheetGrade(val label: String) {
    BALVATIKA("Balvatika"),
    GRADE_1("Grade 1"),
    GRADE_2("Grade 2"),
    GRADE_3("Grade 3")
}

private enum class WorksheetDifficulty(val label: String) {
    EASY("Easy"),
    MEDIUM("Medium"),
    CHALLENGING("Challenging")
}

private enum class WorksheetTemplate(val label: String) {
    PICTURE_RICH("Picture-rich"),
    FILL_WRITE("Fill / Write"),
    MATCH("Match"),
    MIXED("Mixed FLN")
}

private enum class WorksheetPicture {
    APPLES,
    ANIMALS,
    FLOWER,
    FISH,
    SHAPES,
    OBJECTS,
    GROUPS,
    NONE
}

private data class WorksheetConfig(
    val grade: WorksheetGrade,
    val topic: String,
    val difficulty: WorksheetDifficulty,
    val questionCount: Int,
    val template: WorksheetTemplate
)

private data class WorksheetQuestionDraft(
    val number: Int,
    val hindi: String,
    val skill: String,
    val picture: WorksheetPicture,
    val pictureCount: Int = 1,
    val secondaryCount: Int = 0,
    val answerLine: Boolean = true
)

private data class WorksheetQuestion(
    val number: Int,
    val hindi: String,
    val santali: String,
    val skill: String,
    val picture: WorksheetPicture,
    val pictureCount: Int,
    val secondaryCount: Int,
    val answerLine: Boolean
)

@SuppressLint("InlinedApi")
@Composable
fun WorksheetGeneratorScreen(
    onBack: () -> Unit,
    onNeuralTranslate: (String, String, (String) -> Unit, (String) -> Unit) -> Unit
) {
    val context = LocalContext.current

    var grade by remember { mutableStateOf(WorksheetGrade.GRADE_1) }
    var topic by remember { mutableStateOf("Numbers and counting") }
    var difficulty by remember { mutableStateOf(WorksheetDifficulty.EASY) }
    var questionCount by remember { mutableStateOf(5) }
    var template by remember { mutableStateOf(WorksheetTemplate.MIXED) }

    var generatedQuestions by remember { mutableStateOf<List<WorksheetQuestion>>(emptyList()) }
    var isGenerating by remember { mutableStateOf(false) }
    var generationStatus by remember { mutableStateOf("") }
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
                generationStatus = "Worksheet PDF saved successfully."
            } catch (e: Exception) {
                generationStatus = "Could not save PDF: ${e.message ?: "unknown error"}"
            }
        }
    }

    fun generate() {
        val cleanTopic = topic.trim().ifEmpty { "Foundational Learning" }
        val config = WorksheetConfig(
            grade = grade,
            topic = cleanTopic,
            difficulty = difficulty,
            questionCount = questionCount,
            template = template
        )

        val drafts = buildWorksheetDrafts(config)
        generatedQuestions = emptyList()
        isGenerating = true
        generationStatus = "Generating ${drafts.size} questions and translating to Santali..."

        fun translateAt(index: Int, completed: List<WorksheetQuestion>) {
            if (index >= drafts.size) {
                generatedQuestions = completed
                isGenerating = false
                generationStatus = "Worksheet ready • ${completed.size} bilingual questions"
                return
            }

            val draft = drafts[index]
            onNeuralTranslate(
                draft.hindi,
                "Hindi",
                { santali ->
                    val next = completed + WorksheetQuestion(
                        number = draft.number,
                        hindi = draft.hindi,
                        santali = santali,
                        skill = draft.skill,
                        picture = draft.picture,
                        pictureCount = draft.pictureCount,
                        secondaryCount = draft.secondaryCount,
                        answerLine = draft.answerLine
                    )
                    generatedQuestions = next
                    generationStatus = "Translated ${index + 1} / ${drafts.size}"
                    translateAt(index + 1, next)
                },
                { error ->
                    isGenerating = false
                    generationStatus = "Translation stopped at question ${index + 1}: $error"
                }
            )
        }

        translateAt(0, emptyList())
    }

    fun exportPdf() {
        if (generatedQuestions.isEmpty()) return
        pendingPdfBytes = buildWorksheetPdf(
            context = context,
            config = WorksheetConfig(
                grade = grade,
                topic = topic.trim().ifEmpty { "Foundational Learning" },
                difficulty = difficulty,
                questionCount = questionCount,
                template = template
            ),
            questions = generatedQuestions
        )
        savePdfLauncher.launch("LinguaBridge_FLN_Worksheet.pdf")
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
                    text = "Worksheet Studio",
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF18221D)
                )
                Text(
                    text = "Bilingual Hindi ↔ Santali • FLN focused",
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
                    text = "CREATE WORKSHEET",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF557060)
                )
                Spacer(Modifier.height(10.dp))

                WorksheetDropdown(
                    label = "Grade",
                    value = grade.label,
                    options = WorksheetGrade.entries.map { it.label },
                    onSelected = { selected ->
                        grade = WorksheetGrade.entries.first { it.label == selected }
                    }
                )

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = topic,
                    onValueChange = { topic = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Topic") },
                    placeholder = { Text("e.g. numbers, animals, vowels, shapes") },
                    singleLine = true
                )

                Spacer(Modifier.height(10.dp))

                WorksheetDropdown(
                    label = "Difficulty",
                    value = difficulty.label,
                    options = WorksheetDifficulty.entries.map { it.label },
                    onSelected = { selected ->
                        difficulty = WorksheetDifficulty.entries.first { it.label == selected }
                    }
                )

                Spacer(Modifier.height(10.dp))

                WorksheetDropdown(
                    label = "Number of questions",
                    value = questionCount.toString(),
                    options = listOf("5", "10", "15"),
                    onSelected = { selected -> questionCount = selected.toInt() }
                )

                Spacer(Modifier.height(10.dp))

                WorksheetDropdown(
                    label = "Template",
                    value = template.label,
                    options = WorksheetTemplate.entries.map { it.label },
                    onSelected = { selected ->
                        template = WorksheetTemplate.entries.first { it.label == selected }
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
                            text = "FLN focus",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = Color(0xFF285943)
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = inferFlnFocus(topic),
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
                    Text(if (isGenerating) "Generating..." else "GENERATE BILINGUAL WORKSHEET")
                }
            }
        }

        if (generationStatus.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = generationStatus,
                fontSize = 12.sp,
                color = Color(0xFF526059)
            )
        }

        if (generatedQuestions.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "WORKSHEET PREVIEW",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF557060)
                )
                OutlinedButton(onClick = { exportPdf() }) {
                    Text("Save PDF")
                }
            }

            Spacer(Modifier.height(8.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
                shadowElevation = 1.dp
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        text = "LINGUABRIDGE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF285943)
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = "${grade.label} • ${topic.trim().ifEmpty { "Foundational Learning" }}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF18221D)
                    )
                    Text(
                        text = "Difficulty: ${difficulty.label} • ${generatedQuestions.size} questions • ${template.label}",
                        fontSize = 11.sp,
                        color = Color(0xFF6A766F)
                    )

                    Spacer(Modifier.height(12.dp))

                    generatedQuestions.forEach { question ->
                        WorksheetQuestionPreview(question)
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun WorksheetDropdown(
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
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.Start) {
                    Text(label, fontSize = 10.sp, color = Color(0xFF6C7971))
                    Text(value, fontSize = 14.sp, color = Color(0xFF26332B))
                }
                Text("▾", color = Color(0xFF526059))
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun WorksheetQuestionPreview(question: WorksheetQuestion) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        color = Color(0xFFF9FAFC),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFE0E5E2))
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = "Q${question.number} • ${question.skill}",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF557060)
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = question.hindi,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1F2A23)
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = question.santali,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                color = Color(0xFF25352A),
                fontFamily = OlChikiFont
            )

            if (question.picture != WorksheetPicture.NONE) {
                Spacer(Modifier.height(8.dp))
                PicturePreview(
                    picture = question.picture,
                    count = question.pictureCount,
                    secondaryCount = question.secondaryCount
                )
            }

            if (question.answerLine) {
                Spacer(Modifier.height(9.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                    color = Color.White,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFBCC5BF))
                ) {}
            }
        }
    }
}

@Composable
private fun PicturePreview(
    picture: WorksheetPicture,
    count: Int,
    secondaryCount: Int
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .border(1.dp, Color(0xFFE5EAE7), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        ComposeCanvas(modifier = Modifier.fillMaxSize()) {
            when (picture) {
                WorksheetPicture.APPLES -> {
                    val safeCount = count.coerceIn(1, 8)
                    repeat(safeCount) { i ->
                        val x = 35f + (i % 4) * 62f
                        val y = 38f + (i / 4) * 45f
                        drawCircle(Color(0xFFF5B0A2), 15f, androidx.compose.ui.geometry.Offset(x, y))
                        drawLine(Color(0xFF5A764C), androidx.compose.ui.geometry.Offset(x, y - 14), androidx.compose.ui.geometry.Offset(x + 5, y - 20), strokeWidth = 3f)
                    }
                }
                WorksheetPicture.ANIMALS -> {
                    val safeCount = count.coerceIn(1, 5)
                    repeat(safeCount) { i ->
                        val x = 40f + i * 62f
                        val y = 50f
                        drawCircle(Color(0xFFF1D6A8), 18f, androidx.compose.ui.geometry.Offset(x, y))
                        drawCircle(Color(0xFFF1D6A8), 8f, androidx.compose.ui.geometry.Offset(x - 13, y - 15))
                        drawCircle(Color(0xFFF1D6A8), 8f, androidx.compose.ui.geometry.Offset(x + 13, y - 15))
                        drawCircle(Color(0xFF38433B), 2.5f, androidx.compose.ui.geometry.Offset(x - 6, y - 2))
                        drawCircle(Color(0xFF38433B), 2.5f, androidx.compose.ui.geometry.Offset(x + 6, y - 2))
                    }
                }
                WorksheetPicture.FLOWER -> {
                    drawCircle(Color(0xFFF3B3C4), 17f, androidx.compose.ui.geometry.Offset(95f, 45f))
                    drawCircle(Color(0xFFF3B3C4), 17f, androidx.compose.ui.geometry.Offset(125f, 45f))
                    drawCircle(Color(0xFFF3B3C4), 17f, androidx.compose.ui.geometry.Offset(110f, 27f))
                    drawCircle(Color(0xFFF3B3C4), 17f, androidx.compose.ui.geometry.Offset(110f, 63f))
                    drawCircle(Color(0xFFF5D26A), 9f, androidx.compose.ui.geometry.Offset(110f, 45f))
                    drawLine(Color(0xFF4E8A55), androidx.compose.ui.geometry.Offset(110f, 62f), androidx.compose.ui.geometry.Offset(110f, 93f), strokeWidth = 5f)
                }
                WorksheetPicture.FISH -> {
                    val center = androidx.compose.ui.geometry.Offset(110f, 50f)
                    drawOval(
                        color = Color(0xFF9CC6E8),
                        topLeft = androidx.compose.ui.geometry.Offset(65f, 30f),
                        size = androidx.compose.ui.geometry.Size(90f, 42f)
                    )
                    val tail = androidx.compose.ui.graphics.Path().apply {
                        moveTo(65f, 51f)
                        lineTo(35f, 32f)
                        lineTo(35f, 70f)
                        close()
                    }
                    drawPath(tail, Color(0xFF8CB7DC))
                    drawCircle(Color(0xFF28342E), 3f, androidx.compose.ui.geometry.Offset(138f, 43f))
                }
                WorksheetPicture.SHAPES -> {
                    drawRect(Color(0xFFB6D8C1), topLeft = androidx.compose.ui.geometry.Offset(55f, 26f), size = androidx.compose.ui.geometry.Size(48f, 48f))
                    val triangle = androidx.compose.ui.graphics.Path().apply {
                        moveTo(135f, 25f)
                        lineTo(170f, 74f)
                        lineTo(100f, 74f)
                        close()
                    }
                    drawPath(triangle, Color(0xFFF1CD93))
                    drawCircle(Color(0xFFB8C7E6), 25f, androidx.compose.ui.geometry.Offset(220f, 50f))
                }
                WorksheetPicture.OBJECTS -> {
                    val positions = listOf(55f, 110f, 165f, 220f)
                    positions.take(count.coerceIn(1, 4)).forEach { x ->
                        drawRect(Color(0xFFD9B99B), topLeft = androidx.compose.ui.geometry.Offset(x - 16f, 33f), size = androidx.compose.ui.geometry.Size(32f, 35f))
                        drawLine(Color(0xFF8E6A4F), androidx.compose.ui.geometry.Offset(x - 8f, 45f), androidx.compose.ui.geometry.Offset(x + 8f, 45f), strokeWidth = 2f)
                    }
                }
                WorksheetPicture.GROUPS -> {
                    val left = count.coerceIn(1, 6)
                    val right = secondaryCount.coerceIn(1, 6)
                    repeat(left) { i ->
                        drawCircle(Color(0xFFB7D8C1), 11f, androidx.compose.ui.geometry.Offset(55f + (i % 3) * 28f, 42f + (i / 3) * 28f))
                    }
                    repeat(right) { i ->
                        drawCircle(Color(0xFFF0C58B), 11f, androidx.compose.ui.geometry.Offset(165f + (i % 3) * 28f, 42f + (i / 3) * 28f))
                    }
                }
                WorksheetPicture.NONE -> Unit
            }
        }
    }
}

private fun buildWorksheetDrafts(config: WorksheetConfig): List<WorksheetQuestionDraft> {
    val topic = config.topic.lowercase()
    val numeracy = topic.containsAny("number", "count", "math", "add", "subtract", "गणित", "संख्या", "गिन")
    val literacy = topic.containsAny("letter", "vowel", "swar", "phonics", "reading", "word", "अक्षर", "स्वर", "मात्रा", "पढ़")
    val shapes = topic.containsAny("shape", "आकार", "circle", "square", "triangle")
    val animals = topic.containsAny("animal", "जानवर", "पशु", "bird", "पक्षी")

    val out = mutableListOf<WorksheetQuestionDraft>()
    val countBase = when (config.difficulty) {
        WorksheetDifficulty.EASY -> 3
        WorksheetDifficulty.MEDIUM -> 6
        WorksheetDifficulty.CHALLENGING -> 9
    }

    repeat(config.questionCount) { index ->
        val number = index + 1
        val mixedMode = config.template == WorksheetTemplate.MIXED
        val mode = if (!mixedMode) config.template else WorksheetTemplate.entries[index % 3]

        val draft = when {
            numeracy -> numeracyDraft(number, countBase + (index % 4), mode)
            literacy -> literacyDraft(number, index, mode)
            shapes -> shapeDraft(number, index, mode)
            animals -> animalDraft(number, index, mode)
            else -> genericDraft(number, index, config.topic, mode)
        }
        out += draft
    }

    return out
}

private fun numeracyDraft(
    number: Int,
    n: Int,
    mode: WorksheetTemplate
): WorksheetQuestionDraft {
    return when (number % 5) {
        1 -> WorksheetQuestionDraft(
            number,
            "चित्रों को गिनिए और संख्या लिखिए।",
            "Foundational Numeracy • Counting",
            WorksheetPicture.APPLES,
            pictureCount = n,
            answerLine = true
        )
        2 -> WorksheetQuestionDraft(
            number,
            "किस समूह में अधिक वस्तुएँ हैं? सही समूह पर गोला लगाइए।",
            "Foundational Numeracy • Number comparison",
            WorksheetPicture.GROUPS,
            pictureCount = n,
            secondaryCount = (n - 2).coerceAtLeast(1),
            answerLine = false
        )
        3 -> WorksheetQuestionDraft(
            number,
            "सही संख्या चुनिए: ${n + 1} वस्तुएँ हैं।",
            "Foundational Numeracy • Numeral identification",
            WorksheetPicture.OBJECTS,
            pictureCount = min(n + 1, 4),
            answerLine = true
        )
        4 -> WorksheetQuestionDraft(
            number,
            "जोड़कर संख्या लिखिए: ${n - 1} + 2 = ____",
            "Foundational Numeracy • Number operations",
            if (mode == WorksheetTemplate.PICTURE_RICH) WorksheetPicture.APPLES else WorksheetPicture.NONE,
            pictureCount = (n - 1).coerceAtLeast(1),
            answerLine = true
        )
        else -> WorksheetQuestionDraft(
            number,
            "घटाकर संख्या लिखिए: ${n} - 2 = ____",
            "Foundational Numeracy • Number operations",
            if (mode == WorksheetTemplate.PICTURE_RICH) WorksheetPicture.APPLES else WorksheetPicture.NONE,
            pictureCount = n.coerceIn(1, 8),
            answerLine = true
        )
    }
}

private fun literacyDraft(
    number: Int,
    index: Int,
    mode: WorksheetTemplate
): WorksheetQuestionDraft {
    val words = listOf(
        "आम",
        "अनार",
        "कमल",
        "गमला",
        "मछली",
        "फूल"
    )
    val word = words[index % words.size]
    val picture = when (word) {
        "आम", "अनार" -> WorksheetPicture.APPLES
        "मछली" -> WorksheetPicture.FISH
        "फूल" -> WorksheetPicture.FLOWER
        else -> WorksheetPicture.OBJECTS
    }

    return when (number % 4) {
        1 -> WorksheetQuestionDraft(
            number,
            "चित्र के नाम का पहला अक्षर चुनिए। शब्द: $word",
            "Foundational Literacy • Phonological awareness",
            picture,
            pictureCount = 1,
            answerLine = true
        )
        2 -> WorksheetQuestionDraft(
            number,
            "चित्र देखकर सही शब्द चुनिए।",
            "Foundational Literacy • Picture-supported reading",
            picture,
            pictureCount = 1,
            answerLine = true
        )
        3 -> WorksheetQuestionDraft(
            number,
            "खाली जगह में सही अक्षर लिखिए: ${word.first()} ____",
            "Foundational Literacy • Writing",
            if (mode == WorksheetTemplate.FILL_WRITE) WorksheetPicture.NONE else picture,
            pictureCount = 1,
            answerLine = true
        )
        else -> WorksheetQuestionDraft(
            number,
            "दिए गए शब्द को पढ़िए और चित्र से मिलाइए: $word",
            "Foundational Literacy • Decoding / word reading",
            picture,
            pictureCount = 1,
            answerLine = false
        )
    }
}

private fun shapeDraft(
    number: Int,
    index: Int,
    mode: WorksheetTemplate
): WorksheetQuestionDraft {
    val shape = listOf("वृत्त", "वर्ग", "त्रिभुज")[index % 3]
    return when (number % 3) {
        1 -> WorksheetQuestionDraft(
            number,
            "चित्र में $shape पहचानिए और उसका नाम लिखिए।",
            "Foundational Numeracy • Shapes and classification",
            WorksheetPicture.SHAPES,
            answerLine = true
        )
        2 -> WorksheetQuestionDraft(
            number,
            "सही आकार पर गोला लगाइए।",
            "Foundational Numeracy • Shape identification",
            WorksheetPicture.SHAPES,
            answerLine = false
        )
        else -> WorksheetQuestionDraft(
            number,
            "एक $shape बनाइए।",
            "Foundational Numeracy • Representation",
            if (mode == WorksheetTemplate.FILL_WRITE) WorksheetPicture.NONE else WorksheetPicture.SHAPES,
            answerLine = true
        )
    }
}

private fun animalDraft(
    number: Int,
    index: Int,
    mode: WorksheetTemplate
): WorksheetQuestionDraft {
    val prompts = listOf(
        "चित्र देखकर जानवर का नाम लिखिए।",
        "चित्र में कितने जानवर हैं? संख्या लिखिए।",
        "सही जानवर पर गोला लगाइए।"
    )
    return WorksheetQuestionDraft(
        number,
        prompts[index % prompts.size],
        "Foundational Literacy • Oral language / vocabulary",
        WorksheetPicture.ANIMALS,
        pictureCount = (index % 4) + 1,
        answerLine = index % 3 != 2
    )
}

private fun genericDraft(
    number: Int,
    index: Int,
    topic: String,
    mode: WorksheetTemplate
): WorksheetQuestionDraft {
    val pictures = listOf(
        WorksheetPicture.OBJECTS,
        WorksheetPicture.FLOWER,
        WorksheetPicture.FISH,
        WorksheetPicture.APPLES
    )
    val picture = pictures[index % pictures.size]

    val text = when (mode) {
        WorksheetTemplate.MATCH -> "चित्र देखकर $topic से जुड़ी वस्तु को पहचानिए और मिलाइए।"
        WorksheetTemplate.FILL_WRITE -> "चित्र देखकर उत्तर लिखिए। विषय: $topic"
        WorksheetTemplate.PICTURE_RICH -> "चित्र देखकर $topic से जुड़ी वस्तु को पहचानिए।"
        WorksheetTemplate.MIXED -> "चित्र देखकर उत्तर दीजिए। विषय: $topic"
    }

    return WorksheetQuestionDraft(
        number,
        text,
        "Foundational Literacy • Oral language / comprehension",
        picture,
        pictureCount = (index % 4) + 1,
        answerLine = true
    )
}

private fun inferFlnFocus(topic: String): String {
    val text = topic.lowercase()
    return when {
        text.containsAny("number", "count", "math", "गणित", "संख्या", "गिन") ->
            "Foundational Numeracy: counting, numeral identification, comparison and simple operations."
        text.containsAny("letter", "vowel", "swar", "phonics", "reading", "word", "अक्षर", "स्वर", "मात्रा", "पढ़") ->
            "Foundational Literacy: phonological awareness, picture-supported reading, decoding and writing."
        text.containsAny("shape", "आकार", "circle", "square", "triangle") ->
            "Foundational Numeracy: shape identification, classification and representation."
        else ->
            "Foundational Literacy: oral language, vocabulary, picture understanding and simple comprehension."
    }
}

private fun String.containsAny(vararg values: String): Boolean = values.any { contains(it) }

private fun buildWorksheetPdf(
    context: Context,
    config: WorksheetConfig,
    questions: List<WorksheetQuestion>
): ByteArray {
    val document = PdfDocument()
    val pageWidth = 595
    val pageHeight = 842
    val margin = 36
    val contentWidth = pageWidth - margin * 2

    val hindiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(31, 42, 35)
        textSize = 15f
        typeface = Typeface.DEFAULT
    }
    val santaliPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(39, 53, 42)
        textSize = 15f
        typeface = ResourcesCompat.getFont(context, R.font.noto_sans_ol_chiki_regular) ?: Typeface.DEFAULT
    }
    val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(91, 105, 96)
        textSize = 9f
        typeface = Typeface.DEFAULT
    }
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(40, 89, 67)
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
    }
    val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(24, 34, 29)
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
    }

    val perPage = 4
    var pageNumber = 1
    var index = 0

    while (index < questions.size) {
        val page = document.startPage(
            PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        )
        val canvas = page.canvas
        var y = margin

        canvas.drawText("LINGUABRIDGE", margin.toFloat(), y.toFloat() + 18f, titlePaint)
        y += 28
        canvas.drawText("Bilingual FLN Worksheet", margin.toFloat(), y.toFloat() + 18f, headerPaint)
        y += 28

        val topicLine = "${config.grade.label} • ${config.topic} • ${config.difficulty.label} • ${config.template.label}"
        canvas.drawText(topicLine.take(90), margin.toFloat(), y.toFloat() + 12f, smallPaint)
        y += 24

        canvas.drawLine(
            margin.toFloat(), y.toFloat(),
            (pageWidth - margin).toFloat(), y.toFloat(),
            smallPaint
        )
        y += 20

        val end = min(index + perPage, questions.size)
        for (qIndex in index until end) {
            val q = questions[qIndex]
            canvas.drawText("Q${q.number} • ${q.skill}", margin.toFloat(), y.toFloat(), smallPaint)
            y += 18

            y = drawWrappedText(canvas, q.hindi, hindiPaint, margin.toFloat(), y, contentWidth.toFloat(), 19f)
            y += 3
            y = drawWrappedText(canvas, q.santali, santaliPaint, margin.toFloat(), y, contentWidth.toFloat(), 19f)
            y += 7

            if (q.picture != WorksheetPicture.NONE) {
                drawPicture(
                    canvas = canvas,
                    picture = q.picture,
                    count = q.pictureCount,
                    secondaryCount = q.secondaryCount,
                    left = margin,
                    top = y,
                    width = 185,
                    height = 72
                )
                y += 82
            }

            if (q.answerLine) {
                canvas.drawLine(
                    margin.toFloat(), y.toFloat(),
                    (pageWidth - margin).toFloat(), y.toFloat(),
                    smallPaint
                )
                y += 17
            }

            y += 9
        }

        val footer = "NIPUN/FLN focus • Generated offline by LinguaBridge"
        canvas.drawText(footer, margin.toFloat(), (pageHeight - 22).toFloat(), smallPaint)

        document.finishPage(page)
        index = end
        pageNumber++
    }

    val output = java.io.ByteArrayOutputStream()
    document.writeTo(output)
    document.close()
    return output.toByteArray()
}

private fun drawWrappedText(
    canvas: Canvas,
    text: String,
    paint: Paint,
    x: Float,
    startY: Int,
    width: Float,
    lineHeight: Float
): Int {
    var y = startY
    var remaining = text.trim()

    while (remaining.isNotEmpty()) {
        val count = paint.breakText(remaining, true, width, null)
        if (count <= 0) break
        val line = remaining.substring(0, count).trimEnd()
        canvas.drawText(line, x, y.toFloat(), paint)
        remaining = remaining.substring(count).trimStart()
        y += lineHeight.toInt()
    }

    return y
}

private fun drawPicture(
    canvas: Canvas,
    picture: WorksheetPicture,
    count: Int,
    secondaryCount: Int,
    left: Int,
    top: Int,
    width: Int,
    height: Int
) {
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = android.graphics.Color.rgb(78, 106, 88)
    }

    canvas.drawRoundRect(
        RectF(left.toFloat(), top.toFloat(), (left + width).toFloat(), (top + height).toFloat()),
        12f,
        12f,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            style = Paint.Style.FILL
        }
    )
    canvas.drawRoundRect(
        RectF(left.toFloat(), top.toFloat(), (left + width).toFloat(), (top + height).toFloat()),
        12f,
        12f,
        strokePaint
    )

    fun circle(cx: Float, cy: Float, r: Float, color: Int) {
        fillPaint.color = color
        canvas.drawCircle(cx, cy, r, fillPaint)
    }

    when (picture) {
        WorksheetPicture.APPLES -> {
            repeat(count.coerceIn(1, 8)) { i ->
                val cx = left + 25f + (i % 4) * 40f
                val cy = top + 26f + (i / 4) * 32f
                circle(cx, cy, 10f, android.graphics.Color.rgb(245, 176, 162))
                strokePaint.color = android.graphics.Color.rgb(90, 118, 76)
                canvas.drawLine(cx, cy - 9f, cx + 3f, cy - 14f, strokePaint)
            }
        }
        WorksheetPicture.ANIMALS -> {
            repeat(count.coerceIn(1, 4)) { i ->
                val cx = left + 34f + i * 40f
                val cy = top + 38f
                circle(cx, cy, 12f, android.graphics.Color.rgb(241, 214, 168))
                circle(cx - 10f, cy - 12f, 5f, android.graphics.Color.rgb(241, 214, 168))
                circle(cx + 10f, cy - 12f, 5f, android.graphics.Color.rgb(241, 214, 168))
                circle(cx - 4f, cy - 1f, 1.8f, android.graphics.Color.DKGRAY)
                circle(cx + 4f, cy - 1f, 1.8f, android.graphics.Color.DKGRAY)
            }
        }
        WorksheetPicture.FLOWER -> {
            val cx = left + 90f
            val cy = top + 32f
            circle(cx - 13, cy, 12f, android.graphics.Color.rgb(243, 179, 196))
            circle(cx + 13, cy, 12f, android.graphics.Color.rgb(243, 179, 196))
            circle(cx, cy - 13, 12f, android.graphics.Color.rgb(243, 179, 196))
            circle(cx, cy + 13, 12f, android.graphics.Color.rgb(243, 179, 196))
            circle(cx, cy, 7f, android.graphics.Color.rgb(245, 210, 106))
            strokePaint.color = android.graphics.Color.rgb(78, 138, 85)
            canvas.drawLine(cx, cy + 19f, cx, top + 64f, strokePaint)
        }
        WorksheetPicture.FISH -> {
            fillPaint.color = android.graphics.Color.rgb(156, 198, 232)
            canvas.drawOval(RectF(left + 55f, top + 18f, left + 135f, top + 54f), fillPaint)
            val tail = Path().apply {
                moveTo(left + 55f, top + 36f)
                lineTo(left + 28f, top + 18f)
                lineTo(left + 28f, top + 54f)
                close()
            }
            fillPaint.color = android.graphics.Color.rgb(140, 183, 220)
            canvas.drawPath(tail, fillPaint)
            circle(left + 116f, top + 30f, 2f, android.graphics.Color.DKGRAY)
        }
        WorksheetPicture.SHAPES -> {
            fillPaint.color = android.graphics.Color.rgb(182, 216, 193)
            canvas.drawRect(left + 18f, top + 15f, left + 58f, top + 55f, fillPaint)
            fillPaint.color = android.graphics.Color.rgb(241, 205, 147)
            val triangle = Path().apply {
                moveTo(left + 96f, top + 14f)
                lineTo(left + 124f, top + 56f)
                lineTo(left + 68f, top + 56f)
                close()
            }
            canvas.drawPath(triangle, fillPaint)
            circle(left + 150f, top + 35f, 20f, android.graphics.Color.rgb(184, 199, 230))
        }
        WorksheetPicture.OBJECTS -> {
            repeat(count.coerceIn(1, 4)) { i ->
                val cx = left + 26f + i * 38f
                fillPaint.color = android.graphics.Color.rgb(217, 185, 155)
                canvas.drawRect(cx - 12f, top + 20f, cx + 12f, top + 52f, fillPaint)
            }
        }
        WorksheetPicture.GROUPS -> {
            repeat(count.coerceIn(1, 6)) { i ->
                circle(left + 34f + (i % 3) * 22f, top + 25f + (i / 3) * 22f, 7f, android.graphics.Color.rgb(183, 216, 193))
            }
            repeat(secondaryCount.coerceIn(1, 6)) { i ->
                circle(left + 108f + (i % 3) * 22f, top + 25f + (i / 3) * 22f, 7f, android.graphics.Color.rgb(240, 197, 139))
            }
        }
        WorksheetPicture.NONE -> Unit
    }
}

