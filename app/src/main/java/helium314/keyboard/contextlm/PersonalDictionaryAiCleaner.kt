package helium314.keyboard.contextlm

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.provider.UserDictionary
import helium314.keyboard.latin.ai.OfflineAiLoader
import helium314.keyboard.latin.utils.ProofreadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import java.util.Locale

object PersonalDictionaryAiCleaner {
    private const val BATCH_SIZE = 10
    private val mutex = Mutex()

    enum class CleanAction { DELETE, LOWERCASE, CAPITALIZE }
    data class DictEntry(val id: Long, val word: String, val shortcut: String?, val frequency: Int, val locale: String?)
    @Serializable private data class AiAuditItem(val word: String, val action: String, val newWord: String? = null, val reason: String = "")
    data class CleanupProposal(val entry: DictEntry, val action: CleanAction, val newWord: String?, val reason: String)

    sealed class AuditResult {
        data class Ready(val proposals: List<CleanupProposal>) : AuditResult()
        data class Error(val message: String) : AuditResult()
    }

    private val COMMON_CAPITALIZED_ACCIDENTS = setOf(
        "the", "and", "but", "for", "nor", "or", "yet", "so", "in", "on", "at", "to", "from", "by",
        "with", "about", "against", "between", "into", "through", "during", "before", "after", "above",
        "below", "under", "again", "further", "then", "once", "here", "there", "when", "where", "why",
        "how", "all", "any", "both", "each", "few", "more", "most", "other", "some", "such", "only",
        "own", "same", "than", "too", "very", "just", "now", "also", "because", "if", "while", "this",
        "that", "these", "those", "what", "which", "who", "whom", "whose", "applicable", "running", "going"
    )

    fun readUserDictionaryEntries(context: Context, localeFilter: Locale?): List<DictEntry> {
        val resolver = context.contentResolver
        val projection = arrayOf(UserDictionary.Words._ID, UserDictionary.Words.WORD, UserDictionary.Words.SHORTCUT, UserDictionary.Words.FREQUENCY, UserDictionary.Words.LOCALE)
        val (selection, args) = if (localeFilter == null) "${UserDictionary.Words.LOCALE} IS NULL" to null else "${UserDictionary.Words.LOCALE}=?" to arrayOf(localeFilter.toString())
        val entries = ArrayList<DictEntry>()
        resolver.query(UserDictionary.Words.CONTENT_URI, projection, selection, args, "${UserDictionary.Words._ID} ASC")?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(UserDictionary.Words._ID)
            val wordIdx = cursor.getColumnIndexOrThrow(UserDictionary.Words.WORD)
            val shortcutIdx = cursor.getColumnIndexOrThrow(UserDictionary.Words.SHORTCUT)
            val freqIdx = cursor.getColumnIndexOrThrow(UserDictionary.Words.FREQUENCY)
            val locIdx = cursor.getColumnIndexOrThrow(UserDictionary.Words.LOCALE)
            while (cursor.moveToNext()) {
                entries.add(DictEntry(cursor.getLong(idIdx), cursor.getString(wordIdx) ?: continue, cursor.getString(shortcutIdx), cursor.getInt(freqIdx), cursor.getString(locIdx)))
            }
        }
        return entries
    }

    suspend fun auditDictionary(context: Context, localeFilter: Locale?, onProgress: ((Int, Int) -> Unit)? = null): AuditResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                withTimeout(60000) {
                    if (!OfflineAiLoader.hasPlugin(context)) return@withTimeout AuditResult.Error("Offline AI plugin is not loaded.")
                    val provider = OfflineAiLoader.getProvider(context) ?: return@withTimeout AuditResult.Error("Unable to initialize Offline AI provider.")
                    if (!provider.isAvailable()) return@withTimeout AuditResult.Error("Offline AI provider is unavailable.")

                    if (!provider.isModelLoaded()) {
                        val p = ProofreadService(context).getModelPath()
                        if (p.isNullOrBlank() || !provider.loadModel(context, p, 4, 4096)) return@withTimeout AuditResult.Error("Failed to load model in AI plugin.")
                    }

                    val entries = readUserDictionaryEntries(context, localeFilter).filter { !it.word.contains("@") && !it.word.contains("'") && !it.word.contains("-") }
                    if (entries.isEmpty()) return@withTimeout AuditResult.Ready(emptyList())

                    val proposals = ArrayList<CleanupProposal>()
                    val entryByWord = entries.associateBy { it.word }

                    val chunks = entries.chunked(BATCH_SIZE); chunks.forEachIndexed { idx, chunk -> onProgress?.invoke(idx + 1, chunks.size);
                        val wordsStr = chunk.joinToString(", ") { it.word }
                        val prompt = "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\nYou clean a user's personal keyboard dictionary. Rules:\n1. NEVER delete or lowercase human names (Edelman, Darin, Karin, Joshua), brands/apps (Samsung, YouTube, PS5, FUTO, Portainer), places (Texas, Olympia), or technical terms/acronyms (DNS, WiFi, OLED).\n2. If a human name, place, brand, or acronym is lowercase (e.g. karin, texas, samsung, youtube, dns, ps5), output 'capitalize: word -> ProperForm'.\n3. Only 'delete' obvious keyboard typos/gibberish (e.g. teh, hte, adn, theres) or common function words (any, there).\n4. Only 'lowercase' common non-proper English words accidentally capitalized at sentence start (e.g. The, Applicable, Running).\nOutput ONLY lines in format 'action: word' or 'capitalize: word -> Fixed'.<|eot_id|><|start_header_id|>user<|end_header_id|>\n\nteh, The, Edelman, Darin, karin, samsung, youtube, texas, dns, ps5, Applicable, oled<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\ndelete: teh\nlowercase: The\ncapitalize: karin -> Karin\ncapitalize: samsung -> Samsung\ncapitalize: youtube -> YouTube\ncapitalize: texas -> Texas\ncapitalize: dns -> DNS\ncapitalize: ps5 -> PS5\nlowercase: Applicable<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n$wordsStr<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"

                        OfflineAiLoader.isBusy = true
                        val rawResponse = try {
                            provider.generate(prompt = prompt, params = mapOf("temperature" to 0.0, "max_tokens" to 180))
                        } catch (e: Exception) {
                            android.util.Log.e("DictCleaner", "Generate failed", e)
                            ""
                        } finally {
                            OfflineAiLoader.isBusy = false
                        }

                        for (item in parseJsonItems(rawResponse)) {
                            val entry = entryByWord[item.word] ?: continue
                            val lowerCheck = entry.word.lowercase(Locale.ROOT)
                            val looksLikeProperName = entry.word.length >= 3 && entry.word[0].isUpperCase() && lowerCheck !in COMMON_CAPITALIZED_ACCIDENTS
                            when (item.action.lowercase(Locale.ROOT).trim()) {
                                "delete" -> {
                                    if (!looksLikeProperName && !entry.word.contains("@")) {
                                        proposals.add(CleanupProposal(entry, CleanAction.DELETE, null, item.reason))
                                    }
                                }
                                "lowercase" -> {
                                    if (!looksLikeProperName && lowerCheck != entry.word) {
                                        proposals.add(CleanupProposal(entry, CleanAction.LOWERCASE, lowerCheck, item.reason))
                                    }
                                }
                                "capitalize" -> {
                                    val target = item.newWord?.takeIf { it.isNotBlank() && it.equals(entry.word, ignoreCase = true) }
                                        ?: entry.word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                                    if (target != entry.word) {
                                        proposals.add(CleanupProposal(entry, CleanAction.CAPITALIZE, target, item.reason))
                                    }
                                }
                            }
                        }
                    }
                    AuditResult.Ready(proposals)
                }
            } catch (e: Exception) {
                android.util.Log.e("DictCleaner", "Audit failed", e)
                AuditResult.Error("Audit failed: ${e.message}")
            }
        }
    }

    suspend fun applyProposals(context: Context, proposals: List<CleanupProposal>): Int = withContext(Dispatchers.IO) {
        val resolver: ContentResolver = context.contentResolver
        var applied = 0
        for (proposal in proposals) {
            val where = "${UserDictionary.Words._ID}=?"
            val args = arrayOf(proposal.entry.id.toString())
            when (proposal.action) {
                CleanAction.DELETE -> applied += resolver.delete(UserDictionary.Words.CONTENT_URI, where, args)
                CleanAction.LOWERCASE, CleanAction.CAPITALIZE -> {
                    val updated = proposal.newWord ?: continue
                    val values = ContentValues().apply { put(UserDictionary.Words.WORD, updated) }
                    applied += resolver.update(UserDictionary.Words.CONTENT_URI, values, where, args)
                }
            }
        }
        applied
    }

    private fun parseJsonItems(raw: String): List<AiAuditItem> {
        val list = mutableListOf<AiAuditItem>()
        raw.lines().forEach { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                val action = parts[0].trim().lowercase()
                val rhs = parts[1].trim().removeSurrounding("\"")
                if (action == "capitalize" && rhs.contains("->")) {
                    val arrowParts = rhs.split("->", limit = 2)
                    val w = arrowParts[0].trim()
                    val fixed = arrowParts[1].trim()
                    if (w.isNotBlank() && fixed.isNotBlank()) list.add(AiAuditItem(w, action, fixed, ""))
                } else if (action == "delete" || action == "lowercase" || action == "capitalize") {
                    rhs.split(",").forEach { w ->
                        val word = w.trim()
                        if (word.isNotBlank()) list.add(AiAuditItem(word, action, null, ""))
                    }
                }
            }
        }
        return list
    }
}