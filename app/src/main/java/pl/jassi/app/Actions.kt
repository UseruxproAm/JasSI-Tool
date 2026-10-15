package pl.jassi.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.ContactsContract
import java.text.Normalizer
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max

/**
 * "Ręce i nogi": rozpoznaje polskie komendy i wykonuje je na telefonie.
 * handle() zwraca tekst do powiedzenia albo null, jeśli to nie komenda (wtedy odpowiada Gemma).
 */
class Actions(private val ctx: Context) {

    fun norm(s: String): String {
        val d = Normalizer.normalize(s.lowercase(Locale("pl")), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('ł', 'l')
        return d.replace(Regex("[,;:!?\"'()]"), " ")
            .replace(Regex("\\.+(\\s|$)"), " ")
            .replace(Regex("\\s+"), " ").trim()
    }

    fun handle(raw: String): String? {
        val t = norm(raw)
        if (t.isEmpty()) return null

        Regex("^(?:prosze )?(?:zadzwon|dzwon|zatelefonuj|polacz sie)(?: do| z)? (.+)$").find(t)?.let {
            return call(it.groupValues[1])
        }
        if (t.contains("latark")) {
            val off = t.contains("wylacz") || t.contains("zgas")
            return torch(!off)
        }
        if (t.contains("ktora godzina") || t.contains("jaka jest godzina") || t.contains("ktora jest")) {
            return "Jest ${LocalTime.now().format(DateTimeFormatter.ofPattern("H:mm"))}."
        }
        Regex("^(?:wyszukaj|znajdz|wygoogluj|szukaj|sprawdz)(?: w google| w internecie)? (.+)$").find(t)?.let {
            return search(it.groupValues[1])
        }
        Regex("^(?:prosze )?(otworz|uruchom|odpal|wlacz|wejdz na|wejdz w|idz na)\\s+(.+)$").find(t)?.let {
            val verb = it.groupValues[1]
            val rest = it.groupValues[2]
            return open(verb, rest)
        }
        return null
    }

    // ---------- otwieranie ----------
    private val browserWords = setOf("przegladarke", "przegladarka", "chrome", "chrome'a", "internet", "firefoxa", "firefox", "opere", "browser")

    private fun open(verb: String, rest: String): String {
        if (rest in browserWords || rest.startsWith("przegladark")) return openBrowser()
        Regex("^(?:strone|witryne|stronke|adres|www)\\s+(.+)$").find(rest)?.let { return website(it.groupValues[1]) }
        if (Regex("^[a-z0-9-]+( kropka |\\.)[a-z0-9-]+.*$").matches(rest)) return website(rest)
        if (verb.startsWith("wejdz") || verb.startsWith("idz")) return website(rest)
        return openApp(rest)
    }

    private fun startActivitySafe(i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i); true
    } catch (e: ActivityNotFoundException) { false } catch (e: SecurityException) { false }

    private fun openBrowser(): String {
        val i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER)
        return if (startActivitySafe(i)) "Otwieram przeglądarkę." else "Nie znalazłem przeglądarki."
    }

    private fun website(target: String): String {
        var q = target.replace(" kropka ", ".").replace(" . ", ".").trim()
        val hasDot = Regex("^(https?://)?[a-z0-9-]+(\\.[a-z0-9-]+)+(/\\S*)?$").matches(q.replace(" ", ""))
        val url = if (hasDot) {
            q = q.replace(" ", "")
            if (q.startsWith("http")) q else "https://$q"
        } else {
            // brak domeny -> "szczęśliwy traf" Google, czyli pierwszy wynik
            "https://www.google.com/search?btnI=1&q=" + Uri.encode(target)
        }
        return if (startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) "Otwieram stronę." else "Nie mogę otworzyć strony."
    }

    private fun search(q: String): String {
        val url = "https://www.google.com/search?q=" + Uri.encode(q)
        return if (startActivitySafe(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) "Szukam: $q." else "Nie mogę wyszukać."
    }

    private val aliases = mapOf("kamere" to "aparat", "kamera" to "aparat", "zdjecia" to "galeria", "muzyke" to "muzyka")

    private fun openApp(nameRaw: String): String {
        val name = aliases[nameRaw] ?: nameRaw
        val pm = ctx.packageManager
        val li = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val stems = name.split(" ").filter { it.isNotBlank() }.map { it.take(max(3, it.length - 2)) }
        var bestPkg: String? = null
        var bestLabel = ""
        var bestScore = 0
        for (ri in pm.queryIntentActivities(li, 0)) {
            val label = ri.loadLabel(pm).toString()
            val nl = norm(label)
            if (stems.isEmpty() || !stems.all { nl.contains(it) }) continue
            val score = 100 - nl.length + if (nl == name) 100 else 0
            if (score > bestScore) { bestScore = score; bestPkg = ri.activityInfo.packageName; bestLabel = label }
        }
        val pkg = bestPkg ?: return "Nie znalazłem aplikacji $nameRaw."
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return "Nie mogę uruchomić $bestLabel."
        return if (startActivitySafe(intent)) "Otwieram $bestLabel." else "Nie mogę uruchomić $bestLabel."
    }

    // ---------- dzwonienie ----------
    private fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun call(target: String): String {
        val digits = target.filter { it.isDigit() }
        val number: String
        val who: String
        if (digits.length >= 3 && digits.length >= target.replace(" ", "").length - 2) {
            number = digits; who = digits.chunked(3).joinToString(" ")
        } else {
            if (!granted(Manifest.permission.READ_CONTACTS)) return "Nie mam dostępu do kontaktów."
            val c = findContact(target) ?: return "Nie znalazłem kontaktu $target."
            who = c.first; number = c.second
        }
        val uri = Uri.parse("tel:" + Uri.encode(number))
        val action = if (granted(Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return if (startActivitySafe(Intent(action, uri))) "Dzwonię do $who." else "Nie mogę wykonać połączenia."
    }

    private fun findContact(query: String): Pair<String, String>? {
        val stems = query.split(" ").filter { it.isNotBlank() }.map { it.take(max(3, it.length - 3)) }
        if (stems.isEmpty()) return null
        var best: Pair<String, String>? = null
        var bestScore = Int.MIN_VALUE
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                val words = norm(name).split(" ")
                if (!stems.all { s -> words.any { it.startsWith(s) } }) continue
                val score = -Math.abs(words.size - stems.size)
                if (score > bestScore) { bestScore = score; best = name to num }
            }
        }
        return best
    }

    // ---------- latarka ----------
    private fun torch(on: Boolean): String {
        return try {
            val cm = ctx.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "Ten telefon nie ma latarki."
            cm.setTorchMode(id, on)
            if (on) "Latarka włączona." else "Latarka wyłączona."
        } catch (e: Exception) { "Nie mogę obsłużyć latarki." }
    }
}
