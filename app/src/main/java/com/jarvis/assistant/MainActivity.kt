package com.jarvis.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    companion object {
        const val REQ_MIC = 101
        const val REQ_ALL = 102
    }

    private var mode = "jarvis"
    private val histories = mapOf(
        "jarvis" to mutableListOf<Pair<String, String>>(),
        "companion" to mutableListOf<Pair<String, String>>()
    )
    private var pendingUser: String? = null

    @Volatile
    private var busy = false

    private val agent by lazy { Agent(this) }

    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    private lateinit var btnJarvis: Button
    private lateinit var btnCompanion: Button
    private lateinit var keyInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var notifBtn: Button
    private lateinit var scrollView: ScrollView
    private lateinit var logView: TextView
    private lateinit var statusView: TextView
    private lateinit var micBtn: Button

    private fun prefs() = getSharedPreferences(Cfg.PREFS, MODE_PRIVATE)

    fun countryCode(): String = prefs().getString(Cfg.KEY_CODE, "") ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        loadHist()

        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D1117"))
            setPadding(px(16), px(40), px(16), px(16))
        }

        // موڈ کے بٹن
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnJarvis = Button(this).apply {
            text = "JARVIS"
            setOnClickListener { setMode("jarvis") }
        }
        btnCompanion = Button(this).apply {
            text = "ساتھی"
            setOnClickListener { setMode("companion") }
        }
        val clearBtn = Button(this).apply {
            text = "صاف"
            setOnClickListener { clearHistory() }
        }
        modeRow.addView(btnJarvis, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        modeRow.addView(btnCompanion, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        modeRow.addView(clearBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.6f))
        root.addView(modeRow)

        // API key
        val keyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        keyInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 13f
            setSingleLine(true)
        }
        val saveBtn = Button(this).apply {
            text = "محفوظ"
            setOnClickListener { saveSettings() }
        }
        keyRow.addView(keyInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        keyRow.addView(saveBtn)
        root.addView(keyRow)

        // ملک کا کوڈ اور پیغامات کی اجازت
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        codeInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 13f
            setSingleLine(true)
        }
        notifBtn = Button(this).apply {
            textSize = 12f
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        row2.addView(codeInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(notifBtn)
        root.addView(row2)
        refreshHints()

        // گفتگو
        logView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            setPadding(px(8), px(8), px(8), px(8))
        }
        scrollView = ScrollView(this).apply { addView(logView) }
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        statusView = TextView(this).apply {
            setTextColor(Color.parseColor("#8B949E"))
            textSize = 14f
            text = "تیار"
            setPadding(px(8), px(8), px(8), px(8))
        }
        root.addView(statusView)

        micBtn = Button(this).apply {
            text = Cfg.MIC_IDLE
            textSize = 22f
            setOnClickListener {
                if (listening) stopListening() else startListening()
            }
        }
        root.addView(micBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(72)))

        setContentView(root)
        setMode("jarvis")
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshHints()
    }

    // ---------- اجازتیں ----------

    private fun askPermissions() {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.READ_CONTACTS)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), REQ_ALL)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startListening()
            } else {
                statusView.text = "مائیک کی اجازت کے بغیر سن نہیں سکتا"
            }
        }
    }

    private fun listenerEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return s.contains(packageName)
    }

    // ---------- سیٹنگز ----------

    private fun savedKey(): String = prefs().getString(Cfg.KEY_API, "") ?: ""

    private fun refreshHints() {
        keyInput.hint = if (savedKey().isBlank()) "Anthropic API key یہاں لکھو"
        else "key محفوظ ہے (بدلنے کے لیے نئی لکھو)"
        val code = countryCode()
        codeInput.hint = if (code.isBlank()) "ملک کا کوڈ، جیسے 92"
        else "ملک کا کوڈ: $code"
        notifBtn.text = if (listenerEnabled()) "پیغامات: چالو ✓" else "پیغامات کی اجازت دو"
    }

    private fun saveSettings() {
        val k = keyInput.text.toString().trim()
        val c = codeInput.text.toString().trim()
        if (k.isEmpty() && c.isEmpty()) {
            Toast.makeText(this, "کچھ لکھا نہیں", Toast.LENGTH_SHORT).show()
            return
        }
        val e = prefs().edit()
        if (k.isNotEmpty()) e.putString(Cfg.KEY_API, k)
        if (c.isNotEmpty()) e.putString(Cfg.KEY_CODE, c)
        e.apply()
        keyInput.setText("")
        codeInput.setText("")
        refreshHints()
        statusView.text = "محفوظ ہو گیا"
    }

    // ---------- گفتگو کا ریکارڈ ----------

    private fun saveHist() {
        val e = prefs().edit()
        for ((m, h) in histories) {
            val a = JSONArray()
            for ((r, t) in h) a.put(JSONArray().put(r).put(t))
            e.putString("hist_" + m, a.toString())
        }
        e.apply()
    }

    private fun loadHist() {
        for ((m, h) in histories) {
            val s = prefs().getString("hist_" + m, null) ?: continue
            try {
                val a = JSONArray(s)
                for (i in 0 until a.length()) {
                    val p = a.getJSONArray(i)
                    h.add(Pair(p.getString(0), p.getString(1)))
                }
            } catch (e: Exception) {
                h.clear()
            }
        }
    }

    private fun clearHistory() {
        histories.getValue(mode).clear()
        saveHist()
        renderLog()
        statusView.text = "گفتگو صاف ہو گئی"
    }

    private fun setMode(m: String) {
        mode = m
        btnJarvis.alpha = if (m == "jarvis") 1f else 0.5f
        btnCompanion.alpha = if (m == "companion") 1f else 0.5f
        renderLog()
    }

    private fun renderLog() {
        val name = if (mode == "jarvis") "JARVIS" else "نور"
        val sb = StringBuilder()
        for ((role, text) in histories.getValue(mode)) {
            sb.append(if (role == "user") "تم: " else name + ": ").append(text).append("\n\n")
        }
        val p = pendingUser
        if (p != null) sb.append("تم: ").append(p).append("\n\n")
        logView.text = sb.toString()
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // ---------- سننا ----------

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onError(error: Int) {
            listening = false
            micBtn.text = Cfg.MIC_IDLE
            statusView.text =
                if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) "کچھ سنائی نہیں دیا، دوبارہ دباؤ"
                else "آواز کی غلطی (کوڈ $error)"
        }

        override fun onResults(results: Bundle?) {
            listening = false
            micBtn.text = Cfg.MIC_IDLE
            val t = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!t.isNullOrBlank()) send(t) else statusView.text = "کچھ سمجھ نہیں آیا"
        }
    }

    private fun startListening() {
        if (busy) {
            statusView.text = "ذرا رکو، پچھلا کام چل رہا ہے"
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusView.text = "آواز پہچاننے کی سروس نہیں ملی (Google ایپ چیک کرو)"
            return
        }
        tts?.stop()
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(listener)
            }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ur-PK")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        listening = true
        micBtn.text = "⏹ سن رہا ہوں…"
        statusView.text = "بولو…"
        recognizer?.startListening(intent)
    }

    private fun stopListening() {
        recognizer?.stopListening()
    }

    // ---------- سوچنا ----------

    private fun friendly(e: Exception): String {
        if (e is Agent.ApiError) {
            if (e.code == 401) return "API key غلط ہے، دوبارہ لکھ کر محفوظ کرو"
            if (e.code == 429) return "ابھی بہت رش ہے، تھوڑا رک کر دوبارہ بولو"
            if (e.body.contains("credit")) return "API اکاؤنٹ میں پیسے ختم ہیں"
            return "API غلطی " + e.code + ": " + e.body.take(150)
        }
        if (e is java.io.IOException) return "انٹرنیٹ چیک کرو"
        return "غلطی: " + e.message
    }

    private fun send(text: String) {
        val key = savedKey()
        if (key.isBlank()) {
            statusView.text = "پہلے API key محفوظ کرو"
            return
        }
        val m = mode
        busy = true
        pendingUser = text
        renderLog()
        statusView.text = "سوچ رہا ہوں…"
        thread {
            try {
                val reply = agent.run(key, m, histories.getValue(m).toList(), text)
                runOnUiThread {
                    val h = histories.getValue(m)
                    h.add(Pair("user", text))
                    h.add(Pair("assistant", reply))
                    while (h.size > 20) {
                        h.removeAt(0)
                        h.removeAt(0)
                    }
                    saveHist()
                    pendingUser = null
                    busy = false
                    renderLog()
                    statusView.text = "تیار"
                    speak(reply)
                    val launch = agent.pendingLaunch
                    if (launch != null) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            try {
                                startActivity(launch)
                            } catch (e: Exception) {
                                statusView.text = "ایپ نہیں کھل سکی"
                            }
                        }, 1500)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    pendingUser = null
                    busy = false
                    renderLog()
                    statusView.text = friendly(e)
                }
            }
        }
    }

    // ---------- بولنا ----------

    override fun onInit(code: Int) {
        if (code == TextToSpeech.SUCCESS) {
            val r = tts?.setLanguage(Locale("ur", "PK"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                statusView.text = "اردو آواز نہیں ملی: فون کی Text-to-speech سیٹنگ میں اردو ڈاؤن لوڈ کرو"
            }
        } else {
            statusView.text = "بولنے والی سروس شروع نہیں ہوئی"
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        recognizer?.destroy()
        super.onDestroy()
    }
}
