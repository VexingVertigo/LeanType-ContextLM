package helium314.keyboard.contextlm

import android.util.Log
import java.io.File

/**
 * JNI bridge to libcontextlm.so (FUTO llama.cpp / GGUF v2 char_embed_mixing_v1 predictor).
 * Fails gracefully if the native library or external .gguf model is unavailable.
 */
class ContextLmModel private constructor(
    private var nativeHandle: Long,
    val modelPath: String
) : AutoCloseable {

    companion object {
        private const val TAG = "ContextLmModel"
        const val RETURN_AUTOCORRECT = 1
        const val RETURN_UNCERTAIN = 2
        const val RETURN_CLUELESS = 3

        @Volatile
        private var libraryLoaded = false

        @Synchronized
        fun ensureNativeLoaded(): Boolean {
            if (libraryLoaded) return true
            return try {
                System.loadLibrary("contextlm")
                libraryLoaded = true
                true
            } catch (t: Throwable) {
                Log.w(TAG, "libcontextlm.so not available; falling back to LatinIME dictionary", t)
                false
            }
        }

        fun open(modelPath: String): ContextLmModel? {
            if (modelPath.isBlank() || !File(modelPath).isFile) return null
            if (!ensureNativeLoaded()) return null
            val handle = openNative(modelPath)
            if (handle == 0L) {
                Log.e(TAG, "Failed to open GGUF model at $modelPath")
                return null
            }
            return ContextLmModel(handle, modelPath)
        }

        @JvmStatic
        private external fun openNative(modelPath: String): Long
    }

    data class PredictionResult(
        val words: List<String>,
        val probabilities: List<Float>,
        val mode: Int
    )

    val isOpen: Boolean
        get() = nativeHandle != 0L

    fun getSuggestions(
        context: String,
        partialWord: String,
        composeX: IntArray,
        composeY: IntArray,
        keyboardWidth: Int,
        keyboardHeight: Int,
        commonKeyWidth: Int,
        keyCodes: IntArray,
        keyCenterXs: IntArray,
        keyCenterYs: IntArray,
        autocorrectThreshold: Float,
        bannedWords: Array<String>,
        maxResults: Int = 6
    ): PredictionResult? {
        val handle = nativeHandle
        if (handle == 0L) return null

        val outWords = arrayOfNulls<String>(maxResults)
        val outProbs = FloatArray(maxResults)

        val mode = getSuggestionsNative(
            handle,
            context,
            partialWord,
            composeX,
            composeY,
            keyboardWidth,
            keyboardHeight,
            commonKeyWidth,
            keyCodes,
            keyCenterXs,
            keyCenterYs,
            autocorrectThreshold,
            bannedWords,
            outWords,
            outProbs
        )

        val words = ArrayList<String>(maxResults)
        val probs = ArrayList<Float>(maxResults)
        for (i in 0 until maxResults) {
            val w = outWords[i]
            if (!w.isNullOrEmpty()) {
                words.add(w)
                probs.add(outProbs[i])
            }
        }
        return PredictionResult(words, probs, mode)
    }

    @Synchronized
    override fun close() {
        if (nativeHandle != 0L) {
            closeNative(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun closeNative(handlePtr: Long)

    private external fun getSuggestionsNative(
        handlePtr: Long,
        context: String,
        partialWord: String,
        composeX: IntArray,
        composeY: IntArray,
        keyboardWidth: Int,
        keyboardHeight: Int,
        commonKeyWidth: Int,
        keyCodes: IntArray,
        keyCenterXs: IntArray,
        keyCenterYs: IntArray,
        autocorrectThreshold: Float,
        bannedWords: Array<String>,
        outPredictions: Array<String?>,
        outProbabilities: FloatArray
    ): Int
}
