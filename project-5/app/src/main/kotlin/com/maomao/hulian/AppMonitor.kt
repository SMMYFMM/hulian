package com.maomao.hulian

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

class AppMonitor(private val context: Context) {

    private val TAG = "AppMonitor"

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var targetPackage = ""

    // 回调：目标App不在前台了
    var onAppExited: (() -> Unit)? = null

    private val activityManager: ActivityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    // 轮询间隔5秒，车机性能低，不宜太频繁
    private val pollIntervalMs = 5000L

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return

            val topPkg = getTopPackage()
            Log.d(TAG, "当前前台: $topPkg，目标: $targetPackage")

            if (topPkg != null && topPkg != targetPackage) {
                Log.d(TAG, "目标App已退出前台，触发回调")
                stop()
                onAppExited?.invoke()
                return
            }

            // 继续下一次轮询
            handler.postDelayed(this, pollIntervalMs)
        }
    }

    // ── 获取当前前台App包名 ───────────────────────────────

    @Suppress("DEPRECATION")
    private fun getTopPackage(): String? {
        return try {
            activityManager.getRunningTasks(1)
                .firstOrNull()
                ?.topActivity
                ?.packageName
        } catch (e: Exception) {
            Log.w(TAG, "获取前台App失败: ${e.message}")
            null
        }
    }

    // ── 开始监听 ──────────────────────────────────────────

    fun start(packageName: String) {
        if (isRunning) {
            Log.d(TAG, "AppMonitor 已在运行，先停止再重启")
            stop()
        }
        targetPackage = packageName
        isRunning = true
        // 延迟2秒再开始轮询，等互联App完全启动到前台
        handler.postDelayed(pollRunnable, 2000L)
        Log.d(TAG, "AppMonitor 开始监听: $packageName")
    }

    // ── 停止监听 ──────────────────────────────────────────

    fun stop() {
        isRunning = false
        handler.removeCallbacks(pollRunnable)
        Log.d(TAG, "AppMonitor 已停止")
    }

    // ── 释放资源 ──────────────────────────────────────────

    fun release() {
        stop()
        onAppExited = null
    }
}