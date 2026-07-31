package com.whereapp.messenger

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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

class PhotoPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URIS = "uris"
        const val EXTRA_CAPTION = "caption"
        private const val THUMB = 220
    }

    private val all = ArrayList<Uri>()
    private val chosen = ArrayList<Uri>()
    private lateinit var grid: GridView
    private lateinit var title: TextView
    private lateinit var caption: EditText
    private lateinit var adapter: PhotoAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.WHITE)

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

        grid = GridView(this)
        grid.numColumns = 3
        grid.horizontalSpacing = dp(3)
        grid.verticalSpacing = dp(3)
        grid.setPadding(dp(3), dp(3), dp(3), dp(3))
        grid.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )

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

        adapter = PhotoAdapter()
        grid.adapter = adapter
        grid.setOnItemClickListener { _, _, pos, _ -> toggle(all[pos]) }

        loadPhotos()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun loadPhotos() {
        CoroutineScope(Dispatchers.IO).launch {
            val cols = arrayOf(MediaStore.Images.Media._ID)
            var c: Cursor? = null
            try {
                c = contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cols, null, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC"
                )
                var n = 0
                while (c != null && c.moveToNext() && n < 400) {
                    val id = c.getLong(0)
                    all.add(
                        Uri.withAppendedPath(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                        )
                    )
                    n++
                }
            } catch (e: Exception) {
            } finally {
                c?.close()
            }
            withContext(Dispatchers.Main) {
                adapter.notifyDataSetChanged()
                if (all.isEmpty()) {
                    Toast.makeText(
                        this@PhotoPickerActivity,
                        "Фотографии не найдены. Разрешите доступ к файлам в настройках.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun toggle(u: Uri) {
        if (chosen.contains(u)) chosen.remove(u) else chosen.add(u)
        title.text = if (chosen.isEmpty()) "Выберите фото" else "Выбрано: " + chosen.size
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

    inner class PhotoAdapter : BaseAdapter() {
        override fun getCount() = all.size
        override fun getItem(p: Int) = all[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(pos: Int, convert: View?, parent: ViewGroup?): View {
            val size = (resources.displayMetrics.widthPixels - dp(12)) / 3

            val box = FrameLayout(this@PhotoPickerActivity)
            box.layoutParams = AbsListView.LayoutParams(size, size)

            val img = ImageView(this@PhotoPickerActivity)
            img.scaleType = ImageView.ScaleType.CENTER_CROP
            img.layoutParams = FrameLayout.LayoutParams(size, size)
            img.setBackgroundColor(Color.parseColor("#EEE9F8"))
            box.addView(img)

            val badge = TextView(this@PhotoPickerActivity)
            val idx = chosen.indexOf(all[pos])
            badge.text = if (idx >= 0) (idx + 1).toString() else ""
            badge.textSize = 12f
            badge.gravity = Gravity.CENTER
            badge.setTextColor(Color.WHITE)
            badge.setBackgroundColor(
                if (idx >= 0) Color.parseColor("#7C3AED") else Color.parseColor("#66000000")
            )
            val lp = FrameLayout.LayoutParams(dp(24), dp(24))
            lp.gravity = Gravity.TOP or Gravity.END
            lp.setMargins(0, dp(5), dp(5), 0)
            badge.layoutParams = lp
            box.addView(badge)

            if (idx >= 0) box.setPadding(dp(6), dp(6), dp(6), dp(6)) else box.setPadding(0, 0, 0, 0)

            val uri = all[pos]
            CoroutineScope(Dispatchers.IO).launch {
                val bmp = decodeScaled(this@PhotoPickerActivity, uri, THUMB)
                withContext(Dispatchers.Main) { if (bmp != null) img.setImageBitmap(bmp) }
            }
            return box
        }
    }
}

object PhotoBridge {

    var pendingBitmap: Bitmap? = null
    var pendingCaption: String = ""

    fun toBase64(ctx: Context, uri: Uri, maxSide: Int, quality: Int): String? {
        val bmp = decodeScaled(ctx, uri, maxSide) ?: return null
        return bitmapToBase64(bmp, quality)
    }

    fun bitmapToBase64(bmp: Bitmap, quality: Int): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun deliver(web: WebView, photos: List<Pair<String, String>>, captionText: String) {
        val arr = JSONArray()
        photos.forEach { pair ->
            val o = JSONObject()
            o.put("name", pair.first)
            o.put("b64", pair.second)
            arr.put(o)
        }
        val js = "window.__nativePhotos && window.__nativePhotos(" +
                arr.toString() + "," + JSONObject.quote(captionText) + ");"
        web.post { web.evaluateJavascript(js, null) }
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
