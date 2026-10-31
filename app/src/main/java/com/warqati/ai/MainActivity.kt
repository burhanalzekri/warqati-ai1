package com.warqati.ai

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import com.warqati.ai.services.OcrService
import com.warqati.ai.features.questions.EditableQuestion
import com.warqati.ai.features.questions.QuestionAnalyzer

class MainActivity : ComponentActivity() {
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) tts?.language = Locale("ar")
        }
        setContent {
            WarqatiTheme {
                WarqatiScreen(
                    onSpeak = { value ->
                        if (value.isNotBlank()) tts?.speak(value, TextToSpeech.QUEUE_FLUSH, null, "warqati-tts")
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
private fun WarqatiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF087F70),
            secondary = Color(0xFF13A58F),
            background = Color(0xFFF3F7F6),
            surface = Color.White
        ), content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WarqatiScreen(onSpeak: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var extractedText by remember { mutableStateOf("") }
    var documentName by remember { mutableStateOf("مستند جديد") }
    var status by remember { mutableStateOf("ارفع صورة أو ملف PDF للبدء") }
    var isBusy by remember { mutableStateOf(false) }
    var pagesCount by remember { mutableIntStateOf(0) }
    val questions = remember { mutableStateListOf<EditableQuestion>() }
    var selectedQuestion by remember { mutableIntStateOf(-1) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            isBusy = true
            status = "جارٍ فتح المستند وتحضير صفحاته..."
            try {
                val result = withContext(Dispatchers.IO) {
                    val mime = context.contentResolver.getType(uri).orEmpty()
                    val bitmaps = if (mime == "application/pdf" || uri.toString().substringBefore('?').endsWith(".pdf", true)) {
                        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: error("تعذر فتح PDF")
                        descriptor.use { fd ->
                            PdfRenderer(fd).use { renderer ->
                                buildList {
                                    for (index in 0 until renderer.pageCount) {
                                        renderer.openPage(index).use { page ->
                                            val factor = minOf(2f, 1600f / page.width)
                                            val bitmap = Bitmap.createBitmap(
                                                (page.width * factor).toInt().coerceAtLeast(1),
                                                (page.height * factor).toInt().coerceAtLeast(1),
                                                Bitmap.Config.ARGB_8888
                                            )
                                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                            add(bitmap)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                            ?: error("تعذر قراءة الصورة")
                        listOf(bitmap)
                    }
                    val text = OcrService(context).readPages(bitmaps)
                    text to bitmaps.size
                }
                extractedText = result.first
                questions.clear()
                pagesCount = result.second
                documentName = uri.lastPathSegment?.substringAfterLast('/') ?: "مستند ورقتي"
                status = if (extractedText.isBlank()) "تم فتح الملف. أضف ملفات اللغة لمحرك OCR كما هو موضح في README." else "اكتملت معالجة ${result.second} صفحة"
            } catch (error: Exception) {
                status = "تعذرت المعالجة: ${error.localizedMessage ?: "خطأ غير معروف"}"
            } finally { isBusy = false }
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val phrase = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!phrase.isNullOrBlank()) {
            if (selectedQuestion in questions.indices) {
                val old = questions[selectedQuestion]
                questions[selectedQuestion] = old.copy(answer = listOf(old.answer, phrase).filter { it.isNotBlank() }.joinToString(" "))
            } else {
                extractedText = if (extractedText.isBlank()) phrase else "$extractedText\n$phrase"
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        val pdf = PdfDocument()
                        val width = 595
                        val height = 842
                        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK; textSize = 13f }
                        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = android.graphics.Color.rgb(8, 127, 112); textSize = 18f; isFakeBoldText = true
                        }
                        val finalText = if (questions.isNotEmpty()) questions.joinToString("\n\n") { q ->
                            "السؤال ${q.number}:\n${q.prompt}" +
                                (if (q.options.isNotEmpty()) "\n${q.options.joinToString("\n")}" else "") +
                                (if (q.answer.isNotBlank()) "\nالإجابة: ${q.answer}" else "\nالإجابة: __________________")
                        } else extractedText
                        val lines = finalText.lines().flatMap { line ->
                            if (line.isEmpty()) listOf("") else line.chunked(75)
                        }
                        var pageNo = 1
                        var page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, pageNo).create())
                        var y = 55f
                        page.canvas.drawText(documentName.take(50), 40f, 35f, titlePaint)
                        for (line in lines) {
                            if (y > height - 45f) {
                                pdf.finishPage(page)
                                pageNo++
                                page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, pageNo).create())
                                y = 55f
                            }
                            val x = (width - 40f - paint.measureText(line)).coerceAtLeast(40f)
                            page.canvas.drawText(line, x, y, paint)
                            y += 22f
                        }
                        pdf.finishPage(page)
                        pdf.writeTo(output)
                        pdf.close()
                    } ?: error("تعذر إنشاء ملف PDF")
                }
                status = "تم تصدير PDF"
            } catch (error: Exception) { status = "فشل التصدير: ${error.localizedMessage}" }
        }
    }

    Scaffold(topBar = {
        CenterAlignedTopAppBar(title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ورقتي AI", fontWeight = FontWeight.Bold)
                Text("مستنداتك أصبحت تفاعلية", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        })
    }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE4F3EF))) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("حوّل ورقتك إلى مستند ذكي", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF075E53))
                    Spacer(Modifier.height(5.dp))
                    Text("استورد صورة أو PDF، استخرج النص، حرّره، أضف إجابتك، ثم صدّر النتيجة.", fontSize = 13.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { importLauncher.launch(arrayOf("image/*", "application/pdf")) }, enabled = !isBusy, modifier = Modifier.weight(1f)) {
                    Text("＋ استيراد ورقة")
                }
                OutlinedButton(onClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-YE")
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "تحدث لإضافة إجابتك")
                    }
                    try { speechLauncher.launch(intent) } catch (_: Exception) { status = "التعرف الصوتي غير متاح على الجهاز" }
                }, modifier = Modifier.weight(1f)) { Text("🎙 إجابة صوتية") }
            }
            OutlinedButton(onClick = { onSpeak(extractedText) }, enabled = extractedText.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("🔊 استمع إلى النص")
            }
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("حالة المستند", fontWeight = FontWeight.Bold)
                    Text(documentName, color = MaterialTheme.colorScheme.primary, fontSize = 13.sp)
                    if (pagesCount > 0) Text("عدد الصفحات: $pagesCount", fontSize = 12.sp)
                    Spacer(Modifier.height(5.dp))
                    Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (isBusy) { Spacer(Modifier.height(9.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                }
            }
            Text("النص المستخرج والإجابات", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            OutlinedTextField(
                value = extractedText,
                onValueChange = { extractedText = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp),
                placeholder = { Text("سيظهر النص هنا... ويمكنك الكتابة والتعديل مباشرة") },
                minLines = 10,
                textStyle = LocalTextStyle.current.copy(fontSize = 15.sp, textAlign = TextAlign.Start)
            )
            Button(
                onClick = {
                    questions.clear()
                    questions.addAll(QuestionAnalyzer.analyze(extractedText))
                    selectedQuestion = -1
                    status = if (questions.isEmpty()) "لم يتم العثور على أسئلة" else "تم تحليل ${questions.size} سؤالاً — راجع التقسيم وعدّله"
                },
                enabled = extractedText.isNotBlank() && !isBusy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("✦ تحليل الأسئلة وتقسيمها") }

            if (questions.isNotEmpty()) {
                Text("الأسئلة المكتشفة (${questions.size})", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("اضغط على بطاقة السؤال لتحديدها، ثم استخدم زر الإجابة الصوتية لإضافة الكلام إلى إجابته.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                questions.forEachIndexed { index, question ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (selectedQuestion == index) Color(0xFFE5F4F0) else Color.White
                        ),
                        onClick = { selectedQuestion = index }
                    ) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text("السؤال ${index + 1}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                TextButton(onClick = { selectedQuestion = index }) { Text(if (selectedQuestion == index) "محدد ✓" else "تحديد للإجابة") }
                            }
                            OutlinedTextField(
                                value = question.prompt,
                                onValueChange = { value -> questions[index] = questions[index].copy(prompt = value) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("نص السؤال — قابل للتعديل") },
                                minLines = 2
                            )
                            if (question.options.isNotEmpty()) {
                                Text("الاختيارات", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                question.options.forEach { Text(it, fontSize = 13.sp) }
                            }
                            OutlinedTextField(
                                value = question.answer,
                                onValueChange = { value -> questions[index] = questions[index].copy(answer = value) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("الإجابة") },
                                minLines = 2
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onSpeak(question.prompt) }) { Text("🔊 قراءة السؤال") }
                                TextButton(onClick = { selectedQuestion = index; status = "السؤال ${index + 1} محدد — اضغط إجابة صوتية" }) { Text("🎙 أجب صوتياً") }
                                TextButton(onClick = { questions.removeAt(index); selectedQuestion = -1 }) { Text("حذف") }
                            }
                        }
                    }
                }
                OutlinedButton(onClick = {
                    val merged = questions.joinToString("\n\n") { q -> "السؤال ${q.number}:\n${q.prompt}\nالإجابة: ${q.answer}" }
                    extractedText = merged
                    status = "تم دمج الأسئلة والإجابات في النص الرئيسي"
                }, modifier = Modifier.fillMaxWidth()) { Text("تحديث النص الرئيسي من الأسئلة والإجابات") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { extractedText = "" }, modifier = Modifier.weight(1f)) { Text("مسح") }
                Button(onClick = { exportLauncher.launch("warqati-result.pdf") }, enabled = extractedText.isNotBlank(), modifier = Modifier.weight(1f)) {
                    Text("تصدير PDF")
                }
            }
            Text("الإصدار التأسيسي 0.1 • سيتم إضافة تقسيم الأسئلة والحفظ وإعادة بناء التنسيق في المراحل التالية.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}
