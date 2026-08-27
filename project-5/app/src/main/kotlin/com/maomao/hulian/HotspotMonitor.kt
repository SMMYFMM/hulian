package com.maomao.hulian

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.util.Log

class HotspotMonitor(
    private val context: Context,
    private val prefs: PrefsHelper
) {

    private val TAG = "HotspotMonitor"

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    // 回调：连上目标SSID
    var onTargetSsidConnected: (() -> Unit)? = null

    // 回调：从目标SSID断开（条件3触发用）
    var onTargetSsidDisconnected: (() -> Unit)? = null

    private var receiverRegistered = false

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {

                WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(
                        WifiManager.EXTRA_NETWORK_INFO
                    ) ?: return

                    if (networkInfo.isConnected) {
                        val ssid = getCurrentSsid()
                        val target = prefs.targetSsid
                        Log.d(TAG, "WiFi 已连接，SSID=$ssid，目标=$target")
                        if (ssid == target) {
                            Log.d(TAG, "✓ 目标热点已连接：$ssid")
                            onTargetSsidConnected?.invoke()
                        }
                    } else {
                        Log.d(TAG, "WiFi 已断开")
                        onTargetSsidDisconnected?.invoke()
                    }
                }
            }
        }
    }

    // ── 获取当前连接的SSID ────────────────────────────────

    fun getCurrentSsid(): String {
        val rawSsid = wifiManager.connectionInfo?.ssid ?: return ""
        // Android 9 返回的SSID带双引号，需要去掉
        return rawSsid.removeSurrounding("\"")
    }

    // 检查当前是否已连接目标SSID
    fun isConnectedToTarget(): Boolean {
        return getCurrentSsid() == prefs.targetSsid
    }

    // ── 注册/注销监听 ─────────────────────────────────────

    fun register() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }
        context.registerReceiver(wifiReceiver, filter)
        receiverRegistered = true
        Log.d(TAG, "WiFi 热点监听已注册")

        // 注册时立即检查一次当前状态，避免错过已连接的情况
        if (isConnectedToTarget()) {
            Log.d(TAG, "注册时检测到目标热点已连接，直接触发")
            onTargetSsidConnected?.invoke()
        }
    }

    fun unregister() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(wifiReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "注销 WiFi 监听异常: ${e.message}")
        }
        receiverRegistered = false
        Log.d(TAG, "WiFi 热点监听已注销")
    }

    // ── 扫描附近WiFi并返回SSID列表（用于主界面SSID选择）───

    @Suppress("DEPRECATION")
    fun getSavedAndScannedSsids(): List<String> {
        val result = mutableSetOf<String>()

        // 已保存的WiFi
        try {
            wifiManager.configuredNetworks?.forEach { config ->
                val ssid = config.SSID?.removeSurrounding("\"") ?: return@forEach
                if (ssid.isNotEmpty()) result.add(ssid)
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取已保存WiFi失败: ${e.message}")
        }

        // 扫描结果
        try {
            wifiManager.scanResults?.forEach { scanResult ->
                val ssid = scanResult.SSID ?: return@forEach
                if (ssid.isNotEmpty()) result.add(ssid)
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取扫描结果失败: ${e.message}")
        }

        return result.toList().sorted()
    }

    // ── 释放资源 ──────────────────────────────────────────

    fun release() {
        unregister()
        onTargetSsidConnected = null
        onTargetSsidDisconnected = null
    }
}