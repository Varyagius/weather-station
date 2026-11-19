package ua.lancer.scan

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.widget.*
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : Activity() {
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private val full = StringBuilder()
    private var inp: InputStream? = null
    private var out: OutputStream? = null
    private var closer: (() -> Unit)? = null
    @Volatile private var stop = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { f() }
            root.addView(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        btn("1. Підключити Bluetooth ELM327") { pickBluetooth() }
        btn("1б. Підключити Wi-Fi ELM327 (192.168.0.10:35000)") { connectWifi() }
        btn("2. Почати повне сканування") { Thread { scan() }.start() }
        btn("Зупинити") { stop = true }
        btn("3. Зберегти лог у Завантаження") { saveLog() }
        logView = TextView(this).apply { textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE }
        scroll = ScrollView(this).apply { addView(logView) }
        root.addView(scroll, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
    }

    private fun log(s: String) {
        full.append(s).append('\n')
        runOnUiThread { logView.append(s + "\n"); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }
    }

    // ---------- підключення ----------
    private fun pickBluetooth() {
        val ad: BluetoothAdapter? = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val devs = try { ad?.bondedDevices?.toList() ?: emptyList() } catch (e: SecurityException) { emptyList() }
        if (devs.isEmpty()) { log("Немає спарених пристроїв. Спарте ELM327 (PIN 1234 / 0000) у налаштуваннях."); return }
        val names = devs.map { "${it.name} ${it.address}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Оберіть ELM327").setItems(names) { _, i ->
            Thread { connectBt(devs[i]) }.start()
        }.show()
    }

    private fun connectBt(d: BluetoothDevice) {
        try {
            log("Підключення до ${d.name}...")
            val s = d.createRfcommSocketToServiceRecord(UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"))
            s.connect()
            inp = s.inputStream; out = s.outputStream; closer = { s.close() }
            log("OK, підключено")
        } catch (e: Exception) { log("Помилка: $e") }
    }

    private fun connectWifi() = Thread {
        try {
            val s = Socket(); s.connect(InetSocketAddress("192.168.0.10", 35000), 5000)
            inp = s.getInputStream(); out = s.getOutputStream(); closer = { s.close() }
            log("OK, Wi-Fi підключено (вимкни мобільний інтернет, якщо не працює)")
        } catch (e: Exception) { log("Помилка: $e") }
    }.start()

    // ---------- обмін з адаптером ----------
    private fun cmd(c: String, timeoutMs: Long = 5000): String {
        val o = out ?: return "NO_CONN"
        val i = inp ?: return "NO_CONN"
        try {
            while (i.available() > 0) i.read()
            o.write((c + "\r").toByteArray()); o.flush()
            val sb = StringBuilder()
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                if (i.available() > 0) {
                    val ch = i.read().toChar()
                    if (ch == '>') break
                    sb.append(ch)
                } else Thread.sleep(20)
            }
            val r = sb.toString().replace("\r", "\n").lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" | ")
            log(">> $c\n<< $r")
            return r
        } catch (e: Exception) { log("ERR $c: $e"); return "ERR" }
    }

    private fun lines(r: String) = r.split("|").map { it.replace(" ", "") }

    private fun payload(r: String, prefix: String): String? {
        for (l in lines(r)) { val k = l.indexOf(prefix); if (k >= 0) return l.substring(k + prefix.length) }
        return null
    }

    // ---------- сканування ----------
    private fun scan() {
        stop = false
        if (out == null) { log("Спочатку підключись"); return }
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        log("===== Lancer 9 4G63 ELM327 scan ${df.format(Date())} =====")
        log("Увімкни запалювання (ключ у ON, двигун краще заглушений для першого проходу)")
        for (c in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH1", "ATI", "AT@1", "AT@2", "ATRV", "ATDP", "ATDPN")) cmd(c, 3000)

        val found = mutableListOf<Int>()
        // 1 J1850PWM, 2 J1850VPW, 3 ISO9141-2, 4 KWP 5-baud, 5 KWP fast, 6 CAN11/500, 7 CAN29/500, 8 CAN11/250, 9 CAN29/250
        for (p in 1..9) {
            if (stop) return
            log("--- Протокол $p ---")
            cmd("ATSP$p", 2000)
            val r = cmd("0100", 15000)
            cmd("ATDPN", 2000)
            if (r.replace(" ", "").contains("4100")) { found += p; log("*** ПРАЦЮЄ: протокол $p ***") }
        }
        log("--- Авто-режим ATSP0 ---")
        cmd("ATSP0", 2000); cmd("0100", 20000); cmd("ATDP", 2000)
        log("Знайдені протоколи: $found")

        for (p in found) { if (stop) return; deep(p) }
        log("===== Готово. Натисни «Зберегти лог» і скинь мені файл =====")
    }

    private fun deep(p: Int) {
        log("########## ГЛИБОКЕ СКАНУВАННЯ, протокол $p ##########")
        cmd("ATSP$p", 2000)
        cmd("0100", 15000)
        val supported = mutableListOf<Int>()
        for (base in 0x00..0xC0 step 0x20) {
            val pid = "%02X".format(base)
            val r = cmd("01$pid", 6000)
            val pl = payload(r, "41$pid") ?: break
            if (pl.length < 8) break
            val mask = pl.substring(0, 8).toLong(16)
            for (bit in 0 until 32) if (mask and (1L shl (31 - bit)) != 0L) supported += base + bit + 1
            if (mask and 1L == 0L) break
        }
        log("Підтримувані PID режиму 01: " + supported.joinToString(" ") { "%02X".format(it) })
        for (pid in supported) { if (stop) return; if (pid % 0x20 != 0) cmd("01%02X".format(pid), 3000) }
        for (c in listOf("0200", "03", "07", "0A", "0900", "0902", "0904", "090A", "0600", "0620", "0640", "0660", "0680", "06A0", "0500", "0100"))
            { if (stop) return; cmd(c, 6000) }
        // перевірка наявності інших ЕБУ (АКПП/ABS/SRS) по K-line/CAN
        cmd("ATH1", 1000); cmd("ATCAF1", 1000); cmd("ATDP", 1000)
        cmd("ATRV", 1000)
    }

    private fun saveLog() {
        try {
            val name = "lancer_scan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".txt"
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v)!!
            contentResolver.openOutputStream(uri)!!.use { it.write(full.toString().toByteArray()) }
            log("Збережено: Завантаження/$name")
        } catch (e: Exception) { log("Помилка збереження: $e") }
    }

    override fun onDestroy() { try { closer?.invoke() } catch (_: Exception) {}; super.onDestroy() }
}
