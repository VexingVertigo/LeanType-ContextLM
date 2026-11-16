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
    private const val BATCH_SIZE = 12
    private val mutex = Mutex()

    enum class CleanAction { DELETE, LOWERCASE }
    data class DictEntry(val id: Long, val word: String, val shortcut: String?, val frequency: Int, val locale: String?)
    @Serializable private data class AiAuditItem(val word: String, val action: String, val reason: String = "")
    data class CleanupProposal(val entry: DictEntry, val action: CleanAction, val newWord: String?, val reason: String)
    
    sealed class AuditResult {
        data class Ready(val proposals: List<CleanupProposal>) : AuditResult()
        data class Error(val message: String) : AuditResult()
    }

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

    suspend fun auditDictionary(context: Context, localeFilter: Locale?): AuditResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                withTimeout(45000) {
                    if (!OfflineAiLoader.hasPlugin(context)) return@withTimeout AuditResult.Error("Offline AI plugin is not loaded.")
                    val provider = OfflineAiLoader.getProvider(context) ?: return@withTimeout AuditResult.Error("Unable to initialize Offline AI provider.")
                    if (!provider.isAvailable()) return@withTimeout AuditResult.Error("Offline AI provider is unavailable.")
                    
                    if (!provider.isModelLoaded()) {
                        val p = ProofreadService(context).getModelPath()
                        if (p.isNullOrBlank() || !provider.loadModel(context, p, 4, 4096)) return@withTimeout AuditResult.Error("Failed to load model in AI plugin.")
                    }

                    val entries = readUserDictionaryEntries(context, localeFilter)
                    if (entries.isEmpty()) return@withTimeout AuditResult.Ready(emptyList())

                    val proposals = ArrayList<CleanupProposal>()
                    val entryByWord = entries.associateBy { it.word }

                    for (chunk in entries.chunked(BATCH_SIZE)) {
                        val wordsStr = chunk.joinToString(", ") { it.word }
                        val prompt = "Instruction: Fix typos (delete) and lowercase capitalized words. Do NOT touch acronyms (DNS, API) or proper nouns (Google, John). Format: action: word.\n\nInput: teh, The, Google, DNS, Applicable\nOutput:\ndelete: teh\nlowercase: Applicable\n\nInput: $wordsStr\nOutput:\n"
                        
                        OfflineAiLoader.isBusy = true
                        val rawResponse = try {
                            provider.generate(prompt = prompt, params = mapOf("temperature" to 0.0, "max_tokens" to 100))
                        } catch (e: Exception) {
                            android.util.Log.e("DictCleaner", "Generate failed", e)
                            ""
                        } finally {
                            OfflineAiLoader.isBusy = false
                        }
                        
                        android.util.Log.i("DictCleaner", "batch=${chunk.size} rawLen=${rawResponse.length} raw=${rawResponse.take(200)}")
                        
                        for (item in parseJsonItems(rawResponse)) {
                            val entry = entryByWord[item.word] ?: continue
                            when (item.action.lowercase(Locale.ROOT).trim()) {
                                "delete" -> proposals.add(CleanupProposal(entry, CleanAction.DELETE, null, item.reason))
                                "lowercase" -> {
                                    val lower = entry.word.lowercase(Locale.ROOT)
                                    if (lower != entry.word) proposals.add(CleanupProposal(entry, CleanAction.LOWERCASE, lower, item.reason))
                                }
                            }
                        }
                    }
                    android.util.Log.i("DictCleaner", "Returning ${proposals.size} proposals")
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
                CleanAction.LOWERCASE -> {
                    val lower = proposal.newWord ?: continue
                    val values = ContentValues().apply { put(UserDictionary.Words.WORD, lower) }
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
                val words = parts[1].trim().removeSurrounding("\"").split(",")
                if (action == "delete" || action == "lowercase") {
                    words.forEach { w ->
                        val word = w.trim()
                        if (word.isNotBlank()) list.add(AiAuditItem(word, action, ""))
                    }
                }
            }
        }
        return list
    }
}