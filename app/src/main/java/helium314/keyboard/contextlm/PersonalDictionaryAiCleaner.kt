package helium314.keyboard.contextlm

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.provider.UserDictionary
import helium314.keyboard.latin.ai.OfflineAiLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * Batch Personal Dictionary auditor powered by LeanType's OfflineAiLoader (LeanType-Offline-AI-Plugin).
 * Chunks UserDictionary.Words.CONTENT_URI entries into batches of 40 words, prompts the local LLM
 * for strict JSON-only audit decisions, and applies confirmed deletions/lowercasing via ContentResolver.
 */
object PersonalDictionaryAiCleaner {
    private const val BATCH_SIZE = 40

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    enum class CleanAction {
        DELETE,
        LOWERCASE
    }

    data class DictEntry(
        val id: Long,
        val word: String,
        val shortcut: String?,
        val frequency: Int,
        val locale: String?
    )

    @Serializable
    private data class AiAuditItem(
        val word: String,
        val action: String, // "delete" or "lowercase"
        val reason: String = ""
    )

    data class CleanupProposal(
        val entry: DictEntry,
        val action: CleanAction,
        val newWord: String?,
        val reason: String
    )

    sealed class AuditResult {
        data class Ready(val proposals: List<CleanupProposal>) : AuditResult()
        data class Error(val message: String) : AuditResult()
    }

    fun readUserDictionaryEntries(context: Context, localeFilter: Locale?): List<DictEntry> {
        val resolver = context.contentResolver
        val projection = arrayOf(
            UserDictionary.Words._ID,
            UserDictionary.Words.WORD,
            UserDictionary.Words.SHORTCUT,
            UserDictionary.Words.FREQUENCY,
            UserDictionary.Words.LOCALE
        )
        val (selection, args) = if (localeFilter == null) {
            "${UserDictionary.Words.LOCALE} IS NULL" to null
        } else {
            "${UserDictionary.Words.LOCALE}=?" to arrayOf(localeFilter.toString())
        }

        val entries = ArrayList<DictEntry>()
        resolver.query(UserDictionary.Words.CONTENT_URI, projection, selection, args, "${UserDictionary.Words._ID} ASC")
            ?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(UserDictionary.Words._ID)
                val wordIdx = cursor.getColumnIndexOrThrow(UserDictionary.Words.WORD)
                val shortcutIdx = cursor.getColumnIndexOrThrow(UserDictionary.Words.SHORTCUT)
                val freqIdx = cursor.getColumnIndexOrThrow(UserDictionary.Words.FREQUENCY)
                val locIdx = cursor.getColumnIndexOrThrow(UserDictionary.Words.LOCALE)

                while (cursor.moveToNext()) {
                    entries.add(
                        DictEntry(
                            id = cursor.getLong(idIdx),
                            word = cursor.getString(wordIdx) ?: continue,
                            shortcut = cursor.getString(shortcutIdx),
                            frequency = cursor.getInt(freqIdx),
                            locale = cursor.getString(locIdx)
                        )
                    )
                }
            }
        return entries
    }

    suspend fun auditDictionary(context: Context, localeFilter: Locale?): AuditResult =
        withContext(Dispatchers.IO) {
            if (!OfflineAiLoader.hasPlugin(context)) {
                return@withContext AuditResult.Error("Offline AI plugin is not loaded. Load it in Settings -> AI Integration first.")
            }
            val provider = OfflineAiLoader.getProvider(context)
                ?: return@withContext AuditResult.Error("Unable to initialize Offline AI provider.")

            if (!provider.isAvailable()) {
                return@withContext AuditResult.Error("Offline AI provider is unavailable.")
            }
            if (!provider.isModelLoaded()) {
                return@withContext AuditResult.Error("No GGUF model is loaded in Offline AI settings.")
            }

            val entries = readUserDictionaryEntries(context, localeFilter)
            if (entries.isEmpty()) {
                return@withContext AuditResult.Ready(emptyList())
            }

            val proposals = ArrayList<CleanupProposal>()
            val entryByWord = entries.associateBy { it.word }

            for (chunk in entries.chunked(BATCH_SIZE)) {
                val prompt = buildStrictJsonPrompt(chunk.map { it.word })
                val rawResponse = provider.generate(
                    prompt = prompt,
                    params = mapOf("temperature" to 0.0f, "max_tokens" to 512)
                )
                val items = parseJsonArray(rawResponse)
                for (item in items) {
                    val entry = entryByWord[item.word] ?: continue
                    when (item.action.lowercase(Locale.ROOT).trim()) {
                        "delete" -> proposals.add(
                            CleanupProposal(entry, CleanAction.DELETE, null, item.reason)
                        )
                        "lowercase" -> {
                            val lower = entry.word.lowercase(Locale.ROOT)
                            if (lower != entry.word) {
                                proposals.add(
                                    CleanupProposal(entry, CleanAction.LOWERCASE, lower, item.reason)
                                )
                            }
                        }
                    }
                }
            }
            AuditResult.Ready(proposals)
        }

    suspend fun applyProposals(context: Context, proposals: List<CleanupProposal>): Int =
        withContext(Dispatchers.IO) {
            val resolver: ContentResolver = context.contentResolver
            var applied = 0
            for (proposal in proposals) {
                val where = "${UserDictionary.Words._ID}=?"
                val args = arrayOf(proposal.entry.id.toString())
                when (proposal.action) {
                    CleanAction.DELETE -> {
                        applied += resolver.delete(UserDictionary.Words.CONTENT_URI, where, args)
                    }
                    CleanAction.LOWERCASE -> {
                        val lower = proposal.newWord ?: continue
                        val values = ContentValues().apply {
                            put(UserDictionary.Words.WORD, lower)
                        }
                        applied += resolver.update(UserDictionary.Words.CONTENT_URI, values, where, args)
                    }
                }
            }
            applied
        }

    private fun buildStrictJsonPrompt(words: List<String>): String {
        val wordList = words.joinToString(separator = ", ") { "\"${it.replace("\"", "\\\"")}\"" }
        return """
            You are auditing an Android keyboard personal dictionary.
            Inspect these words: [$wordList]
            Flag ONLY:
            1. Obvious typos or garbage tokens -> "action": "delete"
            2. Unnecessary mid-sentence capitalizations of ordinary words (e.g. "The", "Because", "Always") -> "action": "lowercase"
            PRESERVE proper nouns, names, technical acronyms (API, DNS, URL, GGUF), and intentional slang.
            Respond with ONLY a valid JSON array (no markdown, no explanation outside JSON):
            [{"word":"...","action":"delete|lowercase","reason":"short reason"}]
            If all words are valid, respond with [].
        """.trimIndent()
    }

    private fun parseJsonArray(raw: String): List<AiAuditItem> {
        val trimmed = raw.trim()
        val start = trimmed.indexOf('[')
        val end = trimmed.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val jsonSlice = trimmed.substring(start, end + 1)
        return try {
            json.decodeFromString<List<AiAuditItem>>(jsonSlice)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
