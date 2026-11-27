package com.example.files

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.GeneratedFileAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles secure app-private storage (`filesDir/generated_files/`), FileProvider Open & Share intents,
 * and zero-permission public Downloads export via MediaStore.Downloads (Play Policy compliant).
 */
object FileExportManager {
    private const val GENERATED_DIR = "generated_files"
    private const val DOWNLOADS_SUBFOLDER = "MyPersonalAgent"

    fun createOutputFile(context: Context, suggestedFileName: String, format: GeneratedFileFormat): File {
        val dir = File(context.filesDir, GENERATED_DIR).apply { mkdirs() }
        val sanitized = FileBlueprintParser.sanitizeFileName(suggestedFileName, format.extension)
        val base = sanitized.substringBeforeLast('.')
        val ext = format.extension
        val timestamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
        val candidate = File(dir, "$base.$ext")
        return if (!candidate.exists()) candidate else File(dir, "${base}_$timestamp.$ext")
    }

    fun openFile(context: Context, attachment: GeneratedFileAttachment): Result<Unit> {
        return runCatching {
            val file = File(attachment.filePath)
            require(file.exists()) { "Generated file no longer exists on device." }
            val format = GeneratedFileFormat.fromString(attachment.fileType)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, format.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(intent, "Open ${attachment.fileName}")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }
    }

    fun shareFile(context: Context, attachment: GeneratedFileAttachment): Result<Unit> {
        return runCatching {
            val file = File(attachment.filePath)
            require(file.exists()) { "Generated file no longer exists on device." }
            val format = GeneratedFileFormat.fromString(attachment.fileType)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = format.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, attachment.fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Share ${attachment.fileName}")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        }
    }

    /**
     * Copies the generated file into public `Downloads/MyPersonalAgent/` via zero-permission
     * MediaStore.Downloads on Android 10+ (API 29+).
     */
    suspend fun saveToDownloads(context: Context, attachment: GeneratedFileAttachment): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val source = File(attachment.filePath)
                require(source.exists()) { "Generated file no longer exists on device." }
                val format = GeneratedFileFormat.fromString(attachment.fileType)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, attachment.fileName)
                        put(MediaStore.Downloads.MIME_TYPE, format.mimeType)
                        put(
                            MediaStore.Downloads.RELATIVE_PATH,
                            "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOADS_SUBFOLDER"
                        )
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("Unable to create entry in Downloads.")
                    try {
                        resolver.openOutputStream(uri)?.use { out ->
                            source.inputStream().use { input -> input.copyTo(out) }
                        } ?: error("Unable to write to Downloads stream.")
                        values.clear()
                        values.put(MediaStore.Downloads.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    } catch (e: Exception) {
                        resolver.delete(uri, null, null)
                        throw e
                    }
                    "Saved to Downloads/$DOWNLOADS_SUBFOLDER/${attachment.fileName}"
                } else {
                    val fallbackDir = File(
                        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir,
                        DOWNLOADS_SUBFOLDER
                    ).apply { mkdirs() }
                    val target = File(fallbackDir, attachment.fileName)
                    source.copyTo(target, overwrite = true)
                    "Saved to ${target.name}"
                }
            }
        }
}
