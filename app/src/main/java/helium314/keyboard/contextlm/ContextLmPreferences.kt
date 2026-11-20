package helium314.keyboard.contextlm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.filePicker
import java.io.File

/**
 * Preferences and SAF .gguf file picker for the FUTO Contextual Transformer LM,
 * matching LeanType's existing GGUF/plugin filePicker pattern.
 */
object ContextLmPreferences {
    const val PREF_CONTEXT_LM_ENABLED = "context_lm_enabled"
    const val PREF_CONTEXT_LM_MODEL_PATH = "context_lm_model_path"
    private const val MODEL_DIR_NAME = "contextlm_models"

    fun isEnabled(context: Context): Boolean =
        context.prefs().getBoolean(PREF_CONTEXT_LM_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.prefs().edit().putBoolean(PREF_CONTEXT_LM_ENABLED, enabled).apply()
        if (!enabled) ContextLmFacilitator.invalidateModel()
    }

    fun getModelPath(context: Context): String? {
        val saved = context.prefs().getString(PREF_CONTEXT_LM_MODEL_PATH, null) ?: return null
        val savedFile = File(saved)
        if (savedFile.canRead()) return saved
        val localFallback = File(File(context.filesDir, MODEL_DIR_NAME), savedFile.name)
        if (localFallback.canRead()) {
            setModelPath(context, localFallback.absolutePath)
            return localFallback.absolutePath
        }
        return saved
    }

    fun setModelPath(context: Context, path: String?) {
        context.prefs().edit().putString(PREF_CONTEXT_LM_MODEL_PATH, path).apply()
        ContextLmFacilitator.invalidateModel()
    }

    /**
     * Copies the user-selected .gguf file from SAF Uri into app-private storage so
     * native C++ gguf_init_from_file() can mmap/read a standard POSIX file path.
     */
    fun importModelFromUri(context: Context, uri: Uri): String? {
        val dir = File(context.filesDir, MODEL_DIR_NAME).apply { mkdirs() }
        // Remove previous imported model to avoid bloating storage (~62 MB each)
        dir.listFiles()?.forEach { it.delete() }

        val displayName = queryDisplayName(context, uri) ?: "futo_context_lm.gguf"
        val destFile = File(dir, displayName)

        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } ?: return null
            setModelPath(context, destFile.absolutePath)
            destFile.absolutePath
        } catch (_: Exception) {
            destFile.delete()
            null
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0)
            }
        return null
    }
}

@Composable
fun ContextLmSettingsSection() {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(ContextLmPreferences.isEnabled(ctx)) }
    var modelPath by remember { mutableStateOf(ContextLmPreferences.getModelPath(ctx)) }

    val ggufPicker = filePicker { uri ->
        val imported = ContextLmPreferences.importModelFromUri(ctx, uri)
        if (imported != null) {
            modelPath = imported
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Contextual Transformer Prediction",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Use an external FUTO .gguf model for sentence-aware next-word and autocorrect candidates",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    ContextLmPreferences.setEnabled(ctx, it)
                }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    ggufPicker.launch(intent)
                }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Transformer GGUF Model",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = modelPath?.let { File(it).name } ?: "Tap to select .gguf model file",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (modelPath != null) {
                TextButton(onClick = {
                    ContextLmPreferences.setModelPath(ctx, null)
                    modelPath = null
                }) {
                    Text("Clear")
                }
            }
        }
    }
}
