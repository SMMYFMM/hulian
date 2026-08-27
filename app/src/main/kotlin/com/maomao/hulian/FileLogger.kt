package com.maomao.hulian

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object FileLogger {

    private const val MAX_FILE_SIZE = 2 * 1024 * 1024L
    private const val LOG_DIR_NAME = "hulian_logs"
    private const val LOG_FILE_NAME = "hulian_log.txt"
    private const val LOG_FILE_OLD = "hulian_log_old.txt"

    private val executor = Executors.newSingleThreadExecutor()
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private var logDir: File? = null
    private var logFile: File? = null

    fun init(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null)
                ?: File(Environment.getExternalStorageDirectory(), "Android/data/${context.packageName}/files")
            logDir = File(dir, LOG_DIR_NAME).apply { mkdirs() }
            logFile = File(logDir, LOG_FILE_NAME)
            writeHeader(context)
        } catch (e: Exception) {
            try {
                logDir = File(context.filesDir, LOG_DIR_NAME).apply { mkdirs() }
                logFile = File(logDir, LOG_FILE_NAME)
                writeHeader(context)
            } catch (e2: Exception) { }
        }
    }

    private fun writeHeader(context: Context) {
        val header = buildString {
            appendLine("=".repeat(60))
            appendLine("猫猫互联助手 - 运行日志")
            appendLine("=".repeat(60))
            appendLine("记录开始时间: ${sdf.format(Date())}")
            appendLine("设备型号: ${Build.MODEL}")
            appendLine("设备品牌: ${Build.BRAND}")
            appendLine("Android版本: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("App版本: ${try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (e: Exception) { "unknown" }}")
            appendLine("=".repeat(60))
            appendLine()
        }
        appendToFile(header)
    }

    fun d(tag: String, msg: String) = write("DEBUG", tag, msg, null)
    fun i(tag: String, msg: String) = write("INFO", tag, msg, null)
    fun w(tag: String, msg: String, e: Throwable? = null) = write("WARN", tag, msg, e)
    fun e(tag: String, msg: String, e: Throwable? = null) = write("ERROR", tag, msg, e)

    private fun write(level: String, tag: String, msg: String, e: Throwable?) {
        val timestamp = sdf.format(Date())
        val line = buildString {
            append("[$timestamp] [$level] [$tag] $msg")
            if (e != null) {
                append("\n")
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                e.printStackTrace(pw)
                append(sw.toString())
            }
            append("\n")
        }
        executor.execute { appendToFile(line) }
    }

    private fun appendToFile(text: String) {
        val file = logFile ?: return
        try {
            if (file.exists() && file.length() > MAX_FILE_SIZE) {
                val oldFile = File(logDir, LOG_FILE_OLD)
                if (oldFile.exists()) oldFile.delete()
                file.renameTo(oldFile)
            }
            FileWriter(file, true).use { it.append(text); it.flush() }
        } catch (e: Exception) { }
    }

    fun getLogFilePath(): String = logFile?.absolutePath ?: "(日志未初始化)"
    fun getLogDirPath(): String = logDir?.absolutePath ?: "(日志目录未初始化)"
    fun clearLogs() {
        try { logFile?.delete(); File(logDir, LOG_FILE_OLD).delete(); logFile?.createNewFile() } catch (e: Exception) { }
    }
}
