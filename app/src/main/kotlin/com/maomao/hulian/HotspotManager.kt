package com.maomao.hulian

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import java.lang.reflect.Method

class HotspotManager(private val context: Context) {

    private val TAG = "HotspotManager"

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val connectivityManager: ConnectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val handler = Handler(Looper.getMainLooper())

    private var pendingCallback: ((Boolean) -> Unit)? = null
    private var pendingTimeoutRunnable: Runnable? = null
    private var receiverRegistered = false

    companion object {
        // WifiManager 隐藏常量（API 33 不可见，用字面值替代）
        private const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val EXTRA_WIFI_AP_STATE = "wifi_ap_state"
        private const val WIFI_AP_STATE_DISABLING = 10
        private const val WIFI_AP_STATE_DISABLED = 11
        private const val WIFI_AP_STATE_ENABLING = 12
        private const val WIFI_AP_STATE_ENABLED = 13
        private const val WIFI_AP_STATE_FAILED = 14
    }

    // ── 热点状态广播 ──────────────────────────────────────

    private val hotspotReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_WIFI_AP_STATE_CHANGED) return
            val state = intent.getIntExtra(EXTRA_WIFI_AP_STATE, WIFI_AP_STATE_DISABLED)
            when (state) {
                WIFI_AP_STATE_ENABLED -> {
                    FileLogger.i(TAG, "热点状态变化: ENABLED")
                    clearPendingCallback(true)
                }
                WIFI_AP_STATE_ENABLING -> FileLogger.d(TAG, "热点: ENABLING")
                WIFI_AP_STATE_DISABLED -> FileLogger.d(TAG, "热点: DISABLED")
                WIFI_AP_STATE_DISABLING -> FileLogger.d(TAG, "热点: DISABLING")
                WIFI_AP_STATE_FAILED -> FileLogger.w(TAG, "热点: FAILED")
            }
        }
    }

    // ── 检查热点是否已开启 ────────────────────────────────

    fun isHotspotEnabled(): Boolean {
        // 方式1: 反射 isWifiApEnabled
        try {
            val method: Method = wifiManager.javaClass.getMethod("isWifiApEnabled")
            return method.invoke(wifiManager) as Boolean
        } catch (e: Exception) {
            // 继续尝试方式2
        }
        // 方式2: 反射 getWifiApState
        try {
            val method: Method = wifiManager.javaClass.getMethod("getWifiApState")
            val state = method.invoke(wifiManager) as Int
            return state == WIFI_AP_STATE_ENABLED
        } catch (e: Exception) {
            FileLogger.w(TAG, "检查热点状态失败: ${e.message}")
        }
        // 方式3: Shell 检查
        val result = ShellExecutor.exec("settings get global wifi_ap_state")
        if (result.success && result.output.trim() == "13") return true
        return false
    }

    // ── 获取热点状态（反射）──────────────────────────────

    private fun getWifiApState(): Int {
        return try {
            val method: Method = wifiManager.javaClass.getMethod("getWifiApState")
            method.invoke(wifiManager) as Int
        } catch (e: Exception) {
            FileLogger.w(TAG, "getWifiApState失败: ${e.message}")
            WIFI_AP_STATE_DISABLED
        }
    }

    // ── 开启热点（多方式尝试）────────────────────────────

    fun enableHotspot(onComplete: (Boolean) -> Unit, timeoutMs: Long = 15000L) {
        if (isHotspotEnabled()) {
            FileLogger.i(TAG, "热点已是开启状态")
            onComplete(true)
            return
        }

        FileLogger.i(TAG, "热点未开启，尝试多种方式开启...")
        registerReceiver()
        setupPendingCallback(timeoutMs, onComplete)

        Thread {
            tryAllMethods()
        }.start()
    }

    private fun tryAllMethods() {
        // 方案1: ConnectivityManager.startTethering (API 23+)
        FileLogger.i(TAG, "方案1: 尝试 startTethering")
        try {
            val method: Method? = connectivityManager.javaClass.methods.find { it.name == "startTethering" }
            if (method != null) {
                // TETHERING_WIFI = 0
                method.invoke(connectivityManager, 0, false, null)
                FileLogger.i(TAG, "startTethering调用完成")
            } else {
                FileLogger.w(TAG, "startTethering方法不存在")
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "startTethering失败: ${e.message}")
        }

        Thread.sleep(2000)
        if (isHotspotEnabled()) {
            FileLogger.i(TAG, "方案1成功，热点已开启!")
            return
        }

        // 方案2: 反射 setWifiApEnabled
        FileLogger.i(TAG, "方案2: 尝试反射 setWifiApEnabled")
        try {
            val method: Method = wifiManager.javaClass.getMethod("setWifiApEnabled", android.net.wifi.WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
            val result = method.invoke(wifiManager, null, true) as Boolean
            FileLogger.i(TAG, "setWifiApEnabled结果: $result")
        } catch (e: Exception) {
            FileLogger.w(TAG, "setWifiApEnabled失败: ${e.message}")
        }

        Thread.sleep(2000)
        if (isHotspotEnabled()) {
            FileLogger.i(TAG, "方案2成功，热点已开启!")
            return
        }

        // 方案3: service call
        FileLogger.i(TAG, "方案3: 尝试 service call")
        val serviceResult = ShellExecutor.exec("service call wifi 24 i32 1")
        FileLogger.i(TAG, "service call结果: ${serviceResult.success}, output=${serviceResult.output}")

        Thread.sleep(2000)
        if (isHotspotEnabled()) {
            FileLogger.i(TAG, "方案3成功，热点已开启!")
            return
        }

        // 方案4: settings put global
        FileLogger.i(TAG, "方案4: 尝试 settings put global")
        ShellExecutor.exec("settings put global wifi_ap_state 13")
        ShellExecutor.exec("settings put global tether_supported 1")
        ShellExecutor.exec("settings put global tether_dun_required 0")

        Thread.sleep(2000)
        if (isHotspotEnabled()) {
            FileLogger.i(TAG, "方案4成功，热点已开启!")
            return
        }

        // 方案5: su root
        FileLogger.i(TAG, "方案5: 检查su")
        val suCheck = ShellExecutor.exec("which su")
        if (suCheck.success && suCheck.output.isNotBlank()) {
            FileLogger.d(TAG, "su可用，尝试 su -c svc wifi enable")
            val suResult = ShellExecutor.exec("su -c svc wifi enable")
            FileLogger.i(TAG, "su svc结果: ${suResult.success}")

            Thread.sleep(2000)
            if (isHotspotEnabled()) {
                FileLogger.i(TAG, "方案5成功，热点已开启!")
                return
            }

            // su + settings
            ShellExecutor.exec("su -c settings put global wifi_ap_state 13")
            Thread.sleep(2000)
            if (isHotspotEnabled()) {
                FileLogger.i(TAG, "su settings成功!")
                return
            }
        } else {
            FileLogger.w(TAG, "su不可用")
        }

        // 方案6: 打开热点设置页面
        FileLogger.w(TAG, "所有自动方式均失败，打开热点设置页面")
        handler.post { openHotspotSettingsWithPrompt() }
    }

    private fun openHotspotSettingsWithPrompt() {
        FileLogger.i(TAG, "打开热点设置页面，等待用户手动开启")
        Toast.makeText(context, "请手动开启热点", Toast.LENGTH_LONG).show()
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            FileLogger.i(TAG, "已打开WiFi设置界面(含热点选项)")
        } catch (e: Exception) {
            FileLogger.e(TAG, "打开热点设置失败", e)
        }
    }

    fun openHotspotSettings() {
        try {
            // 尝试打开热点设置页面
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            FileLogger.i(TAG, "已打开WiFi设置界面")
        } catch (e: Exception) {
            FileLogger.e(TAG, "打开设置失败", e)
        }
    }

    // ── 回调管理 ──────────────────────────────────────────

    private fun setupPendingCallback(timeoutMs: Long, callback: (Boolean) -> Unit) {
        // 丢弃旧回调，不触发它（避免旧回调干扰新流程）
        pendingTimeoutRunnable?.let { handler.removeCallbacks(it) }
        pendingTimeoutRunnable = null
        pendingCallback = null
        // 设置新回调
        pendingCallback = callback
        pendingTimeoutRunnable = Runnable {
            val enabled = isHotspotEnabled()
            FileLogger.w(TAG, "热点等待超时(${timeoutMs}ms), isHotspotEnabled=$enabled")
            clearPendingCallback(enabled)
        }
        handler.postDelayed(pendingTimeoutRunnable!!, timeoutMs)
    }

    private fun clearPendingCallback(success: Boolean) {
        pendingTimeoutRunnable?.let { handler.removeCallbacks(it) }
        pendingTimeoutRunnable = null
        pendingCallback?.let { handler.post { it(success) } }
        pendingCallback = null
    }

    /**
     * 取消挂起的热点回调，丢弃旧回调不触发。
     * 用于状态机取消/重启时清理。
     */
    fun cancelPendingCallback() {
        FileLogger.i(TAG, "取消挂起的热点回调")
        pendingTimeoutRunnable?.let { handler.removeCallbacks(it) }
        pendingTimeoutRunnable = null
        pendingCallback = null
    }

    // ── 广播注册 ──────────────────────────────────────────

    fun registerReceiver() {
        if (receiverRegistered) return
        context.registerReceiver(hotspotReceiver, IntentFilter(ACTION_WIFI_AP_STATE_CHANGED))
        receiverRegistered = true
        FileLogger.i(TAG, "热点状态监听已注册")
    }

    fun unregisterReceiver() {
        if (!receiverRegistered) return
        try { context.unregisterReceiver(hotspotReceiver) } catch (e: Exception) { FileLogger.w(TAG, "注销热点监听异常", e) }
        receiverRegistered = false
    }

    // ── 关闭热点 ──────────────────────────────────────────

    fun disableHotspot(onComplete: ((Boolean) -> Unit)? = null) {
        if (!isHotspotEnabled()) { onComplete?.invoke(true); return }
        FileLogger.i(TAG, "关闭热点")
        try {
            val method: Method = wifiManager.javaClass.getMethod("setWifiApEnabled", android.net.wifi.WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
            val result = method.invoke(wifiManager, null, false) as Boolean
            FileLogger.i(TAG, "关闭热点结果: $result")
            onComplete?.invoke(result)
        } catch (e: Exception) {
            FileLogger.w(TAG, "关闭热点失败: ${e.message}")
            // 尝试stopTethering
            try {
                val method: Method? = connectivityManager.javaClass.methods.find { it.name == "stopTethering" }
                method?.invoke(connectivityManager, 0)
                FileLogger.i(TAG, "stopTethering调用完成")
                onComplete?.invoke(true)
            } catch (e2: Exception) {
                onComplete?.invoke(false)
            }
        }
    }

    // ── 释放 ──────────────────────────────────────────────

    fun release() {
        clearPendingCallback(false)
        unregisterReceiver()
    }
}
