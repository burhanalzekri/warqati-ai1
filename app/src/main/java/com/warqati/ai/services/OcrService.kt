package com.warqati.ai.services

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

/** OCR facade. Language data is installed in app-private files/tesseract/tessdata. */
class OcrService(private val context: Context) {
    fun readPages(pages: List<Bitmap>): String {
        val root = File(context.filesDir, "tesseract")
        val data = File(root, "tessdata")
        val languages = buildList {
            if (File(data, "ara.traineddata").exists()) add("ara")
            if (File(data, "eng.traineddata").exists()) add("eng")
        }.joinToString("+")
        if (languages.isBlank()) return ""

        val engine = TessBaseAPI()
        try {
            if (!engine.init(root.absolutePath, languages)) return ""
            return pages.mapIndexed { index, bitmap ->
                engine.setImage(bitmap)
                "صفحة ${index + 1}\n${engine.utF8Text.orEmpty()}"
            }.joinToString("\n\n")
        } finally { engine.recycle() }
    }
}
