package com.maomao.hulian

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log

class BtWifiManager(private val context: Context) {

    private val TAG = "BtWifiManager"

    private val btAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    // 蓝牙状态变化回调（true=开，false=关）
    var onBtStateChanged: ((isOn: Boolean) -> Unit)? = null

    private var btReceiverRegistered = false

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(
                BluetoothAdapter.EXTRA_STATE,
                BluetoothAdapter.ERROR
            )
            when (state) {
                BluetoothAdapter.STATE_ON -> {
                    Log.d(TAG, "BT 已开启")
                    onBtStateChanged?.invoke(true)
                }
                BluetoothAdapter.STATE_OFF -> {
                    Log.d(TAG, "BT 已关闭")
                    onBtStateChanged?.invoke(false)
                }
            }
        }
    }

    // ── 蓝牙操作 ──────────────────────────────────────────

    fun isBtEnabled(): Boolean = btAdapter?.isEnabled == true

    // 已开则跳过，未开才开
    fun enableBtIfNeeded(): Boolean {
        return if (isBtEnabled()) {
            Log.d(TAG, "BT 已是开启状态，跳过")
            true
        } else {
            Log.d(TAG, "BT 未开启，正在开启")
            btAdapter?.enable() == true
        }
    }

    // 标准API关闭蓝牙（非永久）
    fun disableBt(): Boolean {
        return if (!isBtEnabled()) {
            Log.d(TAG, "BT 已是关闭状态，跳过")
            true
        } else {
            Log.d(TAG, "正在关闭 BT")
            btAdapter?.disable() == true
        }
    }

    // 长按重试：关BT → 等2秒 → 开BT → 回调通知状态机继续
    fun btRetry(onComplete: () -> Unit) {
        Thread {
            Log.d(TAG, "BT 复位：关闭中...")
            btAdapter?.disable()
            Thread.sleep(2500)
            Log.d(TAG, "BT 复位：开启中...")
            btAdapter?.enable()
            Thread.sleep(1000)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                onComplete()
            }
        }.start()
    }

    // ── WiFi操作 ──────────────────────────────────────────

    fun isWifiEnabled(): Boolean = wifiManager.isWifiEnabled

    // 已开则跳过，未开才开
    @Suppress("DEPRECATION")
    fun enableWifiIfNeeded(): Boolean {
        return if (isWifiEnabled()) {
            Log.d(TAG, "WiFi 已是开启状态，跳过")
            true
        } else {
            Log.d(TAG, "WiFi 未开启，正在开启")
            wifiManager.setWifiEnabled(true)
        }
    }

    // ── 打开WiFi设置主界面 ─────────────────────────────────

    fun openWifiSettings() {
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "已打开 WiFi 设置界面")
        } catch (e: Exception) {
            Log.e(TAG, "打开 WiFi 设置失败: ${e.message}")
        }
    }

    // ── BT状态监听（注册/注销）────────────────────────────

    fun registerBtStateListener() {
        if (btReceiverRegistered) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(btStateReceiver, filter)
        btReceiverRegistered = true
        Log.d(TAG, "BT 状态监听已注册")
    }

    fun unregisterBtStateListener() {
        if (!btReceiverRegistered) return
        try {
            context.unregisterReceiver(btStateReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "注销 BT 监听异常: ${e.message}")
        }
        btReceiverRegistered = false
        Log.d(TAG, "BT 状态监听已注销")
    }

    // ── 释放资源 ──────────────────────────────────────────

    fun release() {
        unregisterBtStateListener()
        onBtStateChanged = null
    }
}