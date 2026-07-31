package com.whereapp.messenger

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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

        val active = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("active", "")
        if (chatId.isNotEmpty() && chatId == active) return

        show(d["tag"] ?: System.currentTimeMillis().toString(), title, body)
    }

    private fun show(id: String, title: String, body: String) {
        val open = PendingIntent.getActivity(
            this, id.hashCode(), Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, "whereapp_messages")
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_stat)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        getSystemService(NotificationManager::class.java).notify(id.hashCode(), n)
    }
}

object FcmTokens {

    private val http = OkHttpClient()

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