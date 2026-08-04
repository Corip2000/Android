package com.whereapp.messenger

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

const val SITE = "https://corip2000.github.io/"
const val SUPA_URL = "https://zvdwimgmbxhvvdnmjuag.supabase.co"
const val SUPA_KEY = "sb_publishable_0FbeQ-Y9mtRqlKHmKRXrHw_RjI-EHa1"
const val PREFS = "whereapp"

/* ============================================================
   Экран приложения: тот же сайт в WebView.
   Чаты, звонки и шифрование работают как в браузере,
   отличие одно — рядом крутится служба уведомлений.
   ============================================================ */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null
        cb.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        )
    }

    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val data = result.data ?: return@registerForActivityResult

        if (data.getBooleanExtra("camera", false)) {
            val bmp = PhotoBridge.pendingBitmap ?: return@registerForActivityResult
            PhotoBridge.pendingBitmap = null
            val b64 = PhotoBridge.bitmapToBase64(bmp, 85)
            PhotoBridge.deliver(web, listOf("camera.jpg" to b64), PhotoBridge.pendingCaption)
            return@registerForActivityResult
        }

        val uris = data.getStringArrayListExtra(PhotoPickerActivity.EXTRA_URIS) ?: return@registerForActivityResult
        val cap = data.getStringExtra(PhotoPickerActivity.EXTRA_CAPTION) ?: ""
        Thread {
            val out = ArrayList<Pair<String, String>>()
            uris.forEachIndexed { i, u ->
                val uri = Uri.parse(u)
                val type = contentResolver.getType(uri) ?: ""
                if (type.startsWith("video/")) {
                    // видео отдаём как есть, без пережатия
                    val b64 = PhotoBridge.fileToBase64(this, uri)
                    if (b64 != null) out.add("video_$i.mp4|$type" to b64)
                } else {
                    val b64 = PhotoBridge.toBase64(this, uri, 1600, 85)
                    if (b64 != null) out.add("photo_$i.jpg" to b64)
                }
            }
            PhotoBridge.deliver(web, out, cap)
        }.start()
    }

    private val permissionAsker = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askPermissions()

        web = WebView(this)
        setContentView(web)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = true
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.addJavascriptInterface(Bridge(this), "WhereAppNative")
        web.addJavascriptInterface(CallBridge(this), "WhereAppCall")

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                val url = req?.url?.toString() ?: return false
                if (url.startsWith(SITE)) return false
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                try {
                    val pick = Intent(Intent.ACTION_GET_CONTENT)
                    pick.addCategory(Intent.CATEGORY_OPENABLE)
                    pick.type = "*/*"
                    val multi = params != null && params.mode == FileChooserParams.MODE_OPEN_MULTIPLE
                    pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multi)
                    val types = params?.acceptTypes
                    if (types != null && types.isNotEmpty()) {
                        pick.putExtra(Intent.EXTRA_MIME_TYPES, types)
                    }
                    filePicker.launch(Intent.createChooser(pick, "Выберите файл"))
                    return true
                } catch (e: Exception) {
                    filePathCallback = null
                    callback?.onReceiveValue(null)
                    return false
                }
            }
        }

        // Скачивание файлов: WebView сам этого не умеет, сохраняем в галерею
        web.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            Downloads.save(this, url, mimeType, contentDisposition)
        }

        // Кнопки громкости по умолчанию управляют звуком вызова во время разговора
        volumeControlStream = android.media.AudioManager.STREAM_VOICE_CALL

        if (savedInstanceState == null) web.loadUrl(SITE)
        WatchService.start(this)
        FcmTokens.register(this)
    }

    private fun askPermissions() {
        val need = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) {
            need.add(Manifest.permission.READ_MEDIA_IMAGES)
            need.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            need.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionAsker.launch(missing.toTypedArray())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState); web.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState); web.restoreState(savedInstanceState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    /** Мост: сайт передаёт сюда ключи чатов, чтобы служба показала текст. */
    /**
     * Во время разговора качелька громкости должна менять громкость вызова.
     * По умолчанию Android отдаёт её медиапотоку, поэтому ползунок появлялся,
     * но на разговор не влиял.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (CallAudio.isActive() &&
            (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
             keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val dir = if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP)
                android.media.AudioManager.ADJUST_RAISE
            else android.media.AudioManager.ADJUST_LOWER
            am.adjustStreamVolume(
                android.media.AudioManager.STREAM_VOICE_CALL, dir,
                android.media.AudioManager.FLAG_SHOW_UI
            )
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    fun openPhotoPicker() {
        runOnUiThread {
            try {
                photoPicker.launch(Intent(this, PhotoPickerActivity::class.java))
            } catch (e: Exception) {
                Toast.makeText(this, "Не удалось открыть выбор фото", Toast.LENGTH_SHORT).show()
            }
        }
    }

    class Bridge(private val ctx: Context) {
        private fun p() = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        @JavascriptInterface
        fun setUid(uid: String) {
            p().edit().putString("uid", uid).apply()
            WatchService.start(ctx)
            FcmTokens.register(ctx)      // адрес устройства привязываем к аккаунту
        }

        @JavascriptInterface
        fun saveKey(chatId: String, keyB64: String) {
            p().edit().putString("key_$chatId", keyB64).apply()
        }

        @JavascriptInterface
        fun saveName(uid: String, name: String) {
            p().edit().putString("name_$uid", name).apply()
        }

        @JavascriptInterface
        fun saveToGallery(dataUrl: String, name: String) {
            Downloads.saveDataUrl(ctx, dataUrl, name)
        }

        @JavascriptInterface
        fun pickPhotos() {
            (ctx as? MainActivity)?.openPhotoPicker()
        }

        @JavascriptInterface
        fun setActiveChat(chatId: String) {
            p().edit().putString("active", chatId).apply()
        }
    }
}

/* ============================================================
   Служба переднего плана.
   Постоянное уведомление в шторке не даёт системе выгрузить
   процесс — благодаря этому сообщения приходят при закрытом
   приложении. Веб-странице такое недоступно в принципе.
   ============================================================ */
class WatchService : Service() {

    companion object {
        private const val CH_SILENT = "whereapp_service"
        private const val CH_ALERT = "whereapp_msg_v2"
        private const val ONGOING_ID = 1
        private const val POLL_MS = 8_000L

        fun start(ctx: Context) {
            val i = Intent(ctx, WatchService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(ONGOING_ID, ongoingNotification())
        startLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (job?.isActive != true) startLoop()
        return START_STICKY
    }

    override fun onDestroy() { job?.cancel(); super.onDestroy() }

    override fun onTaskRemoved(rootIntent: Intent?) {
        start(this)
        super.onTaskRemoved(rootIntent)
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SILENT, "Работа в фоне", NotificationManager.IMPORTANCE_MIN).apply {
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Сообщения", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                enableLights(true)
                setSound(
                    android.media.RingtoneManager.getDefaultUri(
                        android.media.RingtoneManager.TYPE_NOTIFICATION
                    ),
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
        )
    }

    private fun openIntent(code: Int) = PendingIntent.getActivity(
        this, code, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun ongoingNotification(): Notification =
        NotificationCompat.Builder(this, CH_SILENT)
            .setContentTitle("WhereApp на связи")
            .setContentText("Следит за новыми сообщениями")
            .setSmallIcon(R.drawable.ic_stat)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(openIntent(0))
            .build()

    private fun startLoop() {
        job = scope.launch {
            while (isActive) {
                try { poll() } catch (_: Exception) { }
                delay(POLL_MS)
            }
        }
    }

    private fun get(path: String): String? {
        val req = Request.Builder()
            .url("$SUPA_URL/rest/v1/$path")
            .header("apikey", SUPA_KEY)
            .header("Authorization", "Bearer $SUPA_KEY")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            return r.body?.string()
        }
    }

    private fun poll() {
        val p = prefs()
        val uid = p.getString("uid", null) ?: return
        var since = p.getLong("since", 0L)
        if (since == 0L) {
            p.edit().putLong("since", System.currentTimeMillis()).apply()
            return
        }

        val chatsJson = get(
            "chats?select=id,type,name,members&members=cs." + URLEncoder.encode("{$uid}", "UTF-8")
        ) ?: return
        val chats = JSONArray(chatsJson)
        if (chats.length() == 0) return

        val ids = ArrayList<String>()
        val titles = HashMap<String, String>()
        val groups = HashMap<String, Boolean>()
        for (i in 0 until chats.length()) {
            val c = chats.getJSONObject(i)
            val id = c.getString("id")
            ids.add(id)
            val type = c.optString("type", "direct")
            groups[id] = (type == "group")
            titles[id] = if (type == "group") c.optString("name", "Группа") else ""
        }

        val inList = ids.joinToString(",") { "\"$it\"" }
        val msgsJson = get(
            "messages?select=id,chat_id,from_uid,ts,kind,ct,iv" +
                    "&chat_id=in.(" + URLEncoder.encode(inList, "UTF-8") + ")" +
                    "&ts=gt.$since&order=ts.asc&limit=25"
        ) ?: return

        val msgs = JSONArray(msgsJson)
        if (msgs.length() == 0) return

        var maxTs = since
        val active = p.getString("active", "") ?: ""

        for (i in 0 until msgs.length()) {
            val m = msgs.getJSONObject(i)
            val ts = m.optLong("ts", 0L)
            if (ts > maxTs) maxTs = ts

            val from = m.optString("from_uid", "")
            if (from == uid) continue
            if (m.optString("kind", "") == "sys") continue

            val chatId = m.optString("chat_id", "")
            if (chatId == active && isForeground()) continue

            val sender = senderName(from)
            val group = groups[chatId] == true
            val title = if (group) (titles[chatId] ?: "Группа") else sender

            var body = when (m.optString("kind", "text")) {
                "image" -> "\uD83D\uDCF7 Фото"
                "audio" -> "\uD83C\uDFA4 Голосовое"
                "video" -> "\uD83C\uDFAC Видео"
                "file" -> "\uD83D\uDCCE Файл"
                else -> "Новое сообщение"
            }
            if (m.optString("kind", "") == "text" && !m.isNull("ct")) {
                decrypt(chatId, m.optString("ct"), m.optString("iv"))?.let { body = it }
            }
            if (group) body = "$sender: $body"

            val mid = m.optString("id", ts.toString())
            if (FcmTokens.wasShown(this, mid)) continue   // уже показано через Google
            showMessage(mid, title, body)
        }

        p.edit().putLong("since", maxTs).apply()
    }

    private fun senderName(uid: String): String {
        prefs().getString("name_$uid", null)?.let { return it }
        return try {
            val js = get("profiles?select=name&id=eq." + URLEncoder.encode(uid, "UTF-8"))
            val arr = JSONArray(js ?: "[]")
            val name = if (arr.length() > 0) arr.getJSONObject(0).optString("name", "Сообщение")
                       else "Сообщение"
            prefs().edit().putString("name_$uid", name).apply()
            name
        } catch (e: Exception) { "Сообщение" }
    }

    /** Тот же алгоритм, что в браузере: AES-GCM, метка 128 бит. */
    private fun decrypt(chatId: String, ctB64: String, ivB64: String): String? = try {
        val keyB64 = prefs().getString("key_$chatId", null)
        if (keyB64 == null) null else {
            val key = SecretKeySpec(Base64.decode(keyB64, Base64.NO_WRAP), "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key,
                GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP)), Charsets.UTF_8)
        }
    } catch (e: Exception) { null }

    private fun isForeground(): Boolean = try {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.runningAppProcesses?.any {
            it.processName == packageName &&
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        } ?: false
    } catch (e: Exception) { false }

    private fun showMessage(id: String, title: String, body: String) {
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_stat)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openIntent(id.hashCode()))
            .build()
        // Свой номер у каждого сообщения, иначе система подменяет предыдущее
        getSystemService(NotificationManager::class.java).notify(id.hashCode(), n)
    }
}

/** Поднимает службу после перезагрузки телефона. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) WatchService.start(context)
    }
}
