package com.maomao.hulian

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

class AppMonitor(private val context: Context) {

    private val TAG = "AppMonitor"

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var targetPackage = ""

    var onAppExited: (() -> Unit)? = null

    private val activityManager: ActivityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val usageStatsManager: UsageStatsManager? =
        try { context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager }
        catch (e: Exception) { Log.w(TAG, "UsageStatsManager不可用"); null }

    private val pollIntervalMs = 5000L

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val topPkg = getTopPackage()
            Log.d(TAG, "当前前台: $topPkg，目标: $targetPackage")
            if (topPkg != null && topPkg != targetPackage) {
                Log.d(TAG, "目标App已退出前台")
                FileLogger.i(TAG, "目标App已退出前台: 当前=$topPkg, 目标=$targetPackage")
                stop()
                onAppExited?.invoke()
                return
            }
            handler.postDelayed(this, pollIntervalMs)
        }
    }

    @Suppress("DEPRECATION")
    private fun getTopPackage(): String? {
        val topPkg = try {
            activityManager.getRunningTasks(1).firstOrNull()?.topActivity?.packageName
        } catch (e: Exception) { Log.w(TAG, "getRunningTasks失败: ${e.message}"); null }
        if (topPkg != null) return topPkg
        return getTopPackageViaUsageStats()
    }

    private fun getTopPackageViaUsageStats(): String? {
        val usm = usageStatsManager ?: return null
        return try {
            val endTime = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, endTime - 10000, endTime)
            stats?.filter { it.lastTimeUsed > endTime - 10000 }?.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (e: Exception) { Log.w(TAG, "UsageStats查询失败: ${e.message}"); null }
    }

    fun start(packageName: String) {
        if (isRunning) { Log.d(TAG, "已在运行，先停止"); stop() }
        targetPackage = packageName
        isRunning = true
        handler.postDelayed(pollRunnable, 2000L)
        FileLogger.i(TAG, "开始监听: $packageName")
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(pollRunnable)
        Log.d(TAG, "已停止")
    }

    fun release() { stop(); onAppExited = null }
}
