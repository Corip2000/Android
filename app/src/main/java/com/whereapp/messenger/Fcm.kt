package com.whereapp.messenger

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Приём уведомлений через сервисы Google.
 *
 * Главное отличие от опроса в фоне: этот путь работает, даже когда
 * приложение полностью выгружено. Сервисы Google Play живут в системе
 * постоянно, и по команде сервера они сами поднимают наш процесс.
 * Именно так устроены уведомления в больших мессенджерах.
 *
 * Сообщения приходят только зашифрованными: расшифровка происходит
 * здесь, ключом, который веб-часть положила в настройки приложения.
 */
class FcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        FcmTokens.upload(this, token)
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        val d = msg.data
        val chatId = d["chatId"] ?: ""
        val title = d["title"] ?: "WhereApp"
        var body = d["body"] ?: "Новое сообщение"

        val ct = d["ct"]
        val iv = d["iv"]
        if (!ct.isNullOrEmpty() && !iv.isNullOrEmpty() && chatId.isNotEmpty()) {
            val text = FcmTokens.decrypt(this, chatId, ct, iv)
            if (text != null) {
                val sender = d["sender"]
                val group = d["group"]
                body = if (!group.isNullOrEmpty() && !sender.isNullOrEmpty()) "$sender: $text" else text
            }
        }

        // Открытый чат не тревожим
        val active = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("active", "")
        if (chatId.isNotEmpty() && chatId == active) return

        val tag = d["tag"] ?: System.currentTimeMillis().toString()
        FcmTokens.markShown(this, tag)   // чтобы фоновый опрос не показал это же второй раз
        val avatar = FcmTokens.loadAvatar(d["icon"])
        show(tag, title, body, avatar)
    }

    private fun show(id: String, title: String, body: String, avatar: Bitmap?) {
        val open = PendingIntent.getActivity(
            this, id.hashCode(), Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, "whereapp_msg_v2")
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_stat)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(open)
        if (avatar != null) n.setLargeIcon(avatar)
        getSystemService(NotificationManager::class.java).notify(id.hashCode(), n.build())
    }
}

object FcmTokens {

    private val http = OkHttpClient()

    /** Просит у Google адрес этого устройства и сохраняет его в базе. */
    /** Список недавно показанных сообщений — защита от двойных уведомлений. */
    fun markShown(ctx: Context, id: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = (p.getString("shown", "") ?: "").split(",").filter { it.isNotBlank() }
        val next = (listOf(id) + cur).distinct().take(60)
        p.edit().putString("shown", next.joinToString(",")).apply()
    }

    fun wasShown(ctx: Context, id: String): Boolean {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return (p.getString("shown", "") ?: "").split(",").contains(id)
    }

    fun register(ctx: Context) {
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { t ->
                if (t.isSuccessful) t.result?.let { upload(ctx, it) }
            }
        } catch (e: Exception) {
        }
    }

    fun upload(ctx: Context, token: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uid = p.getString("uid", null) ?: return
        if (p.getString("fcm_sent", "") == token + "|" + uid) return

        Thread {
            try {
                val body = JSONObject()
                body.put("token", token)
                body.put("uid", uid)
                body.put("updated_at", System.currentTimeMillis())

                val req = Request.Builder()
                    .url("$SUPA_URL/rest/v1/fcm_tokens?on_conflict=token")
                    .header("apikey", SUPA_KEY)
                    .header("Authorization", "Bearer $SUPA_KEY")
                    .header("Content-Type", "application/json")
                    .header("Prefer", "resolution=merge-duplicates")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                http.newCall(req).execute().use { r ->
                    if (r.isSuccessful) p.edit().putString("fcm_sent", token + "|" + uid).apply()
                }
            } catch (e: Exception) {
            }
        }.start()
    }

    /** Скачивает аватарку отправителя и делает её круглой. */
    fun loadAvatar(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        return try {
            val req = Request.Builder().url(url).build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return null
                val bytes = r.body?.bytes() ?: return null
                val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                val side = minOf(src.width, src.height)
                val sq = Bitmap.createBitmap(
                    src, (src.width - side) / 2, (src.height - side) / 2, side, side
                )
                val out = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
                val c = Canvas(out)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                c.drawCircle(side / 2f, side / 2f, side / 2f, paint)
                paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                c.drawBitmap(sq, 0f, 0f, paint)
                out
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Тот же AES-GCM, что и в браузере. */
    fun decrypt(ctx: Context, chatId: String, ctB64: String, ivB64: String): String? = try {
        val keyB64 = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("key_$chatId", null)
        if (keyB64 == null) null else {
            val key = SecretKeySpec(Base64.decode(keyB64, Base64.NO_WRAP), "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, key,
                GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP))
            )
            String(cipher.doFinal(Base64.decode(ctB64, Base64.NO_WRAP)), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        null
    }
}
