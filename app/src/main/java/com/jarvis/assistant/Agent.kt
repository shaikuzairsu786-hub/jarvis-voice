package com.jarvis.assistant

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class Agent(private val act: MainActivity) {

    class ApiError(val code: Int, val body: String) : Exception("API $code")

    @Volatile
    var pendingLaunch: Intent? = null
    private var webOk = true

    fun run(key: String, mode: String, history: List<Pair<String, String>>, userText: String): String {
        pendingLaunch = null
        val isJarvis = mode == "jarvis"
        val model = if (isJarvis) Cfg.MODEL_JARVIS else Cfg.MODEL_COMPANION
        val system = if (isJarvis) Cfg.PROMPT_JARVIS + "\n\n" + nowText() else Cfg.PROMPT_COMPANION

        val msgs = JSONArray()
        for ((r, t) in history) {
            msgs.put(JSONObject().put("role", r).put("content", t))
        }
        msgs.put(JSONObject().put("role", "user").put("content", userText))

        var finalText = ""
        for (step in 0 until 8) {
            val resp = callApi(key, model, system, msgs, isJarvis)
            val content = resp.getJSONArray("content")
            val stop = resp.optString("stop_reason")
            msgs.put(JSONObject().put("role", "assistant").put("content", content))
            finalText = textOf(content)

            if (stop == "tool_use") {
                val results = JSONArray()
                for (i in 0 until content.length()) {
                    val b = content.getJSONObject(i)
                    if (b.optString("type") == "tool_use") {
                        val out = try {
                            runTool(b.getString("name"), b.optJSONObject("input") ?: JSONObject())
                        } catch (e: Exception) {
                            "غلطی: ${e.message}"
                        }
                        results.put(
                            JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", b.getString("id"))
                                .put("content", out)
                        )
                    }
                }
                msgs.put(JSONObject().put("role", "user").put("content", results))
            } else if (stop != "pause_turn") {
                break
            }
        }
        return if (finalText.isBlank()) "ٹھیک ہے" else finalText
    }

    private fun nowText(): String {
        val f = DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd HH:mm", Locale.ENGLISH)
        val z = ZonedDateTime.now()
        return "موجودہ وقت: " + z.format(f) + " (ٹائم زون " + z.zone.id + ")"
    }

    private fun textOf(content: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        return sb.toString().trim()
    }

    // ---------- API ----------

    private fun callApi(key: String, model: String, system: String, msgs: JSONArray, isJarvis: Boolean): JSONObject {
        return try {
            post(key, buildBody(model, system, msgs, isJarvis, webOk))
        } catch (e: ApiError) {
            if (isJarvis && webOk && e.code == 400 && e.body.contains("web_search")) {
                webOk = false
                post(key, buildBody(model, system, msgs, isJarvis, false))
            } else {
                throw e
            }
        }
    }

    private fun buildBody(model: String, system: String, msgs: JSONArray, isJarvis: Boolean, web: Boolean): JSONObject {
        val b = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("system", system)
            .put("messages", msgs)
        if (isJarvis) {
            val tools = JSONArray()
            if (web) {
                tools.put(
                    JSONObject()
                        .put("type", "web_search_20250305")
                        .put("name", "web_search")
                        .put("max_uses", 3)
                )
            }
            for (t in customTools()) tools.put(t)
            b.put("tools", tools)
        }
        return b
    }

    private fun post(key: String, body: JSONObject): JSONObject {
        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 90000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.setRequestProperty("x-api-key", key)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw ApiError(code, resp)
            return JSONObject(resp)
        } finally {
            conn.disconnect()
        }
    }

    // ---------- ٹولز کی فہرست ----------

    private fun tool(name: String, desc: String, props: Map<String, String>, required: List<String>): JSONObject {
        val p = JSONObject()
        for ((k, d) in props) {
            p.put(k, JSONObject().put("type", "string").put("description", d))
        }
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", p)
            .put("required", JSONArray(required))
        return JSONObject().put("name", name).put("description", desc).put("input_schema", schema)
    }

    private fun customTools(): List<JSONObject> = listOf(
        tool(
            "open_maps",
            "Open Google Maps to search for a place or to navigate to it. Opens after your reply.",
            mapOf(
                "query" to "Place name or address",
                "mode" to "search or navigate"
            ),
            listOf("query", "mode")
        ),
        tool(
            "find_contact",
            "Find phone numbers in the user's contacts by name.",
            mapOf("name" to "Name or part of the name"),
            listOf("name")
        ),
        tool(
            "send_message",
            "Open a WhatsApp or SMS chat with the text already typed. It does NOT send; the user taps send.",
            mapOf(
                "app" to "whatsapp or sms",
                "phone" to "Phone number from find_contact",
                "text" to "Message text"
            ),
            listOf("app", "phone", "text")
        ),
        tool(
            "draft_email",
            "Open an email draft in the mail app. It does NOT send; the user taps send.",
            mapOf(
                "to" to "Recipient email address (can be empty)",
                "subject" to "Subject",
                "body" to "Email body"
            ),
            listOf("subject", "body")
        ),
        tool(
            "add_calendar_event",
            "Open the calendar with a new event filled in. The user taps save.",
            mapOf(
                "title" to "Event title",
                "start" to "Start time, local, format 2026-11-24T15:30",
                "end" to "End time, same format (optional, default 1 hour)",
                "location" to "Place (optional)",
                "attendees" to "Comma separated emails (optional)",
                "description" to "Notes (optional)"
            ),
            listOf("title", "start")
        ),
        tool(
            "set_reminder",
            "Schedule a notification on the phone at a given time.",
            mapOf(
                "message" to "Reminder text",
                "at" to "Time, local, format 2026-11-24T15:30"
            ),
            listOf("message", "at")
        ),
        tool(
            "read_messages",
            "Read the most recent incoming messages (WhatsApp, SMS, Gmail, etc.) seen in notifications.",
            emptyMap(),
            emptyList()
        ),
        tool(
            "reply_message",
            "Reply to an incoming message from read_messages. The app asks the user to confirm before sending.",
            mapOf(
                "id" to "Message id from read_messages",
                "text" to "Reply text"
            ),
            listOf("id", "text")
        )
    )

    // ---------- ٹولز کا عمل ----------

    private fun runTool(name: String, i: JSONObject): String {
        return when (name) {
            "open_maps" -> openMaps(i)
            "find_contact" -> findContact(i.optString("name"))
            "send_message" -> sendMessage(i)
            "draft_email" -> draftEmail(i)
            "add_calendar_event" -> addCalendar(i)
            "set_reminder" -> setReminder(i)
            "read_messages" -> readMessages()
            "reply_message" -> replyMessage(i)
            else -> "یہ ٹول موجود نہیں"
        }
    }

    private fun queueLaunch(intent: Intent, what: String): String {
        if (pendingLaunch != null) {
            return "ایک وقت میں ایک ہی ایپ کھل سکتی ہے۔ پہلے والی کھلنے دو، پھر دوبارہ کہنا"
        }
        pendingLaunch = intent
        return "ٹھیک ہے، جواب کے فوراً بعد $what کھل جائے گی"
    }

    private fun openMaps(i: JSONObject): String {
        val q = i.optString("query").trim()
        if (q.isEmpty()) return "جگہ کا نام خالی ہے"
        val nav = i.optString("mode") == "navigate"
        val url = if (nav) {
            "https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(q)
        } else {
            "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(q)
        }
        return queueLaunch(Intent(Intent.ACTION_VIEW, Uri.parse(url)), if (nav) "راستہ" else "نقشہ")
    }

    private fun findContact(name: String): String {
        if (act.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "کانٹیکٹ کی اجازت نہیں ملی۔ صارف سے کہو فون کی سیٹنگ میں ایپ کو کانٹیکٹ کی اجازت دے"
        }
        val sb = StringBuilder()
        val c = act.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%" + name.trim() + "%"),
            null
        )
        c?.use {
            var n = 0
            while (it.moveToNext() && n < 6) {
                sb.append(it.getString(0)).append(": ").append(it.getString(1)).append("\n")
                n++
            }
        }
        return if (sb.isEmpty()) "اس نام سے کوئی نہیں ملا" else sb.toString()
    }

    private fun waNumber(raw: String): String? {
        val s = raw.filter { it.isDigit() || it == '+' }
        if (s.startsWith("+")) return s.drop(1)
        if (s.startsWith("00")) return s.drop(2)
        if (s.startsWith("0")) {
            val code = act.countryCode()
            if (code.isBlank()) return null
            return code + s.drop(1)
        }
        return s
    }

    private fun sendMessage(i: JSONObject): String {
        val app = i.optString("app").lowercase(Locale.ROOT)
        val phone = i.optString("phone").trim()
        val text = i.optString("text")
        if (phone.isEmpty()) return "فون نمبر نہیں ہے۔ پہلے find_contact سے نمبر نکالو"
        if (app == "sms") {
            val clean = phone.filter { it.isDigit() || it == '+' }
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + clean))
            intent.putExtra("sms_body", text)
            return queueLaunch(intent, "ایس ایم ایس")
        }
        val n = waNumber(phone)
            ?: return "ملک کا کوڈ محفوظ نہیں ہے۔ صارف سے کہو ایپ میں ملک کا کوڈ (جیسے 92) لکھ کر محفوظ کرے"
        val url = "https://wa.me/" + n + "?text=" + Uri.encode(text)
        return queueLaunch(Intent(Intent.ACTION_VIEW, Uri.parse(url)), "واٹس ایپ")
    }

    private fun draftEmail(i: JSONObject): String {
        val intent = Intent(Intent.ACTION_SENDTO)
        intent.data = Uri.parse("mailto:")
        val to = i.optString("to").trim()
        if (to.isNotEmpty()) intent.putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
        intent.putExtra(Intent.EXTRA_SUBJECT, i.optString("subject"))
        intent.putExtra(Intent.EXTRA_TEXT, i.optString("body"))
        return queueLaunch(intent, "ای میل")
    }

    private fun parseTime(s: String): Long? {
        return try {
            val t = s.trim().replace(' ', 'T')
            LocalDateTime.parse(t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    private fun addCalendar(i: JSONObject): String {
        val title = i.optString("title").trim().ifEmpty { "میٹنگ" }
        val start = parseTime(i.optString("start"))
            ?: return "وقت کی شکل غلط ہے۔ ایسے لکھو: 2026-11-24T15:30"
        val endText = i.optString("end")
        val end = if (endText.isBlank()) start + 3600000L else (parseTime(endText) ?: (start + 3600000L))
        val intent = Intent(Intent.ACTION_INSERT)
        intent.data = CalendarContract.Events.CONTENT_URI
        intent.putExtra(CalendarContract.Events.TITLE, title)
        intent.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
        intent.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        val loc = i.optString("location").trim()
        if (loc.isNotEmpty()) intent.putExtra(CalendarContract.Events.EVENT_LOCATION, loc)
        val desc = i.optString("description").trim()
        if (desc.isNotEmpty()) intent.putExtra(CalendarContract.Events.DESCRIPTION, desc)
        val att = i.optString("attendees").trim()
        if (att.isNotEmpty()) intent.putExtra(Intent.EXTRA_EMAIL, att)
        return queueLaunch(intent, "کیلنڈر")
    }

    private fun setReminder(i: JSONObject): String {
        val msg = i.optString("message").trim().ifEmpty { "یاد دہانی" }
        val at = parseTime(i.optString("at"))
            ?: return "وقت کی شکل غلط ہے۔ ایسے لکھو: 2026-11-24T15:30"
        if (at < System.currentTimeMillis()) return "یہ وقت گزر چکا ہے"
        val am = act.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(act, ReminderReceiver::class.java)
        intent.putExtra("msg", msg)
        val pi = PendingIntent.getBroadcast(
            act,
            (at / 1000L).toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        return "یاد دہانی لگ گئی (چند منٹ کی دیر ہو سکتی ہے)"
    }

    private fun readMessages(): String {
        val l = Inbox.recent(8)
        if (l.isEmpty()) {
            return "کوئی پیغام نہیں ملا۔ شاید ایپ کو پیغامات پڑھنے کی اجازت نہیں دی گئی، یا ابھی کوئی نیا پیغام نہیں آیا"
        }
        val f = SimpleDateFormat("dd/MM HH:mm", Locale.ENGLISH)
        val sb = StringBuilder()
        for (m in l) {
            sb.append("id=").append(m.id)
                .append(" | ").append(m.app)
                .append(" | ").append(m.from)
                .append(" | ").append(f.format(Date(m.time)))
                .append(" | ").append(m.text)
                .append(" | جواب ممکن: ").append(if (m.ri != null && m.pi != null) "ہاں" else "نہیں")
                .append("\n")
        }
        return sb.toString()
    }

    private fun confirm(message: String): Boolean {
        val latch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        act.runOnUiThread {
            AlertDialog.Builder(act)
                .setMessage(message)
                .setPositiveButton("ہاں، بھیجو") { _, _ ->
                    ok.set(true)
                    latch.countDown()
                }
                .setNegativeButton("نہیں") { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .show()
        }
        latch.await(90, TimeUnit.SECONDS)
        return ok.get()
    }

    private fun replyMessage(i: JSONObject): String {
        val id = i.optString("id").trim().toIntOrNull() ?: return "id غلط ہے"
        val text = i.optString("text").trim()
        if (text.isEmpty()) return "جواب کا متن خالی ہے"
        val m = Inbox.get(id) ?: return "یہ پیغام اب نہیں ملا"
        val ri = m.ri
        val pi = m.pi
        if (ri == null || pi == null) {
            return "اس پیغام کا جواب یہاں سے نہیں جا سکتا۔ واٹس ایپ یا ایس ایم ایس کا مسودہ کھول کر دو"
        }
        if (!confirm(m.app + " پر " + m.from + " کو یہ بھیجوں؟\n\n" + text)) {
            return "صارف نے منع کر دیا، کچھ نہیں بھیجا"
        }
        return try {
            val intent = Intent()
            val bundle = Bundle()
            bundle.putCharSequence(ri.resultKey, text)
            RemoteInput.addResultsToIntent(arrayOf(ri), intent, bundle)
            pi.send(act, 0, intent)
            "جواب بھیج دیا"
        } catch (e: Exception) {
            "بھیج نہیں سکا: ${e.message}"
        }
    }
}
