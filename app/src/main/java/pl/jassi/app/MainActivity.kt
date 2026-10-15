package pl.jassi.app

import android.Manifest
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import pl.jassi.llama.Llama
import pl.jassi.whisper.Whisper

private const val GEMMA_URL = "https://huggingface.co/unsloth/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-IQ4_NL.gguf"
private const val WHISPER_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin"

class MainActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var orb: TextView
    private lateinit var tts: Tts
    private lateinit var actions: Actions
    private val brain = Brain()

    private var whisperHandle = 0L
    private var llamaReady = false
    private var job: Job? = null
    private var pulse: ValueAnimator? = null
    private val cyan = Color.parseColor("#00E5FF")

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    private val pickGemma = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> importModel(u, true) } }
    private val pickWhisper = registerForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { u -> importModel(u, false) } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        tts = Tts(this)
        actions = Actions(this)
        perms.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE))
        loadModels(auto = true)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#05080C"))
            setPadding(dp(20), dp(48), dp(20), dp(20))
        }
        root.addView(TextView(this).apply {
            text = "JasSI"; textSize = 36f; setTextColor(cyan)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); letterSpacing = 0.3f
        })
        status = TextView(this).apply { textSize = 14f; setTextColor(Color.parseColor("#7FB8C4")); gravity = Gravity.CENTER }
        root.addView(status)

        orb = TextView(this).apply {
            text = "MÓW"; gravity = Gravity.CENTER; textSize = 20f; setTextColor(cyan)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#0A1A22")); setStroke(dp(3), cyan)
            }
            layoutParams = LinearLayout.LayoutParams(dp(180), dp(180)).apply { topMargin = dp(28); bottomMargin = dp(28) }
            setOnClickListener { onOrb() }
        }
        root.addView(orb)

        logView = TextView(this).apply { textSize = 15f; setTextColor(Color.parseColor("#CFE9EF")) }
        scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(Button(this).apply { text = "Pobierz modele (Wi-Fi, ok. 1 GB)"; setOnClickListener { downloadModels() } },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply { text = "Wczytaj Gemma (.gguf)"; setOnClickListener { pickGemma.launch(arrayOf("*/*")) } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply { text = "Wczytaj Whisper (.bin)"; setOnClickListener { pickWhisper.launch(arrayOf("*/*")) } },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row)
        setContentView(root)
    }

    private fun setState(text: String, active: Boolean) {
        status.text = text
        if (active && pulse == null) {
            pulse = ValueAnimator.ofFloat(1f, 1.12f).apply {
                duration = 600; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
                addUpdateListener { orb.scaleX = it.animatedValue as Float; orb.scaleY = it.animatedValue as Float }
                start()
            }
        } else if (!active) {
            pulse?.cancel(); pulse = null; orb.scaleX = 1f; orb.scaleY = 1f
        }
    }

    private fun addLog(who: String, msg: String) {
        logView.append("$who: $msg\n\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun readyText() = when {
        whisperHandle == 0L -> "Wczytaj model Whisper (.bin) – to uszy."
        !llamaReady -> "Brak modelu Gemma (.gguf) – działają tylko komendy."
        else -> "Gotowy. Dotknij kółka i mów."
    }

    // ---------- modele ----------
    private fun loadModels(auto: Boolean = false) {
        lifecycleScope.launch {
            setState("Ładuję modele…", true)
            withContext(Dispatchers.IO) {
                if (whisperHandle == 0L) ModelStore.find(this@MainActivity, ".bin")?.let { whisperHandle = Whisper.load(it.absolutePath) }
                if (!llamaReady) ModelStore.find(this@MainActivity, ".gguf")?.let { llamaReady = Llama.load(it.absolutePath) }
            }
            setState(readyText(), false)
            if (auto && (whisperHandle == 0L || !llamaReady)) {
                val cm = getSystemService(ConnectivityManager::class.java)
                if (cm.activeNetwork != null && !cm.isActiveNetworkMetered) downloadModels()
                else status.text = "Brak modeli. Połącz z Wi-Fi i kliknij „Pobierz modele”."
            }
        }
    }

    private fun downloadModels() {
        lifecycleScope.launch {
            try {
                setState("Pobieram modele…", true)
                withContext(Dispatchers.IO) {
                    if (ModelStore.find(this@MainActivity, ".bin") == null)
                        ModelDownloader.download(this@MainActivity, WHISPER_URL, "whisper.bin") { d, t ->
                            runOnUiThread { status.text = "Pobieram Whisper… $d / $t MB" }
                        }
                    if (ModelStore.find(this@MainActivity, ".gguf") == null)
                        ModelDownloader.download(this@MainActivity, GEMMA_URL, "gemma.gguf") { d, t ->
                            runOnUiThread { status.text = "Pobieram Gemmę… $d / $t MB" }
                        }
                }
                loadModels()
            } catch (e: IOException) {
                setState("Błąd pobierania (${e.message}). Kliknij „Pobierz modele” – wznowi od miejsca przerwania.", false)
            }
        }
    }

    private fun importModel(uri: Uri, gemma: Boolean) {
        lifecycleScope.launch {
            job?.cancel()
            setState("Kopiuję model…", true)
            withContext(Dispatchers.IO) {
                if (gemma) { if (llamaReady) { Llama.release(); llamaReady = false } }
                else if (whisperHandle != 0L) { Whisper.free(whisperHandle); whisperHandle = 0L }
                ModelStore.import(this@MainActivity, uri, if (gemma) "gemma.gguf" else "whisper.bin") { mb ->
                    runOnUiThread { status.text = "Kopiuję model… $mb MB" }
                }
            }
            loadModels()
        }
    }

    // ---------- główny pipeline ----------
    private fun onOrb() {
        if (job?.isActive == true) { job?.cancel(); return }   // dotknięcie w trakcie = stop
        if (whisperHandle == 0L) { status.text = readyText(); return }
        job = lifecycleScope.launch {
            try {
                setState("Słucham…", true)
                val audio = Recorder.record()
                if (audio.isEmpty()) { addLog("JasSI", "(cisza)"); return@launch }

                setState("Rozpoznaję…", true)
                val text = withContext(Dispatchers.Default) { Whisper.transcribe(whisperHandle, audio, "pl", 4) }.trim()
                if (text.isBlank()) { addLog("JasSI", "(nie zrozumiałem)"); return@launch }
                addLog("Ty", text)

                val reply = actions.handle(text) ?: if (llamaReady) {
                    setState("Myślę…", true); brain.reply(text)
                } else "Model językowy nie jest wczytany."
                addLog("JasSI", reply)

                setState("Mówię…", true)
                tts.speak(reply)
            } finally {
                setState(readyText(), false)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.shutdown()
        if (whisperHandle != 0L) Whisper.free(whisperHandle)
        if (llamaReady) Llama.release()
    }
}
