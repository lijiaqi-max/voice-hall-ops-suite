package com.local.interactionassistant.executor.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class PublishedAsset(
    val source: TaskAssetView,
    val uri: Uri,
    val publishedName: String,
)

class AssetLibrary(private val context: Context) {
    private val root = File(context.filesDir, "media-library").apply { mkdirs() }

    fun import(uri: Uri): MediaAssetEntity {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri) ?: "image/jpeg"
        require(mimeType.startsWith("image/")) { "仅支持图片文件" }
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法读取所选图片")
        return importBytes(null, mimeType, bytes)
    }

    fun importBytes(
        displayName: String?,
        mimeType: String,
        bytes: ByteArray,
        expectedSha256: String? = null,
    ): MediaAssetEntity {
        require(mimeType.startsWith("image/")) { "仅支持图片文件" }
        require(bytes.isNotEmpty()) { "图片内容为空" }
        val sha256 = bytes.sha256()
        check(expectedSha256 == null || expectedSha256.equals(sha256, ignoreCase = true)) {
            "备份素材校验失败"
        }
        val extension = when (mimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "jpg"
        }
        val id = UUID.randomUUID().toString()
        val safeDisplayName = displayName?.trim()?.takeIf(String::isNotEmpty)
            ?: "素材-${sha256.take(8)}.$extension"
        val destination = File(root, "$id.$extension")
        destination.writeBytes(bytes)
        return MediaAssetEntity(
            id = id,
            displayName = safeDisplayName,
            privatePath = destination.absolutePath,
            mimeType = mimeType,
            sha256 = sha256,
            byteSize = bytes.size.toLong(),
        )
    }

    fun delete(asset: MediaAssetEntity) {
        runCatching { File(asset.privatePath).delete() }
    }

    fun publish(taskId: String, assets: List<TaskAssetView>): List<PublishedAsset> =
        assets.mapIndexed { index, asset ->
            val source = File(asset.privatePath)
            check(source.isFile) { "素材缺失: ${asset.displayName}" }
            val extension = source.extension.ifBlank { "jpg" }
            val publishedName = "IA_${taskId.take(8)}_${index + 1}.$extension"
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, publishedName)
                put(MediaStore.Images.Media.MIME_TYPE, asset.mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/InteractionAssistant")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = context.contentResolver.insert(collection, values)
                ?: error("无法发布图片: ${asset.displayName}")
            try {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: error("无法写入图片: ${asset.displayName}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.update(
                        uri,
                        ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                        null,
                        null,
                    )
                }
                PublishedAsset(asset, uri, publishedName)
            } catch (error: Throwable) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw error
            }
        }

    fun clearPublished(assets: List<PublishedAsset>) {
        assets.forEach { runCatching { context.contentResolver.delete(it.uri, null, null) } }
    }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(this)
            .joinToString("") { "%02x".format(it) }
}
