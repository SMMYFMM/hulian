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

    // 2027-01-01 00:00:00 UTC
    private const val EXPIRE_TIME = 1798761600000L

    private const val PREF_NAME = "license_prefs"
    private const val KEY_LAST_TRUSTED_TIME = "last_trusted_time"
    private const val KEY_LAST_ELAPSED = "last_elapsed"

    // 允许的最大时间跳变（防突然改到未来）
    private const val MAX_JUMP_MS = 10 * 60 * 1000L // 10分钟

    fun check(context: Context): Boolean {

        Thread {

            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

            try {

                val localTime = System.currentTimeMillis()
                val elapsed = SystemClock.elapsedRealtime()

                val lastTrusted = prefs.getLong(KEY_LAST_TRUSTED_TIME, 0L)
                val lastElapsed = prefs.getLong(KEY_LAST_ELAPSED, 0L)

                val netTime = tryGetNetworkTime()

                // ===============================
                // 1️⃣ 计算候选时间（网络优先）
                // ===============================
                val baseTime = if (netTime > 0) netTime else localTime

                // ===============================
                // 2️⃣ 用单调时钟修正时间漂移
                // ===============================
                val monotonicDelta = if (lastElapsed > 0) {
                    elapsed - lastElapsed
                } else {
                    0L
                }

                var trustedTime = if (lastTrusted == 0L) {
                    baseTime
                } else {
                    maxOf(lastTrusted + monotonicDelta, baseTime)
                }

                // ===============================
                // 3️⃣ 防时间回拨
                // ===============================
                if (trustedTime < lastTrusted) {
                    throw RuntimeException("检测到时间回拨")
                }

                // ===============================
                // 4️⃣ 防“跳到未来”（关键修复你27年问题）
                // ===============================
                if (trustedTime - lastTrusted > MAX_JUMP_MS && lastTrusted != 0L) {
                    throw RuntimeException("检测到时间异常跳变")
                }

                // ===============================
                // 5️⃣ 更新可信时间锚点
                // ===============================
                save(prefs, trustedTime, elapsed)

                // ===============================
                // 6️⃣ 到期判断
                // ===============================
                if (trustedTime > EXPIRE_TIME) {
                    notifyExpired(context)
                    return@Thread
                }

            } catch (e: Exception) {
                Log.w(TAG, "License check failed: ${e.message}")
            }

        }.start()

        return true
    }

    // ===============================
    // 网络时间（HEAD方式）
    // ===============================
    private fun tryGetNetworkTime(): Long {
        return try {
            val url = URL("https://www.baidu.com")
            val conn = url.openConnection() as HttpURLConnection

            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "HEAD"

            conn.connect()

            val date = conn.date
            conn.disconnect()

            if (date <= 0) -1 else date

        } catch (e: Exception) {
            -1
        }
    }

    // ===============================
    // 保存可信时间锚点
    // ===============================
    private fun save(prefs: SharedPreferences, time: Long, elapsed: Long) {
        prefs.edit()
            .putLong(KEY_LAST_TRUSTED_TIME, time)
            .putLong(KEY_LAST_ELAPSED, elapsed)
            .apply()
    }

    // ===============================
    // 到期处理
    // ===============================
    private fun notifyExpired(context: Context) {
        Handler(Looper.getMainLooper()).post {

            Toast.makeText(
                context,
                "请更新软件",
                Toast.LENGTH_LONG
            ).show()

            if (context is android.app.Activity) {
                context.finish()
            }
        }
    }
}