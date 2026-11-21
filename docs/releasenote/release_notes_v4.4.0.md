### 💖 Support Our Work

Keep LeanType fast and independent. As a community-funded, open-source project, we rely on users like you to stay alive. If LeanType saves you time every day, consider backing us on [GitHub Sponsors](https://github.com/sponsors/LeanBitLab) or [Open Collective](https://opencollective.com/leanbitlab-org) or simply share it with a friend!

## 🚀 LeanType 4.4.0

---

### ✨ Added
- **Contextual Transformer Prediction & Autocorrect (FUTO GGUF)**: Integrated native `libcontextlm.so` (`llama.cpp` + SentencePiece + spatial key-coordinate mixing) for sentence-aware next-word predictions and context-sensitive autocorrections. Import any compatible quantized FUTO `.gguf` model (such as `ml4_q6_k.gguf`) via Android's file picker in **Settings → AI Integration → Contextual Transformer Prediction** — runs 100% on-device with zero internet access across both Standard and Offline flavors.
- **Personal Dictionary Hygiene & AI Cleaner**: Added `DictionaryHygieneGuard` to prevent unverified neural candidates from polluting learned typing history, alongside a 1-tap **AI Personal Dictionary Cleaner** in **Settings → Dictionaries → Personal Dictionary** to review and prune accidental typos or noisy entries.

---

### 🛠 Fixed & Hardened
- **Autocorrect Slot Prioritization & Spacebar Commit**: Aligned `ContextLmFacilitator` candidate merging with `SuggestedWords` index mapping (`INDEX_OF_TYPED_WORD`, `INDEX_OF_AUTO_CORRECTION`, `INDEX_OF_TYPED_WORD_WHEN_AUTOCORRECT`) so high-confidence neural corrections take the center auto-correct slot and commit automatically on Spacebar.
- **Fast-Typing Concurrency & Binder Caching**: Added single-worker busy-state tracking (`AtomicBoolean`) and per-word cursor context caching so rapid keystrokes complete inference without stalling on duplicate `getTextBeforeCursor` IPC binder calls.
- **Self-Healing GGUF Model Path Resolution**: Automatically resolves migrated or cross-flavor `.gguf` model paths to the active app sandbox (`files/contextlm_models/`) when readable locally, and hardened native symbol visibility (`contextlm.map`) and mutex synchronization.

---

### 📦 Choose Your Flavor

| Flavor | Primary Focus | AI Engine | Plugins Setup | Internet | Release Updater | Size |
|:---|:---|:---|:---|:---|:---|:---:|
| **`1-LeanType_4.4.0-standard-release.apk`** | **Recommended** | Cloud AI + Local ContextLM | In-app download or File import | Optional (AI/plugins) | ✅ View Release | 43.7 MB |
| **`2-LeanType_4.4.0-offline-release.apk`** | **Offline** | Local LLM Plugin (8.0+) + Local ContextLM | Browser download + File import | 🚫 Zero Internet (No Permission) | ❌ None | 43.3 MB |

> 💡 **Plugin Compatibility**: All flavors support **On-Device Contextual Transformer LM** (`.gguf` via SAF), **Offline Voice Dictation** (Android 8.1+), **Offline Translation** (Android 6.0+), **Offline Handwriting Recognition** (Android 6.0+), **Offline OCR Text Extraction** (Android 6.0+), and **Offline AI Proofreading** (Android 8.0+, 64-bit) via modular plugins, and work 100% offline.