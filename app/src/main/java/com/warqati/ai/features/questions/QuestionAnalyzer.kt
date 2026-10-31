package com.warqati.ai.features.questions

/** Lightweight on-device question segmentation. It does not invent answers or alter source text. */
data class EditableQuestion(
    val number: Int,
    val prompt: String,
    val answer: String = "",
    val options: List<String> = emptyList()
)

object QuestionAnalyzer {
    private val questionStart = Regex(
        "(?i)^\\s*(?:(?:السؤال|سؤال)\\s*(?:رقم\\s*)?(?:[\\d٠-٩]+|الأول|الأولى|الثاني|الثانية|الثالث|الثالثة|الرابع|الرابعة|الخامس|الخامسة|السادس|السابعة|السابع|الثامن|الثامنة|التاسع|التاسعة|العاشر|العاشرة)?\\s*[:：.)-]?|س\\s*[\\d٠-٩]+\\s*[:：.)-]?|[\\[(]?[\\d٠-٩]+[\\])]?[.、):：-])\\s*(.*)$"
    )
    private val optionStart = Regex("^\\s*(?:(?:(?:\\(|\\[)?\\s*[أابجدهA-Da-d١-٤1-4](?:\\)|\\]|）|[.、:：-]))|(?:الخيار|اختيار)\\s*[١٢٣٤1-4])\\s*(.*)$")

    fun analyze(rawText: String): List<EditableQuestion> {
        val normalized = rawText.replace('\u00A0', ' ').replace("\r", "")
        val lines = normalized.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        val questions = mutableListOf<MutableQuestion>()
        var current: MutableQuestion? = null
        var preamble = mutableListOf<String>()

        fun startQuestion(content: String) {
            current?.let { questions.add(it) }
            current = MutableQuestion(questions.size + 1, content.trim())
        }

        for (line in lines) {
            val match = questionStart.matchEntire(line)
            if (match != null) {
                val content = match.groupValues.getOrNull(1).orEmpty()
                startQuestion(content)
                continue
            }
            val option = optionStart.matchEntire(line)
            if (option != null && current != null) {
                current!!.options.add(line)
                continue
            }
            if (current == null) preamble.add(line)
            else current!!.prompt = listOf(current!!.prompt, line).filter { it.isNotBlank() }.joinToString("\n")
        }
        current?.let { questions.add(it) }

        // If numbering could not be recognized, preserve the text as one editable unit.
        if (questions.isEmpty()) {
            val body = (preamble + lines).distinct().joinToString("\n")
            return listOf(EditableQuestion(1, body))
        }
        if (preamble.isNotEmpty()) {
            val heading = preamble.joinToString("\n")
            questions.first().prompt = if (questions.first().prompt.isBlank()) heading else "$heading\n\n${questions.first().prompt}"
        }
        return questions.map { EditableQuestion(it.number, it.prompt, options = it.options.toList()) }
    }

    private data class MutableQuestion(
        val number: Int,
        var prompt: String,
        val options: MutableList<String> = mutableListOf()
    )
}
