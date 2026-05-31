package com.echonion.nion.ui.task

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.util.UUID
import android.util.Log

private const val TAG_ATTACHMENT = "AttachmentPicker"

/**
 * 附件文件信息 —— 复制到内部存储后的文件元数据
 */
data class PickedFileInfo(
    /** 内部存储路径 */
    val filePath: String,
    /** 原始文件名 */
    val fileName: String,
    /** MIME 类型 */
    val mimeType: String,
    /** 文件大小（字节） */
    val fileSize: Long,
)

/**
 * 附件选择器 —— 提供图片选择和文件选择两种方式。
 *
 * 使用 Android Activity Result API：
 * - 图片：PickVisualMedia（系统照片选择器）
 * - 文件：OpenDocument（系统文件选择器）
 *
 * 选中文件后会自动复制到应用内部存储的 attachments 子目录，
 * 并通过 onFileReady 回调返回 PickedFileInfo。
 *
 * @param onFileReady 文件复制完成回调
 */
@Composable
fun rememberAttachmentPicker(
    onFileReady: (PickedFileInfo) -> Unit,
): AttachmentPicker {
    val context = LocalContext.current

    // 图片选择器：使用系统照片选择器
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            val result = copyToInternalStorage(context, uri)
            if (result != null) {
                onFileReady(result)
            }
        }
    }

    // 文件选择器：使用系统文件选择器
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            val result = copyToInternalStorage(context, uri)
            if (result != null) {
                onFileReady(result)
            }
        }
    }

    return remember {
        AttachmentPicker(
            pickImage = {
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            pickFile = {
                filePicker.launch(arrayOf("*/*"))
            },
        )
    }
}

/**
 * 附件选择器句柄，提供 pickImage 和 pickFile 两个方法。
 */
data class AttachmentPicker(
    /** 启动图片选择器 */
    val pickImage: () -> Unit,
    /** 启动文件选择器 */
    val pickFile: () -> Unit,
)

/**
 * 将 URI 指向的文件复制到应用内部存储。
 *
 * 内部存储路径规则：filesDir/attachments/<uuid>.<扩展名>
 *
 * @return 成功返回 PickedFileInfo，失败返回 null
 */
private fun copyToInternalStorage(
    context: Context,
    uri: Uri,
): PickedFileInfo? {
    return try {
        val contentResolver = context.contentResolver

        // 查询原始文件名
        var fileName = "unknown"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                fileName = cursor.getString(nameIndex)
            }
        }

        // 获取 MIME 类型
        var mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

        // 根据 MIME 类型推断扩展名（如果文件名没有扩展名）
        val extension = fileName.substringAfterLast('.', "")
            .ifBlank {
                // 从 MIME 类型推断扩展名
                when {
                    mimeType.startsWith("image/jpeg") -> "jpg"
                    mimeType.startsWith("image/png") -> "png"
                    mimeType.startsWith("image/gif") -> "gif"
                    mimeType.startsWith("image/webp") -> "webp"
                    mimeType.startsWith("image/") -> "jpg"
                    mimeType.startsWith("video/") -> "mp4"
                    mimeType.contains("pdf") -> "pdf"
                    mimeType.contains("word") || mimeType.contains("document") -> "docx"
                    mimeType.contains("sheet") || mimeType.contains("excel") -> "xlsx"
                    mimeType.contains("presentation") -> "pptx"
                    mimeType.contains("zip") -> "zip"
                    mimeType.contains("text/plain") -> "txt"
                    else -> "bin"
                }
            }

        // 如果文件名本身没有扩展名，加上推断的扩展名
        if (!fileName.contains('.')) {
            fileName = "$fileName.$extension"
        }

        // 创建内部存储目录
        val attachmentsDir = File(context.filesDir, "attachments")
        if (!attachmentsDir.exists()) {
            attachmentsDir.mkdirs()
        }

        // 使用 UUID 作为内部文件名，避免冲突
        val internalName = "${UUID.randomUUID()}.$extension"
        val destFile = File(attachmentsDir, internalName)

        // 复制文件内容
        contentResolver.openInputStream(uri)?.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        PickedFileInfo(destFile.absolutePath, fileName, mimeType, destFile.length())
    } catch (e: Exception) {
        Log.w(TAG_ATTACHMENT, "复制附件文件失败", e)
        null
    }
}
