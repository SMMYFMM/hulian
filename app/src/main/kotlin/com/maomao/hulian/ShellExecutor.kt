package com.maomao.hulian

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

object ShellExecutor {

    private const val TAG = "ShellExecutor"

    // 执行结果数据类
    data class Result(
        val success: Boolean,
        val output: String,
        val error: String
    )

    // ── 同步执行（在调用方自行开线程）────────────────────

    fun exec(command: String): Result {
        return try {
            Log.d(TAG, "exec: $command")
            FileLogger.i(TAG, "执行Shell: $command")
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))

            val output = BufferedReader(InputStreamReader(process.inputStream))
                .readLines().joinToString("\n")

            val error = BufferedReader(InputStreamReader(process.errorStream))
                .readLines().joinToString("\n")

            val exitCode = process.waitFor()
            val success = exitCode == 0

            if (!success) {
                Log.w(TAG, "exec failed (exit=$exitCode): $error")
            } else {
                Log.d(TAG, "exec ok: $output")
                FileLogger.d(TAG, "Shell成功: $output")
            }

            Result(success, output, error)

        } catch (e: Exception) {
            Log.e(TAG, "exec exception: ${e.message}")
            Result(false, "", e.message ?: "unknown error")
        }
    }

    // ── 异步执行（内部起线程，结果回调到主线程）────────────

    fun execAsync(
        command: String,
        onResult: ((Result) -> Unit)? = null
    ) {
        Thread {
            val result = exec(command)
            if (onResult != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    onResult(result)
                }
            }
        }.start()
    }

    // ── 常用命令封装 ──────────────────────────────────

    // 执行vendor蓝牙关闭命令（异步，结果可选回调）
    fun killBluetooth(command: String, onResult: ((Result) -> Unit)? = null) {
        execAsync(command, onResult)
    }

    // 检查某个包名对应的App进程是否在运行
    fun isProcessRunning(packageName: String): Boolean {
        val result = exec("pidof $packageName")
        return result.success && result.output.trim().isNotEmpty()
    }
}