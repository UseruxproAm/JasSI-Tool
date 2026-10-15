package pl.jassi.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.sqrt

/** Nagrywa 16 kHz mono; kończy po ~1.2 s ciszy po mowie. Zwraca pustą tablicę, jeśli nikt nic nie powiedział. */
object Recorder {
    private const val SR = 16000

    @SuppressLint("MissingPermission")
    suspend fun record(): FloatArray = withContext(Dispatchers.IO) {
        val frame = SR / 10
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, max(minBuf, frame * 8)
        )
        val frames = ArrayList<ShortArray>()
        var noise = 0.0
        var spoke = false
        var silent = 0
        var count = 0
        rec.startRecording()
        try {
            val buf = ShortArray(frame)
            while (isActive && count < 150) {                 // max 15 s
                val n = rec.read(buf, 0, frame)
                if (n <= 0) continue
                count++
                frames.add(buf.copyOf(n))
                var sum = 0.0
                for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
                val rms = sqrt(sum / n)
                if (count <= 3) { noise = max(noise, rms); continue }   // kalibracja szumu
                val thr = max(700.0, noise * 2.5)
                if (rms > thr) { spoke = true; silent = 0 } else if (spoke) silent++
                if (spoke && silent >= 12) break              // 1.2 s ciszy
                if (!spoke && count > 60) break               // 6 s bez mowy
            }
        } finally {
            rec.stop(); rec.release()
        }
        if (!spoke) return@withContext FloatArray(0)
        val total = frames.sumOf { it.size }
        val out = FloatArray(total)
        var k = 0
        for (f in frames) for (s in f) out[k++] = s / 32768f
        out
    }
}
