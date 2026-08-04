package com.whereapp.messenger

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import java.io.OutputStream

/**
 * Сохранение вложений на телефон.
 *
 * WebView сам ничего не скачивает: без этого нажатие на «скачать»
 * внутри страницы просто ничего не делало. Здесь принимаем данные
 * и кладём файл в галерею или в загрузки, чтобы он был виден
 * в обычных приложениях телефона.
 */
object Downloads {

    /** Ссылка из WebView. Файлы приходят как data:-строка, а не по сети. */
    fun save(ctx: Context, url: String, mime: String?, disposition: String?) {
        try {
            if (url.startsWith("data:")) {
                val name = URLUtil.guessFileName(url, disposition, mime)
                saveDataUrl(ctx, url, name)
            } else {
                Toast.makeText(ctx, "Не удалось скачать файл", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(ctx, "Ошибка сохранения", Toast.LENGTH_SHORT).show()
        }
    }

    /** Строка вида data:image/jpeg;base64,.... */
    fun saveDataUrl(ctx: Context, dataUrl: String, fileName: String) {
        try {
            val comma = dataUrl.indexOf(',')
            if (comma < 0) return
            val header = dataUrl.substring(0, comma)
            val mime = header.removePrefix("data:").substringBefore(";").ifBlank { "application/octet-stream" }
            val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
            writeFile(ctx, bytes, fixName(fileName, mime), mime)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Ошибка сохранения", Toast.LENGTH_SHORT).show()
        }
    }

    /* ---- Приём больших файлов по частям ----
       Видео в виде одной data-строки не доходит: WebView обрывает
       слишком длинный вызов. Поэтому принимаем кусками и склеиваем. */
    private val chunks = HashMap<String, StringBuilder>()

    fun chunkStart(id: String) {
        chunks[id] = StringBuilder()
    }

    fun chunkAdd(id: String, part: String) {
        chunks[id]?.append(part)
    }

    fun chunkFinish(ctx: Context, id: String, name: String, mime: String) {
        val sb = chunks.remove(id)
        if (sb == null) {
            Toast.makeText(ctx, "Файл не получен", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val bytes = Base64.decode(sb.toString(), Base64.DEFAULT)
            writeFile(ctx, bytes, fixName(name, mime), mime)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Ошибка сохранения", Toast.LENGTH_SHORT).show()
        }
    }

    private fun fixName(name: String, mime: String): String {
        if (name.contains('.')) return name
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        return "$name.$ext"
    }

    private fun writeFile(ctx: Context, bytes: ByteArray, name: String, mime: String) {
        val isImage = mime.startsWith("image/")
        val isVideo = mime.startsWith("video/")

        val collection: Uri
        val values = ContentValues()
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val dir = when {
                isImage -> Environment.DIRECTORY_PICTURES + "/WhereApp"
                isVideo -> Environment.DIRECTORY_MOVIES + "/WhereApp"
                else -> Environment.DIRECTORY_DOWNLOADS
            }
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, dir)
            collection = when {
                isImage -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                isVideo -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
        } else {
            @Suppress("DEPRECATION")
            collection = when {
                isImage -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                isVideo -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Files.getContentUri("external")
            }
        }

        val uri = ctx.contentResolver.insert(collection, values)
        if (uri == null) {
            Toast.makeText(ctx, "Не удалось создать файл", Toast.LENGTH_SHORT).show()
            return
        }

        var out: OutputStream? = null
        try {
            out = ctx.contentResolver.openOutputStream(uri)
            out?.write(bytes)
            out?.flush()
            val where = if (isImage || isVideo) "в галерею" else "в загрузки"
            Toast.makeText(ctx, "Сохранено $where", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(ctx, "Ошибка записи файла", Toast.LENGTH_SHORT).show()
        } finally {
            try { out?.close() } catch (e: Exception) { }
        }
    }
}
