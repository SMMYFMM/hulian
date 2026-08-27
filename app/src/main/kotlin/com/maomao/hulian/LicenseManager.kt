package com.maomao.hulian

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL

object LicenseManager {

    private const val TAG = "LicenseManager"
    private const val EXPIRE_TIME = 1798761600000L
    private const val PREF_NAME = "license_prefs"
    private const val KEY_LAST_TRUSTED_TIME = "last_trusted_time"
    private const val KEY_LAST_ELAPSED = "last_elapsed"
    private const val KEY_EXPIRED_FLAG = "license_expired"
    private const val MAX_JUMP_MS = 10 * 60 * 1000L

    @Volatile
    private var isExpired = false

    fun check(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_EXPIRED_FLAG, false)) {
            FileLogger.e(TAG, "本地标记已过期")
            notifyExpired(context)
            return false
        }
        Thread {
            try {
                val localTime = System.currentTimeMillis()
                val elapsed = SystemClock.elapsedRealtime()
                val lastTrusted = prefs.getLong(KEY_LAST_TRUSTED_TIME, 0L)
                val lastElapsed = prefs.getLong(KEY_LAST_ELAPSED, 0L)
                FileLogger.i(TAG, "授权检查: local=$localTime, elapsed=$elapsed, lastTrusted=$lastTrusted")
                val netTime = tryGetNetworkTime()
                FileLogger.i(TAG, "网络时间: $netTime")
                val baseTime = if (netTime > 0) netTime else localTime
                val monotonicDelta = if (lastElapsed > 0) elapsed - lastElapsed else 0L
                var trustedTime = if (lastTrusted == 0L) baseTime else maxOf(lastTrusted + monotonicDelta, baseTime)
                if (trustedTime < lastTrusted) {
                    FileLogger.w(TAG, "时间回拨: $trustedTime < $lastTrusted")
                    trustedTime = lastTrusted + monotonicDelta
                }
                if (trustedTime - lastTrusted > MAX_JUMP_MS && lastTrusted != 0L) {
                    FileLogger.w(TAG, "时间异常跳变: ${trustedTime - lastTrusted}ms")
                    trustedTime = lastTrusted + monotonicDelta
                }
                save(prefs, trustedTime, elapsed)
                val remainingDays = (EXPIRE_TIME - trustedTime) / (24 * 60 * 60 * 1000L)
                FileLogger.i(TAG, "授权检查结果: 剩余${remainingDays}天")
                if (trustedTime > EXPIRE_TIME) {
                    FileLogger.e(TAG, "授权已过期!")
                    prefs.edit().putBoolean(KEY_EXPIRED_FLAG, true).apply()
                    isExpired = true
                    notifyExpired(context)
                    return@Thread
                }
                FileLogger.i(TAG, "授权检查通过")
            } catch (e: Exception) {
                FileLogger.e(TAG, "授权检查异常", e)
            }
        }.start()
        return !isExpired
    }

    private fun tryGetNetworkTime(): Long {
        return try {
            val url = URL("https://www.baidu.com")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "HEAD"
            conn.instanceFollowRedirects = false
            conn.connect()
            val date = conn.date
            conn.disconnect()
            if (date <= 0) -1 else date
        } catch (e: Exception) { FileLogger.w(TAG, "获取网络时间失败: ${e.message}"); -1 }
    }

    private fun save(prefs: SharedPreferences, time: Long, elapsed: Long) {
        prefs.edit().putLong(KEY_LAST_TRUSTED_TIME, time).putLong(KEY_LAST_ELAPSED, elapsed).apply()
    }

    private fun notifyExpired(context: Context) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "软件已过期，请更新", Toast.LENGTH_LONG).show()
            if (context is android.app.Activity) context.finish()
        }
    }
}
