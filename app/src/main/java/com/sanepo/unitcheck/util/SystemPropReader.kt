package com.sanepo.unitcheck.util

import android.annotation.SuppressLint
import android.os.Build
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object SystemPropReader {
    private val propCache = ConcurrentHashMap<String, String>()

    private val systemPropGet: Method? by lazy { resolvePropMethod() }

    @SuppressLint("PrivateApi")
    private fun resolvePropMethod(): Method? {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            cls.getMethod("get", String::class.java, String::class.java)
        } catch (_: Throwable) {
            null
        }
    }

    fun get(key: String, defaultValue: String = ""): String {
        val cacheable = key.startsWith("ro.")
        if (cacheable) {
            propCache[key]?.let { return it.ifEmpty { defaultValue } }
        }
        var result = ""
        try {
            val value = systemPropGet?.invoke(null, key, "") as? String
            if (!value.isNullOrBlank()) {
                result = value.trim()
            }
        } catch (_: Throwable) {
            // Hidden API restriction fallback handled below via getprop.
        }

        if (result.isEmpty()) {
            result = execLine(arrayOf("getprop", key))
        }
        if (cacheable) {
            propCache[key] = result
        }
        return result.ifEmpty { defaultValue }
    }

    fun firstNonBlank(vararg keys: String): String {
        for (k in keys) {
            val v = get(k)
            if (v.isNotBlank() && !v.equals("unknown", ignoreCase = true)) {
                return v
            }
        }
        return ""
    }

    fun readFirstSysfsLine(vararg paths: String): String {
        for (p in paths) {
            val v = readFirstLine(p)
            if (!v.isNullOrBlank()) {
                return v
            }
        }
        return ""
    }

    fun readText(path: String): String? {
        return try {
            val f = File(path)
            if (!f.exists() || !f.canRead()) null else f.readText().trim()
        } catch (_: Throwable) {
            null
        }
    }

    fun readFirstLine(path: String): String? {
        return try {
            val f = File(path)
            if (!f.exists() || !f.canRead()) null
            else f.bufferedReader().use { it.readLine()?.trim() }
        } catch (_: Throwable) {
            null
        }
    }

    fun readLong(path: String): Long? {
        val line = readFirstLine(path) ?: return null
        return line.toLongOrNull()
    }

    fun execLine(cmd: Array<String>, timeoutMs: Long = 400L): String {
        var proc: Process? = null
        return try {
            proc = ProcessBuilder(*cmd)
                .redirectErrorStream(true)
                .start()
            val outputHolder = StringBuilder()
            val readerThread = Thread {
                try {
                    proc.inputStream.bufferedReader().use { r ->
                        val txt = r.readText().trim()
                        synchronized(outputHolder) { outputHolder.append(txt) }
                    }
                } catch (_: Throwable) {
                }
            }.apply {
                isDaemon = true
                start()
            }

            val finished = waitForProcessWithTimeout(proc, timeoutMs)
            if (!finished) {
                proc.destroy()
                return ""
            }
            readerThread.join(100L)
            synchronized(outputHolder) { outputHolder.toString() }
        } catch (_: Throwable) {
            ""
        } finally {
            try {
                proc?.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    private fun waitForProcessWithTimeout(proc: Process, timeoutMs: Long): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            try {
                proc.exitValue()
                return true
            } catch (_: IllegalThreadStateException) {
                try {
                    Thread.sleep(15L)
                } catch (_: InterruptedException) {
                    return false
                }
            }
        }
        return false
    }
}
