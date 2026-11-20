package helium314.keyboard.contextlm

import android.content.Context
import android.provider.UserDictionary
import android.util.Log
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.DictionaryFacilitator
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.WordComposer
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.dictionary.Dictionary
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object ContextLmFacilitator {
    private const val TAG = "ContextLmFacilitator"
    private const val INFERENCE_TIMEOUT_MS = 140L
    private const val BUSY_INFERENCE_TIMEOUT_MS = 210L
    private const val BASE_NEURAL_SCORE = 1_500_000
    private const val PERSONAL_DICT_REFRESH_MS = 30_000L

    private val lmExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LeanType-ContextLM").apply {
            priority = Thread.NORM_PRIORITY + 1
            isDaemon = true
        }
    }

    private val latestSequence = AtomicInteger(0)
    private val isBusy = AtomicBoolean(false)

    @Volatile private var activeFuture: Future<*>? = null
    @Volatile private var cachedModel: ContextLmModel? = null
    @Volatile private var cachedModelPath: String? = null
    @Volatile private var cachedPersonalWords: Map<String, String> = emptyMap()
    @Volatile private var lastPersonalDictLoadMs: Long = 0L

    // Reuse sentence context across letters within the same word to avoid repeated IPC binder calls
    @Volatile private var cachedSentenceNgramKey: String = ""
    @Volatile private var cachedSentenceText: String = ""

    fun isEnabledAndReady(context: Context): Boolean {
        if (!ContextLmPreferences.isEnabled(context)) return false
        val path = ContextLmPreferences.getModelPath(context)
        return !path.isNullOrBlank()
    }

    fun invalidateModel() {
        lastPersonalDictLoadMs = 0L
        cachedSentenceNgramKey = ""
        cachedSentenceText = ""
        lmExecutor.execute {
            cachedModel?.close()
            cachedModel = null
            cachedModelPath = null
        }
    }

    fun getContextualSuggestions(
        context: Context,
        wordComposer: WordComposer,
        ngramContext: NgramContext,
        keyboard: Keyboard,
        sequenceNumber: Int,
        autocorrectThreshold: Float,
        textBeforeCursorProvider: (() -> String?)? = null,
        dictionaryFacilitator: DictionaryFacilitator? = null,
        bannedWords: Array<String> = emptyArray()
    ): List<SuggestedWordInfo>? {
        if (!isEnabledAndReady(context)) return null
        val modelPath = ContextLmPreferences.getModelPath(context) ?: return null

        latestSequence.set(sequenceNumber)
        activeFuture?.cancel(false)

        val composedData: ComposedData = wordComposer.getComposedDataSnapshot()
        val partialWord = composedData.mTypedWord ?: ""
        val ngramKey = ngramContext.extractPrevWordsContext()
        val rawBefore = if (ngramKey == cachedSentenceNgramKey && cachedSentenceText.isNotEmpty()) {
            cachedSentenceText
        } else {
            val fetched = textBeforeCursorProvider?.invoke()
            if (!fetched.isNullOrBlank()) {
                cachedSentenceNgramKey = ngramKey
                cachedSentenceText = fetched
            }
            fetched
        }

        val prevContext = extractPrecedingContext(ngramContext, rawBefore, partialWord)
        val isSentenceStart = prevContext.isEmpty() ||
            prevContext.endsWith(".") || prevContext.endsWith("!") ||
            prevContext.endsWith("?") || prevContext.endsWith("\n")

        val xCoords = composedData.mInputPointers.xCoordinates.take(partialWord.length).toIntArray()
        val yCoords = composedData.mInputPointers.yCoordinates.take(partialWord.length).toIntArray()

        val sortedKeys = keyboard.sortedKeys
        val keyCodes = IntArray(sortedKeys.size)
        val keyXs = IntArray(sortedKeys.size)
        val keyYs = IntArray(sortedKeys.size)
        for (i in sortedKeys.indices) {
            val key = sortedKeys[i]
            keyCodes[i] = key.code
            keyXs[i] = key.x + key.width / 2
            keyYs[i] = key.y + key.height / 2
        }

        val personalMap = getPersonalDictionaryMap(context)
        val wasBusy = isBusy.get()

        val task = Callable<List<SuggestedWordInfo>?> {
            if (latestSequence.get() != sequenceNumber) return@Callable null
            isBusy.set(true)
            try {
                val model = getOrLoadModel(modelPath) ?: return@Callable null
                val result = model.getSuggestions(
                    context = prevContext,
                    partialWord = partialWord,
                    composeX = xCoords,
                    composeY = yCoords,
                    keyboardWidth = keyboard.mOccupiedWidth,
                    keyboardHeight = keyboard.mOccupiedHeight,
                    commonKeyWidth = keyboard.mMostCommonKeyWidth,
                    keyCodes = keyCodes,
                    keyCenterXs = keyXs,
                    keyCenterYs = keyYs,
                    autocorrectThreshold = autocorrectThreshold,
                    bannedWords = bannedWords,
                    maxResults = 8
                ) ?: return@Callable null

                if (latestSequence.get() != sequenceNumber) return@Callable null

                val out = ArrayList<SuggestedWordInfo>(result.words.size)
                for (i in result.words.indices) {
                    var word = result.words[i]
                    var prob = result.probabilities[i].coerceIn(0f, 1.5f)
                    val lower = word.lowercase(Locale.ROOT)

                    val personalCased = personalMap[lower]
                    if (personalCased != null) {
                        word = personalCased
                        prob *= 1.30f
                    } else if (!isSentenceStart && dictionaryFacilitator != null) {
                        word = DictionaryHygieneGuard.normalizeForHistory(word) { candidateLower ->
                            dictionaryFacilitator.isValidSuggestionWord(candidateLower)
                        }
                    }

                    val score = (BASE_NEURAL_SCORE * prob.coerceIn(0f, 1.5f)).toInt().coerceAtLeast(1)
                    val kind = if (partialWord.isEmpty()) {
                        SuggestedWordInfo.KIND_PREDICTION
                    } else if (i == 0 && result.mode == ContextLmModel.RETURN_AUTOCORRECT) {
                        SuggestedWordInfo.KIND_CORRECTION or SuggestedWordInfo.KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION
                    } else {
                        SuggestedWordInfo.KIND_CORRECTION
                    }

                    out.add(
                        SuggestedWordInfo(
                            word,
                            ngramKey,
                            score,
                            kind,
                            Dictionary.DICTIONARY_USER_TYPED,
                            SuggestedWordInfo.NOT_AN_INDEX,
                            SuggestedWordInfo.NOT_A_CONFIDENCE
                        )
                    )
                }
                out
            } finally {
                isBusy.set(false)
            }
        }

        val future = lmExecutor.submit(task)
        activeFuture = future
        val budgetMs = if (wasBusy) BUSY_INFERENCE_TIMEOUT_MS else INFERENCE_TIMEOUT_MS
        return try {
            future.get(budgetMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(false)
            null
        } catch (e: Exception) {
            Log.d(TAG, "Contextual LM skipped: ${e.message}")
            null
        }
    }

    fun mergeSuggestions(
        baseSuggestions: SuggestedWords,
        neuralCandidates: List<SuggestedWordInfo>?,
        isCorrectionEnabled: Boolean = true
    ): SuggestedWords {
        if (neuralCandidates.isNullOrEmpty()) return baseSuggestions

        val topNeural = neuralCandidates[0]
        val neuralWantsAutoCorrect = isCorrectionEnabled &&
            (topNeural.kind and SuggestedWordInfo.KIND_FLAG_APPROPRIATE_FOR_AUTO_CORRECTION) != 0 &&
            baseSuggestions.mTypedWordInfo != null &&
            !topNeural.mWord.equals(baseSuggestions.mTypedWordInfo.mWord, ignoreCase = true)

        val willAutoCorrect = baseSuggestions.mWillAutoCorrect || neuralWantsAutoCorrect
        val merged = ArrayList<SuggestedWordInfo>(baseSuggestions.size() + neuralCandidates.size + 2)
        val seen = HashSet<String>()

        val typedInfo = baseSuggestions.mTypedWordInfo
        val hasTypedWord = typedInfo != null && typedInfo.mWord.isNotEmpty()
        val typedLower = if (hasTypedWord) typedInfo!!.mWord.lowercase(Locale.ROOT) else ""

        val ranked = ArrayList<SuggestedWordInfo>(baseSuggestions.size() + neuralCandidates.size)
        for (candidate in neuralCandidates) {
            val l = candidate.mWord.lowercase(Locale.ROOT)
            if (l != typedLower && seen.add(l)) {
                ranked.add(candidate)
            }
        }
        for (i in 0 until baseSuggestions.size()) {
            val info = baseSuggestions.getInfo(i)
            val l = info.mWord.lowercase(Locale.ROOT)
            if (l != typedLower && seen.add(l)) {
                ranked.add(info)
            }
        }

        // Match SuggestedWords indices:
        // INDEX_OF_TYPED_WORD = 0, INDEX_OF_AUTO_CORRECTION = 1, INDEX_OF_TYPED_WORD_WHEN_AUTOCORRECT = 2
        if (!hasTypedWord) {
            merged.addAll(ranked)
        } else if (willAutoCorrect) {
            merged.add(typedInfo!!)
            if (ranked.isNotEmpty()) merged.add(ranked[0])
            merged.add(typedInfo)
            for (i in 1 until ranked.size) merged.add(ranked[i])
        } else {
            merged.add(typedInfo!!)
            merged.addAll(ranked)
        }

        return SuggestedWords(
            merged,
            baseSuggestions.mRawSuggestions,
            baseSuggestions.mTypedWordInfo,
            baseSuggestions.mTypedWordValid,
            willAutoCorrect,
            baseSuggestions.mIsObsoleteSuggestions,
            baseSuggestions.mInputStyle,
            baseSuggestions.mSequenceNumber
        )
    }

    private fun getPersonalDictionaryMap(context: Context): Map<String, String> {
        val now = System.currentTimeMillis()
        if (now - lastPersonalDictLoadMs < PERSONAL_DICT_REFRESH_MS && cachedPersonalWords.isNotEmpty()) {
            return cachedPersonalWords
        }
        lastPersonalDictLoadMs = now
        return try {
            val map = HashMap<String, String>()
            context.contentResolver.query(
                UserDictionary.Words.CONTENT_URI,
                arrayOf(UserDictionary.Words.WORD),
                null,
                null,
                null
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(UserDictionary.Words.WORD)
                if (idx >= 0) {
                    while (cursor.moveToNext()) {
                        val w = cursor.getString(idx)?.trim()
                        if (!w.isNullOrEmpty() && !w.contains(' ')) {
                            map[w.lowercase(Locale.ROOT)] = w
                        }
                    }
                }
            }
            cachedPersonalWords = map
            map
        } catch (_: Throwable) {
            cachedPersonalWords
        }
    }

    private fun getOrLoadModel(path: String): ContextLmModel? {
        if (cachedModelPath == path && cachedModel?.isOpen == true) {
            return cachedModel
        }
        cachedModel?.close()
        cachedModel = ContextLmModel.open(path)
        cachedModelPath = if (cachedModel != null) path else null
        return cachedModel
    }

    private fun extractPrecedingContext(
        ngramContext: NgramContext,
        textBeforeCursor: String?,
        partialWord: String
    ): String {
        val source = if (!textBeforeCursor.isNullOrBlank()) {
            val stripped = if (partialWord.isNotEmpty() && textBeforeCursor.endsWith(partialWord)) {
                textBeforeCursor.removeSuffix(partialWord)
            } else {
                textBeforeCursor
            }
            val tail = if (stripped.length > 240) stripped.takeLast(240) else stripped
            val lastNewline = tail.lastIndexOf('\n')
            if (lastNewline >= 0) tail.substring(lastNewline + 1).trim() else tail.trim()
        } else {
            ngramContext.extractPrevWordsContext()
                .replace(NgramContext.BEGINNING_OF_SENTENCE_TAG, " ")
                .trim()
        }

        return if (partialWord.isNotEmpty() && source.endsWith(partialWord)) {
            source.removeSuffix(partialWord).trimEnd()
        } else {
            source
        }
    }
}