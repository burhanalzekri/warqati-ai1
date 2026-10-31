package com.warqati.ai.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.warqati.ai.features.questions.EditableQuestion
import org.json.JSONArray
import org.json.JSONObject

data class SavedDocument(
    val id: Long,
    val name: String,
    val text: String,
    val pageCount: Int,
    val questions: List<EditableQuestion>,
    val updatedAt: Long
)

class DocumentStore(context: Context) :
    SQLiteOpenHelper(context, "warqati_documents.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE documents (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                content TEXT NOT NULL,
                page_count INTEGER NOT NULL DEFAULT 0,
                questions_json TEXT NOT NULL DEFAULT '[]',
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {}

    private fun encodeQuestions(
        questions: List<EditableQuestion>
    ): String {
        val array = JSONArray()

        questions.forEach { question ->
            val item = JSONObject()
            item.put("number", question.number)
            item.put("prompt", question.prompt)
            item.put("answer", question.answer)

            val options = JSONArray()
            question.options.forEach { options.put(it) }

            item.put("options", options)
            array.put(item)
        }

        return array.toString()
    }

    private fun decodeQuestions(json: String): List<EditableQuestion> {
        return try {
            val array = JSONArray(json)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val optionsArray = item.optJSONArray("options")
                        ?: JSONArray()

                    val options = buildList {
                        for (j in 0 until optionsArray.length()) {
                            add(optionsArray.getString(j))
                        }
                    }

                    add(
                        EditableQuestion(
                            number = item.optInt("number", i + 1),
                            prompt = item.optString("prompt"),
                            answer = item.optString("answer"),
                            options = options
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(
        id: Long,
        name: String,
        text: String,
        pageCount: Int,
        questions: List<EditableQuestion>
    ): Long {
        val db = writableDatabase
        val now = System.currentTimeMillis()

        val values = ContentValues().apply {
            put("name", name)
            put("content", text)
            put("page_count", pageCount)
            put("questions_json", encodeQuestions(questions))
            put("updated_at", now)
        }

        if (id > 0) {
            val updated = db.update(
                "documents",
                values,
                "id = ?",
                arrayOf(id.toString())
            )

            if (updated > 0) return id
        }

        return db.insertOrThrow("documents", null, values)
    }

    @Synchronized
    fun getAll(): List<SavedDocument> {
        val result = mutableListOf<SavedDocument>()

        readableDatabase.rawQuery(
            "SELECT * FROM documents ORDER BY updated_at DESC",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    SavedDocument(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        text = cursor.getString(cursor.getColumnIndexOrThrow("content")),
                        pageCount = cursor.getInt(cursor.getColumnIndexOrThrow("page_count")),
                        questions = decodeQuestions(
                            cursor.getString(cursor.getColumnIndexOrThrow("questions_json"))
                        ),
                        updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
                    )
                )
            }
        }

        return result
    }

    @Synchronized
    fun delete(id: Long): Int {
        return writableDatabase.delete(
            "documents",
            "id = ?",
            arrayOf(id.toString())
        )
    }
}
