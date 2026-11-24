package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class Msg(
    val id: Int,
    val app: String,
    val from: String,
    val text: String,
    val time: Long,
    val pi: PendingIntent?,
    val ri: RemoteInput?
)

object Inbox {
    val APPS = mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "com.google.android.gm" to "Gmail",
        "com.google.android.apps.messaging" to "SMS",
        "com.samsung.android.messaging" to "SMS",
        "org.telegram.messenger" to "Telegram",
        "com.facebook.orca" to "Messenger",
        "com.instagram.android" to "Instagram"
    )

    private val list = ArrayList<Msg>()
    private var nextId = 1

    @Synchronized
    fun add(pkg: String, from: String, text: String, pi: PendingIntent?, ri: RemoteInput?) {
        val app = APPS[pkg] ?: pkg
        val dup = list.takeLast(5).any { it.app == app && it.from == from && it.text == text }
        if (dup) return
        list.add(Msg(nextId, app, from, text, System.currentTimeMillis(), pi, ri))
        nextId++
        while (list.size > 30) list.removeAt(0)
    }

    @Synchronized
    fun recent(n: Int): List<Msg> = list.takeLast(n)

    @Synchronized
    fun get(id: Int): Msg? = list.firstOrNull { it.id == id }
}

class MsgListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        try {
            if (sbn == null) return
            if (sbn.packageName == packageName) return
            val n = sbn.notification ?: return
            if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
            val isMsg = n.category == Notification.CATEGORY_MESSAGE ||
                n.category == Notification.CATEGORY_EMAIL ||
                Inbox.APPS.containsKey(sbn.packageName)
            if (!isMsg) return
            val ex = n.extras ?: return
            val from = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
            val text = (ex.getCharSequence(Notification.EXTRA_TEXT)
                ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString() ?: return
            var pi: PendingIntent? = null
            var ri: RemoteInput? = null
            val acts = n.actions
            if (acts != null) {
                for (a in acts) {
                    val inputs = a.remoteInputs
                    if (inputs != null && inputs.isNotEmpty()) {
                        ri = inputs[0]
                        pi = a.actionIntent
                        break
                    }
                }
            }
            Inbox.add(sbn.packageName, from, text, pi, ri)
        } catch (e: Exception) {
            // نوٹیفکیشن پڑھنے میں مسئلہ ہو تو چپ چاپ چھوڑ دو
        }
    }
}

// =====================================================================
//  یاد دہانی کا نوٹیفکیشن
// =====================================================================

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val msg = intent.getStringExtra("msg") ?: "یاد دہانی"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("jarvis_rem", "یاد دہانی", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(context, "jarvis_rem")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("JARVIS")
            .setContentText(msg)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % 100000L).toInt(), n)
    }
}
