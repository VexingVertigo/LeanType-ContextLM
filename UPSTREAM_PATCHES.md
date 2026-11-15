# Surgical Upstream Patches for LeanBitLab/LeanType

These are the minimal, isolated touchpoints in existing `LeanType` files so future upstream `LeanBitLab/LeanType` merges remain clean.

---

## 1. `app/build.gradle.kts` (CMake + `contextlm` native build)
Note: `LeanType` currently uses `ndkBuild { path = File("src/main/jni/Android.mk") }` in `externalNativeBuild`.
Android Gradle Plugin allows only one `externalNativeBuild` system per module (`cmake` OR `ndkBuild`).
- **Option A (CMake, as requested):** Switch `externalNativeBuild` to `cmake { path = File("src/main/cpp/CMakeLists.txt") }` (and include `src/main/jni/Android.mk` sources or build `libcontextlm.so` separately with `System.loadLibrary("contextlm")`).
- **Option B (Keep upstream `ndkBuild` untouched):** Add `include $(LOCAL_PATH)/../cpp/contextlm/Android.mk` at the bottom of `app/src/main/jni/Android.mk`.

Also ensure `futo-org/android-keyboard`'s `native/jni/src/ggml/` and `native/jni/src/sentencepiece/` are copied into `app/src/main/cpp/contextlm/ggml/` and `app/src/main/cpp/contextlm/sentencepiece/`.

---

## 2. `app/src/main/java/helium314/keyboard/latin/Suggest.kt` (Hook 1)
In `getSuggestedWordsForNonBatchInput(...)`, right before returning `SuggestedWords(...)`:
```kotlin
val baseSuggestions = SuggestedWords(
    suggestionsList,
    suggestionResults.mRawSuggestions,
    typedWordInfo,
    isTypedWordValid,
    hasAutoCorrection,
    false,
    inputStyle,
    sequenceNumber
)
val neural = helium314.keyboard.contextlm.ContextLmFacilitator.getContextualSuggestions(
    context = keyboard.mId.mSubtype.let { /* pass Application/IME Context */ appContext },
    wordComposer = wordComposer,
    ngramContext = ngramContext,
    keyboard = keyboard,
    sequenceNumber = sequenceNumber,
    autocorrectThreshold = mAutoCorrectionThreshold
)
return helium314.keyboard.contextlm.ContextLmFacilitator.mergeSuggestions(baseSuggestions, neural)
```
*(Pass `Context` into `Suggest` constructor or `mDictionaryFacilitator` context accessor.)*

---

## 3. `app/src/main/java/helium314/keyboard/latin/DictionaryFacilitatorImpl.kt` (Hook 2)
In `addWordToUserHistory(...)`, right before `UserHistoryDictionary.addToDictionary(...)`:
```kotlin
val guardedWord = helium314.keyboard.contextlm.DictionaryHygieneGuard.normalizeForHistory(wordToUse) { lower ->
    getMainDict(dictionaryGroup)?.isValidWord(lower) == true
}
if (guardedWord != wordToUse && isValidWord(guardedWord, DictionaryFacilitator.ALL_DICTIONARY_TYPES, dictionaryGroup)) {
    // Lowercase form is already a valid dictionary word; skip mid-sentence capitalized pollution
    return
}
UserHistoryDictionary.addToDictionary(userHistoryDictionary, ngramContext, guardedWord, isValid, timeStampInSeconds)
```

---

## 4. `app/src/main/java/helium314/keyboard/settings/screens/AIIntegrationScreen.kt` (GGUF File Picker)
Inside `OfflineAIIntegrationScreen(...)`:
```kotlin
helium314.keyboard.contextlm.ContextLmSettingsSection()
```

---

## 5. `app/src/main/java/helium314/keyboard/settings/screens/PersonalDictionaryScreen.kt` (Hook 3: AI Clean Menu + Confirmation Dialog)
In `PersonalDictionaryScreen(...)`:
1. Add state:
```kotlin
var showAiCleanDialog by remember { mutableStateOf(false) }
var aiCleanProposals by remember { mutableStateOf<List<helium314.keyboard.contextlm.PersonalDictionaryAiCleaner.CleanupProposal>>(emptyList()) }
var aiCleanError by remember { mutableStateOf<String?>(null) }
val scope = rememberCoroutineScope()
```
2. Add menu item alongside `personal_dict_export`, `user_dict_sort_title`, and `clear_all`:
```kotlin
Pair("AI Clean Dictionary") {
    scope.launch {
        when (val res = helium314.keyboard.contextlm.PersonalDictionaryAiCleaner.auditDictionary(ctx, locale)) {
            is helium314.keyboard.contextlm.PersonalDictionaryAiCleaner.AuditResult.Ready -> {
                aiCleanProposals = res.proposals
                aiCleanError = null
                showAiCleanDialog = true
            }
            is helium314.keyboard.contextlm.PersonalDictionaryAiCleaner.AuditResult.Error -> {
                aiCleanError = res.message
                showAiCleanDialog = true
            }
        }
    }
}
```
3. Show confirmation dialog listing `aiCleanProposals` (`DELETE` / `LOWERCASE`) and on confirm call `PersonalDictionaryAiCleaner.applyProposals(ctx, aiCleanProposals)` and reload the word list.
