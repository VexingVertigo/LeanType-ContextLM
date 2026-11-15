package helium314.keyboard.contextlm

import android.content.Context
import android.util.Log
import helium314.keyboard.keyboard.Keyboard
import helium314.keyboard.latin.NgramContext
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.WordComposer
import helium314.keyboard.latin.common.ComposedData
import helium314.keyboard.latin.dictionary.Dictionary
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Orchestrates off-UI-thread contextual Transformer predictions with sequence-based
 * cancellation and a strict <40ms latency budget, merging neural candidates into
 * LeanType's SuggestedWords without blocking keystrokes.
 */
object ContextLmFacilitator {
    private const val TAG = "ContextLmFacilitator"
    private const val INFERENCE_TIMEOUT_MS = 38L
    private const val BASE_NEURAL_SCORE = 1_500_000

    private val lmExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LeanType-ContextLM").apply {
            priority = Thread.NORM_PRIORITY + 1
            isDaemon = true
        }
    }

    private val latestSequence = AtomicInteger(0)

    @Volatile
    private var activeFuture: Future<*>? = null

    @Volatile
    private var cachedModel: ContextLmModel? = null

    @Volatile
    private var cachedModelPath: String? = null

    fun isEnabledAndReady(context: Context): Boolean {
        if (!ContextLmPreferences.isEnabled(context)) return false
        val path = ContextLmPreferences.getModelPath(context)
        return !path.isNullOrBlank()
    }

    fun invalidateModel() {
        lmExecutor.execute {
            cachedModel?.close()
            cachedModel = null
            cachedModelPath = null
        }
    }

    /**
     * Cancels any stale in-flight inference when a new keystroke arrives and runs
     * contextual Transformer prediction within the <40ms budget.
     * Returns null on timeout, cancellation, or if no model is loaded (caller uses LatinIME).
     */
    fun getContextualSuggestions(
        context: Context,
        wordComposer: WordComposer,
        ngramContext: NgramContext,
        keyboard: Keyboard,
        sequenceNumber: Int,
        autocorrectThreshold: Float,
        bannedWords: Array<String> = emptyArray()
    ): List<SuggestedWordInfo>? {
        if (!isEnabledAndReady(context)) return null
        val modelPath = ContextLmPreferences.getModelPath(context) ?: return null

        latestSequence.set(sequenceNumber)
        activeFuture?.cancel(false)

        val composedData: ComposedData = wordComposer.getComposedDataSnapshot()
        val partialWord = composedData.mTypedWord ?: ""
        val prevContext = extractPrecedingContext(ngramContext, partialWord)

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

        val task = Callable<List<SuggestedWordInfo>?> {
            if (latestSequence.get() != sequenceNumber) return@Callable null

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
                bannedWords = bannedWords
            ) ?: return@Callable null

            if (latestSequence.get() != sequenceNumber) return@Callable null

            val out = ArrayList<SuggestedWordInfo>(result.words.size)
            for (i in result.words.indices) {
                val word = result.words[i]
                val prob = result.probabilities[i].coerceIn(0f, 1f)
                val score = (BASE_NEURAL_SCORE * prob).toInt().coerceAtLeast(1)
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
                        ngramContext.extractPrevWordsContext(),
                        score,
                        kind,
                        Dictionary.DICTIONARY_USER_TYPED, // non-null placeholder source dict
                        SuggestedWordInfo.NOT_AN_INDEX,
                        SuggestedWordInfo.NOT_A_CONFIDENCE
                    )
                )
            }
            out
        }

        val future = lmExecutor.submit(task)
        activeFuture = future
        return try {
            future.get(INFERENCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(false)
            null
        } catch (e: Exception) {
            Log.d(TAG, "Contextual LM skipped: ${e.message}")
            null
        }
    }

    /**
     * Merges neural contextual candidates with LatinIME's dictionary suggestions,
     * preserving the typed word at index 0 when present and deduplicating case-insensitively.
     */
    fun mergeSuggestions(
        baseSuggestions: SuggestedWords,
        neuralCandidates: List<SuggestedWordInfo>?
    ): SuggestedWords {
        if (neuralCandidates.isNullOrEmpty()) return baseSuggestions

        val merged = ArrayList<SuggestedWordInfo>(baseSuggestions.size() + neuralCandidates.size)
        val seen = HashSet<String>()

        // Preserve typed word slot if baseSuggestions has one
        val startIndex = if (baseSuggestions.mWillAutoCorrect || baseSuggestions.size() == 0) 0 else 1
        if (startIndex == 1 && baseSuggestions.size() > 0) {
            val typed = baseSuggestions.getInfo(0)
            merged.add(typed)
            seen.add(typed.mWord.lowercase())
        }

        for (candidate in neuralCandidates) {
            if (seen.add(candidate.mWord.lowercase())) {
                merged.add(candidate)
            }
        }

        for (i in startIndex until baseSuggestions.size()) {
            val info = baseSuggestions.getInfo(i)
            if (seen.add(info.mWord.lowercase())) {
                merged.add(info)
            }
        }

        return SuggestedWords(
            merged,
            baseSuggestions.mRawSuggestions,
            baseSuggestions.mTypedWordInfo,
            baseSuggestions.mTypedWordValid,
            baseSuggestions.mWillAutoCorrect,
            baseSuggestions.mIsObsoleteSuggestions,
            baseSuggestions.mInputStyle,
            baseSuggestions.mSequenceNumber
        )
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

    private fun extractPrecedingContext(ngramContext: NgramContext, partialWord: String): String {
        val raw = ngramContext.extractPrevWordsContext()
            .replace(NgramContext.BEGINNING_OF_SENTENCE_TAG, " ")
            .trim()
        return if (partialWord.isNotEmpty() && raw.endsWith(partialWord)) {
            raw.removeSuffix(partialWord).trimEnd()
        } else {
            raw
        }
    }
}
