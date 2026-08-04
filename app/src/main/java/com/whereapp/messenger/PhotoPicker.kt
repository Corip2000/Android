package com.whereapp.messenger

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.view.ViewOutlineProvider
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Свой выбор фотографий, без обращения к галерее.
 *
 * Читает снимки напрямую из хранилища телефона, показывает сеткой
 * с номерами выбора и полем подписи внизу. Готовые изображения
 * уменьшаются и уходят в веб-часть уже пережатыми.
 */
class PhotoPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_CAPTION = "caption"
        private const val THUMB = 220          // размер миниатюры в пикселях
    }

    private val all = ArrayList<Uri>()
    private val isVideo = HashSet<Uri>()
    private val durations = HashMap<Uri, Long>()   // длительность видео в миллисекундах
    private val chosen = ArrayList<Uri>()
    private lateinit var grid: GridView
    private lateinit var title: TextView
    private lateinit var caption: EditText
    private lateinit var adapter: PhotoAdapter
    private var cell = 0
    private var thumbPx = 400
    private val cache = object : LruCache<String, Bitmap>(60) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)

        // ---- верхняя полоса ----
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setBackgroundColor(Color.parseColor("#5B21B6"))
        top.setPadding(dp(12), dp(14), dp(12), dp(14))

        val close = TextView(this)
        close.text = "✕"
        close.textSize = 20f
        close.setTextColor(Color.WHITE)
        close.setPadding(dp(6), 0, dp(16), 0)
        close.setOnClickListener { finish() }

        title = TextView(this)
        title.text = "Выберите фото"
        title.textSize = 17f
        title.setTextColor(Color.WHITE)
        title.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        val camera = TextView(this)
        camera.text = "Камера"
        camera.textSize = 15f
        camera.setTextColor(Color.WHITE)
        camera.setPadding(dp(10), 0, dp(6), 0)
        camera.setOnClickListener { openCamera() }

        top.addView(close); top.addView(title); top.addView(camera)

        // ---- сетка ----
        grid = GridView(this)
        grid.numColumns = 3
        grid.horizontalSpacing = dp(3)
        grid.verticalSpacing = dp(3)
        grid.setPadding(dp(3), dp(3), dp(3), dp(3))
        grid.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )

        // ---- нижняя полоса с подписью ----
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.gravity = Gravity.CENTER_VERTICAL
        bottom.setBackgroundColor(Color.parseColor("#F0EBFA"))
        bottom.setPadding(dp(10), dp(8), dp(10), dp(8))

        caption = EditText(this)
        caption.hint = "Добавить подпись…"
        caption.setSingleLine(false)
        caption.maxLines = 3
        caption.setBackgroundColor(Color.WHITE)
        caption.setPadding(dp(14), dp(10), dp(14), dp(10))
        caption.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

        val send = Button(this)
        send.text = "▶"
        send.textSize = 16f
        send.setTextColor(Color.WHITE)
        send.setBackgroundColor(Color.parseColor("#7C3AED"))
        send.setOnClickListener { finishWithResult() }

        bottom.addView(caption); bottom.addView(send)

        root.addView(top); root.addView(grid); root.addView(bottom)
        setContentView(root)

        cell = (resources.displayMetrics.widthPixels - dp(12)) / 3
        thumbPx = cell + dp(40)          // берём с запасом, иначе превью мылит
        adapter = PhotoAdapter()
        grid.adapter = adapter
        grid.setOnTouchListener { _, ev ->
            touchX = ev.x + grid.scrollX
            touchY = ev.y + grid.scrollY
            false
        }
        // Тап по кружку отмечает, тап по самой картинке открывает просмотр
        grid.setOnItemClickListener { _, view, pos, _ ->
            val uri = all[pos]
            val box = view as? FrameLayout
            val badge = (box?.tag as? Array<*>)?.get(2) as? TextView
            if (badge != null && lastTouchInBadge(box, badge)) toggle(uri) else preview(uri)
        }

        loadPhotos()
    }

    private var touchX = 0f
    private var touchY = 0f

    private fun lastTouchInBadge(box: FrameLayout, badge: TextView): Boolean {
        val bx = box.left + badge.left
        val by = box.top + badge.top
        val pad = dp(10)
        return touchX >= bx - pad && touchX <= bx + badge.width + pad &&
               touchY >= by - pad && touchY <= by + badge.height + pad
    }

    /** Полноэкранный просмотр снимка или видео прямо из выбора. */
    private fun preview(uri: Uri) {
        try {
            val i = Intent(Intent.ACTION_VIEW)
            val type = contentResolver.getType(uri) ?: "image/*"
            i.setDataAndType(uri, type)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun loadPhotos() {
        CoroutineScope(Dispatchers.IO).launch {
            // Читаем фото и видео вместе, свежие сверху
            val picked = ArrayList<Triple<Uri, Long, Boolean>>()

            fun collect(base: Uri, dateCol: String, video: Boolean) {
                var c: Cursor? = null
                try {
                    val cols = if (video)
                        arrayOf(MediaStore.MediaColumns._ID, dateCol, MediaStore.Video.Media.DURATION)
                    else
                        arrayOf(MediaStore.MediaColumns._ID, dateCol)
                    c = contentResolver.query(base, cols, null, null, "$dateCol DESC")
                    var n = 0
                    while (c != null && c.moveToNext() && n < 300) {
                        val id = c.getLong(0)
                        val date = c.getLong(1)
                        val uri = Uri.withAppendedPath(base, id.toString())
                        picked.add(Triple(uri, date, video))
                        if (video) {
                            val d = try { c.getLong(2) } catch (e: Exception) { 0L }
                            if (d > 0) durations[uri] = d
                        }
                        n++
                    }
                } catch (e: Exception) {
                } finally {
                    c?.close()
                }
            }

            collect(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Images.Media.DATE_ADDED, false
            )
            collect(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.DATE_ADDED, true
            )

            picked.sortByDescending { it.second }
            picked.take(400).forEach {
                all.add(it.first)
                if (it.third) isVideo.add(it.first)
            }

            withContext(Dispatchers.Main) {
                adapter.notifyDataSetChanged()
                if (all.isEmpty()) {
                    Toast.makeText(this@PhotoPickerActivity,
                        "Фото и видео не найдены. Разрешите доступ к файлам в настройках.",
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun toggle(u: Uri) {
        if (chosen.contains(u)) chosen.remove(u) else chosen.add(u)
        title.text = if (chosen.isEmpty()) "Выберите фото" else "Выбрано: ${chosen.size}"
        adapter.notifyDataSetChanged()
    }

    private fun openCamera() {
        try {
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE), 77)
        } catch (e: Exception) {
            Toast.makeText(this, "Камера недоступна", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 77 && resultCode == Activity.RESULT_OK) {
            // Снимок с камеры сразу отправляем
            val bmp = data?.extras?.get("data") as? Bitmap
            if (bmp != null) {
                PhotoBridge.pendingBitmap = bmp
                PhotoBridge.pendingCaption = caption.text.toString()
                setResult(Activity.RESULT_OK, Intent().putExtra("camera", true))
                finish()
            }
        }
    }

    private fun finishWithResult() {
        if (chosen.isEmpty()) {
            Toast.makeText(this, "Отметьте хотя бы одно фото", Toast.LENGTH_SHORT).show()
            return
        }
        val out = Intent()
        out.putStringArrayListExtra(EXTRA_URIS, ArrayList(chosen.map { it.toString() }))
        out.putExtra(EXTRA_CAPTION, caption.text.toString())
        setResult(Activity.RESULT_OK, out)
        finish()
    }

    /** Сетка миниатюр с номерами выбора. */
    inner class PhotoAdapter : BaseAdapter() {
        override fun getCount() = all.size
        override fun getItem(p: Int) = all[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(pos: Int, convert: View?, parent: ViewGroup?): View {
            val box: FrameLayout
            val img: ImageView
            val badge: TextView
            val shade: View
            val vlabel: TextView

            if (convert == null) {
                box = FrameLayout(this@PhotoPickerActivity)
                box.layoutParams = AbsListView.LayoutParams(cell, cell)

                img = ImageView(this@PhotoPickerActivity)
                img.scaleType = ImageView.ScaleType.CENTER_CROP
                img.layoutParams = FrameLayout.LayoutParams(cell, cell)
                img.setBackgroundColor(Color.parseColor("#EEE9F8"))
                img.clipToOutline = true
                img.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) {
                        o.setRoundRect(0, 0, v.width, v.height, dp(10).toFloat())
                    }
                }
                box.addView(img)

                shade = View(this@PhotoPickerActivity)
                shade.layoutParams = FrameLayout.LayoutParams(cell, cell)
                shade.setBackgroundColor(Color.parseColor("#557C3AED"))
                shade.visibility = View.GONE
                box.addView(shade)

                badge = TextView(this@PhotoPickerActivity)
                badge.textSize = 12f
                badge.gravity = Gravity.CENTER
                badge.setTextColor(Color.WHITE)
                val lp = FrameLayout.LayoutParams(dp(26), dp(26))
                lp.gravity = Gravity.TOP or Gravity.END
                lp.setMargins(0, dp(6), dp(6), 0)
                badge.layoutParams = lp
                box.addView(badge)

                // Метка видео с длительностью. Создаём один раз: если добавлять
                // её на лету, при прокрутке она остаётся на чужой плитке,
                // и фотографии выглядят как видео.
                vlabel = TextView(this@PhotoPickerActivity)
                vlabel.textSize = 11f
                vlabel.setTextColor(Color.WHITE)
                vlabel.setPadding(dp(6), dp(2), dp(6), dp(2))
                val vlp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                vlp.gravity = Gravity.BOTTOM or Gravity.START
                vlp.setMargins(dp(8), 0, 0, dp(8))
                vlabel.layoutParams = vlp
                vlabel.visibility = View.GONE
                box.addView(vlabel)

                box.tag = arrayOf(img, shade, badge, vlabel)
            } else {
                box = convert as FrameLayout
                val t = box.tag as Array<*>
                img = t[0] as ImageView
                shade = t[1] as View
                badge = t[2] as TextView
                vlabel = t[3] as TextView
            }

            val uri = all[pos]
            paintSelection(box, uri)

            if (isVideo.contains(uri)) {
                val ms = durations[uri] ?: 0L
                val sec = (ms / 1000).toInt()
                vlabel.text = if (sec > 0)
                    "▶  " + (sec / 60) + ":" + String.format("%02d", sec % 60)
                else "▶"
                vlabel.background = pillBg()
                vlabel.visibility = View.VISIBLE
            } else {
                vlabel.visibility = View.GONE
            }

            // подпись, что это видео
            // Миниатюра из кэша: без этого при каждом касании все фото
            // перезагружались заново и сетка «прыгала»
            val key = uri.toString()
            val cached = cache.get(key)
            if (cached != null) {
                img.setImageBitmap(cached)
            } else {
                img.setImageDrawable(null)
                img.setTag(R.drawable.ic_stat, key)
                CoroutineScope(Dispatchers.IO).launch {
                    val bmp = if (isVideo.contains(uri)) videoFrame(this@PhotoPickerActivity, uri, thumbPx)
                              else decodeScaled(this@PhotoPickerActivity, uri, thumbPx)
                    if (bmp != null) cache.put(key, bmp)
                    withContext(Dispatchers.Main) {
                        if (bmp != null && img.getTag(R.drawable.ic_stat) == key) {
                            img.setImageBitmap(bmp)
                        }
                    }
                }
            }
            return box
        }
    }

    /** Обновляет только отметку выбора, не трогая саму картинку. */
    private fun paintSelection(box: FrameLayout, uri: Uri) {
        val t = box.tag as Array<*>
        val shade = t[1] as View
        val badge = t[2] as TextView
        val idx = chosen.indexOf(uri)
        if (idx >= 0) {
            shade.visibility = View.VISIBLE
            badge.text = (idx + 1).toString()
            badge.background = roundBadge(Color.parseColor("#7C3AED"))
        } else {
            shade.visibility = View.GONE
            badge.text = ""
            badge.background = roundBadge(Color.parseColor("#55000000"))
        }
    }

    private fun pillBg(): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.cornerRadius = dp(9).toFloat()
        d.setColor(Color.parseColor("#A6000000"))
        return d
    }

    private fun roundBadge(color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        d.setStroke(dp(2), Color.WHITE)
        return d
    }
}

object PhotoBridge {

    var pendingBitmap: Bitmap? = null
    var pendingCaption: String = ""

    /** Уменьшает изображение и отдаёт готовый JPEG в base64. */
    fun toBase64(ctx: Context, uri: Uri, maxSide: Int, quality: Int): String? {
        val bmp = decodeScaled(ctx, uri, maxSide) ?: return null
        return bitmapToBase64(bmp, quality)
    }

    /** Читает файл целиком: нужно для видео, его пережимать нельзя. */
    fun fileToBase64(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.openInputStream(uri).use { input ->
            val bytes = input?.readBytes()
            if (bytes == null || bytes.size > 100 * 1024 * 1024) null
            else Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    } catch (e: Exception) {
        null
    }

    fun bitmapToBase64(bmp: Bitmap, quality: Int): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** Передаёт фотографии в страницу: она сама покажет их и отправит. */
    fun deliver(web: WebView, photos: List<Pair<String, String>>, captionText: String) {
        val arr = JSONArray()
        photos.forEach { (name, b64) ->
            val o = JSONObject()
            o.put("name", name)
            o.put("b64", b64)
            arr.put(o)
        }
        val js = "window.__nativePhotos && window.__nativePhotos(" +
                arr.toString() + "," + JSONObject.quote(captionText) + ");"
        web.post { web.evaluateJavascript(js, null) }
    }
}

/** Читает изображение сразу уменьшенным, чтобы не съесть память. */
/** Первый кадр видео для миниатюры. */
fun videoFrame(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
    val r = android.media.MediaMetadataRetriever()
    return try {
        r.setDataSource(ctx, uri)
        val bmp = r.getFrameAtTime(500000) ?: return null
        val side = maxOf(bmp.width, bmp.height)
        if (side <= maxSide) bmp
        else {
            val k = maxSide.toFloat() / side
            Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
        }
    } catch (e: Exception) {
        null
    } finally {
        try { r.release() } catch (e: Exception) { }
    }
}

fun decodeScaled(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        ctx.contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }

        var sample = 1
        val big = maxOf(bounds.outWidth, bounds.outHeight)
        while (big / sample > maxSide * 2) sample *= 2

        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        val bmp = ctx.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val side = maxOf(bmp.width, bmp.height)
        if (side <= maxSide) return bmp
        val k = maxSide.toFloat() / side
        Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true)
    } catch (e: Exception) {
        null
    }
}
