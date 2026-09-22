package com.maomao.hulian

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast

class BtWifiManager(private val context: Context) {

    private val TAG = "BtWifiManager"

    private val btAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())

    var onBtStateChanged: ((isOn: Boolean) -> Unit)? = null

    private var pendingBtCallback: ((Boolean) -> Unit)? = null
    private var pendingTargetState: Int = -1
    private var pendingBtTimeoutRunnable: Runnable? = null

    private var pendingWifiCallback: ((Boolean) -> Unit)? = null
    private var pendingWifiTimeoutRunnable: Runnable? = null
    private var wifiReceiverRegistered = false
    private var btReceiverRegistered = false

    // WiFi后台使能任务ID，每次新请求递增，旧线程检测到不匹配时自动退出
    @Volatile
    private var wifiEnableTaskId: Int = 0

    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            when (state) {
                BluetoothAdapter.STATE_ON -> {
                    FileLogger.i(TAG, "BT状态变化: ON")
                    onBtStateChanged?.invoke(true)
                    if (pendingTargetState == BluetoothAdapter.STATE_ON && pendingBtCallback != null) {
                        clearPendingBtCallback(true)
                    }
                }
                BluetoothAdapter.STATE_OFF -> {
                    FileLogger.i(TAG, "BT状态变化: OFF")
                    onBtStateChanged?.invoke(false)
                    if (pendingTargetState == BluetoothAdapter.STATE_OFF && pendingBtCallback != null) {
                        clearPendingBtCallback(true)
                    }
                }
                BluetoothAdapter.STATE_TURNING_ON -> FileLogger.d(TAG, "BT: TURNING_ON")
                BluetoothAdapter.STATE_TURNING_OFF -> FileLogger.d(TAG, "BT: TURNING_OFF")
            }
        }
    }

    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != WifiManager.WIFI_STATE_CHANGED_ACTION) return
            val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
            when (state) {
                WifiManager.WIFI_STATE_ENABLED -> {
                    FileLogger.i(TAG, "WiFi状态变化: ENABLED")
                    clearPendingWifiCallback(true)
                }
                WifiManager.WIFI_STATE_ENABLING -> FileLogger.d(TAG, "WiFi: ENABLING")
                WifiManager.WIFI_STATE_DISABLED -> FileLogger.d(TAG, "WiFi: DISABLED")
            }
        }
    }

    fun isBtEnabled(): Boolean = btAdapter?.isEnabled == true

    fun enableBtIfNeeded(onComplete: (Boolean) -> Unit, timeoutMs: Long = 10000L) {
        if (isBtEnabled()) { FileLogger.i(TAG, "BT已是开启状态"); onComplete(true); return }
        if (btAdapter == null) { FileLogger.e(TAG, "BT适配器不可用"); onComplete(false); return }
        FileLogger.i(TAG, "BT未开启，正在开启(等待STATE_ON, 超时${timeoutMs}ms)")
        setupPendingBtCallback(BluetoothAdapter.STATE_ON, timeoutMs, onComplete)
        if (!btAdapter.enable()) { FileLogger.e(TAG, "btAdapter.enable()失败"); clearPendingBtCallback(false) }
    }

    fun disableBt(onComplete: (Boolean) -> Unit, timeoutMs: Long = 10000L) {
        if (!isBtEnabled()) { FileLogger.i(TAG, "BT已是关闭状态"); onComplete(true); return }
        if (btAdapter == null) { onComplete(false); return }
        FileLogger.i(TAG, "正在关闭BT(等待STATE_OFF)")
        setupPendingBtCallback(BluetoothAdapter.STATE_OFF, timeoutMs, onComplete)
        if (!btAdapter.disable()) { FileLogger.e(TAG, "btAdapter.disable()失败"); clearPendingBtCallback(false) }
    }

    fun enableBtIfNeeded(): Boolean {
        return if (isBtEnabled()) { FileLogger.i(TAG, "BT已是开启状态(同步)"); true }
        else { FileLogger.i(TAG, "BT未开启，正在开启(同步)"); btAdapter?.enable() == true }
    }

    fun disableBt(): Boolean {
        return if (!isBtEnabled()) { true } else { btAdapter?.disable() == true }
    }

    fun btRetry(onComplete: () -> Unit) {
        FileLogger.i(TAG, "=== BT复位重试开始 ===")
        if (btAdapter == null) { FileLogger.e(TAG, "BT适配器不可用"); onComplete(); return }
        disableBt(onComplete = { offSuccess ->
            if (!offSuccess) FileLogger.w(TAG, "BT关闭超时")
            FileLogger.i(TAG, "BT复位：开始重新开启")
            enableBtIfNeeded(onComplete = { onSuccess ->
                if (!onSuccess) FileLogger.w(TAG, "BT开启超时")
                FileLogger.i(TAG, "=== BT复位重试结束 ===")
                handler.post { onComplete() }
            }, timeoutMs = 15000L)
        }, timeoutMs = 10000L)
    }

    private fun setupPendingBtCallback(targetState: Int, timeoutMs: Long, callback: (Boolean) -> Unit) {
        // 丢弃旧回调，不触发它（避免旧回调干扰新流程）
        pendingBtTimeoutRunnable?.let { handler.removeCallbacks(it) }
        pendingBtTimeoutRunnable = null
        pendingTargetState = -1
        pendingBtCallback = null
        // 设置新回调
        pendingTargetState = targetState
        pendingBtCallback = callback
        pendingBtTimeoutRunnable = Runnable { FileLogger.w(TAG, "BT等待超时: $targetState"); clearPendingBtCallback(false) }
        handler.postDelayed(pendingBtTimeoutRunnable!!, timeoutMs)
    }

    private fun clearPendingBtCallback(success: Boolean) {
        pendingBtTimeoutRunnable?.let { handler.removeCallbacks(it) }
        pendingBtTimeoutRunnable = null
        pendingTargetState = -1
        pendingBtCallback?.let { handler.post { it(success) } }
        pendingBtCallback = null
    }

    fun isWifiEnabled(): Boolean = wifiManager.isWifiEnabled

    fun enableWifiIfNeeded(onComplete: (Boolean) -> Unit, timeoutMs: Long = 20000L) {
        if (isWifiEnabled()) {
            FileLogger.i(TAG, "WiFi已是开启状态")
            onComplete(true)
            return
        }
        FileLogger.i(TAG, "WiFi未开启，尝试多种方式开启...")
        registerWifiStateListener()
        setupPendingWifiCallback(timeoutMs, onComplete)

        // 递增任务ID，使旧的后台线程自动退出
        wifiEnableTaskId++
        val myTaskId = wifiEnableTaskId

        // 方案1: WifiManager API
        @Suppress("DEPRECATION")
        val apiResult = wifiManager.setWifiEnabled(true)
        FileLogger.d(TAG, "setWifiEnabled(true)=$apiResult")

        // 无论API返回true还是false，都启动延迟验证线程
        Thread {
            // 等待3秒让API方法生效
            Thread.sleep(3000)
            if (myTaskId != wifiEnableTaskId) {
                FileLogger.d(TAG, "WiFi使能线程被取代，退出")
                return@Thread
            }
            if (isWifiEnabled()) {
                FileLogger.i(TAG, "方案1成功: API方式开启WiFi")
                return@Thread
            }
            FileLogger.w(TAG, "方案1未生效，尝试替代方案...")

            // 方案2: settings put global wifi_on 1 + svc wifi enable
            FileLogger.i(TAG, "方案2: settings put global wifi_on 1")
            ShellExecutor.exec("settings put global wifi_on 1")
            Thread.sleep(3000)
            if (myTaskId != wifiEnableTaskId) return@Thread
            if (isWifiEnabled()) { FileLogger.i(TAG, "方案2成功: settings命令"); return@Thread }

            // 方案3: service call (Android 9 格式: packageName + boolean)
            FileLogger.i(TAG, "方案3a: service call wifi 24 s16 com.maomao.hulian i32 1")
            ShellExecutor.exec("service call wifi 24 s16 com.maomao.hulian i32 1")
            Thread.sleep(3000)
            if (myTaskId != wifiEnableTaskId) return@Thread
            if (isWifiEnabled()) { FileLogger.i(TAG, "方案3a成功: service call(带包名)"); return@Thread }

            // 方案3b: service call (旧格式: 仅boolean)
            FileLogger.i(TAG, "方案3b: service call wifi 24 i32 1")
            ShellExecutor.exec("service call wifi 24 i32 1")
            Thread.sleep(3000)
            if (myTaskId != wifiEnableTaskId) return@Thread
            if (isWifiEnabled()) { FileLogger.i(TAG, "方案3b成功: service call(旧格式)"); return@Thread }

            // 方案4: service call transaction 29 (备选transaction code)
            FileLogger.i(TAG, "方案4: service call wifi 29 i32 1 (备选code)")
            ShellExecutor.exec("service call wifi 29 i32 1")
            Thread.sleep(3000)
            if (myTaskId != wifiEnableTaskId) return@Thread
            if (isWifiEnabled()) { FileLogger.i(TAG, "方案4成功: service call code 29"); return@Thread }

            // 方案5: su root 方式
            FileLogger.i(TAG, "方案5: 检查su")
            val suCheck = ShellExecutor.exec("which su")
            if (suCheck.success && suCheck.output.isNotBlank()) {
                FileLogger.d(TAG, "su可用")

                // 5a: su + svc wifi enable
                FileLogger.i(TAG, "方案5a: su -c svc wifi enable")
                ShellExecutor.exec("su -c svc wifi enable")
                Thread.sleep(3000)
                if (myTaskId != wifiEnableTaskId) return@Thread
                if (isWifiEnabled()) { FileLogger.i(TAG, "方案5a成功: su svc"); return@Thread }

                // 5b: su + service call (带包名)
                FileLogger.i(TAG, "方案5b: su -c service call wifi 24 s16 com.maomao.hulian i32 1")
                ShellExecutor.exec("su -c service call wifi 24 s16 com.maomao.hulian i32 1")
                Thread.sleep(3000)
                if (myTaskId != wifiEnableTaskId) return@Thread
                if (isWifiEnabled()) { FileLogger.i(TAG, "方案5b成功: su service call"); return@Thread }

                // 5c: su + settings + svc组合
                FileLogger.i(TAG, "方案5c: su -c settings put global wifi_on 1")
                ShellExecutor.exec("su -c settings put global wifi_on 1")
                Thread.sleep(1000)
                ShellExecutor.exec("su -c svc wifi enable")
                Thread.sleep(3000)
                if (myTaskId != wifiEnableTaskId) return@Thread
                if (isWifiEnabled()) { FileLogger.i(TAG, "方案5c成功: su组合"); return@Thread }

                // 5d: su + cmd wifi (Android 10+命令，部分Android 9也可用)
                FileLogger.i(TAG, "方案5d: su -c cmd wifi set-wifi-enabled enabled")
                ShellExecutor.exec("su -c cmd wifi set-wifi-enabled enabled")
                Thread.sleep(3000)
                if (myTaskId != wifiEnableTaskId) return@Thread
                if (isWifiEnabled()) { FileLogger.i(TAG, "方案5d成功: su cmd wifi"); return@Thread }
            } else {
                FileLogger.w(TAG, "su不可用，跳过root方案")
            }

            // 方案6: WiFi开关切换（先关再开）
            FileLogger.i(TAG, "方案6: WiFi开关切换(先关再开)")
            @Suppress("DEPRECATION")
            wifiManager.setWifiEnabled(false)
            Thread.sleep(1000)
            if (myTaskId != wifiEnableTaskId) return@Thread
            @Suppress("DEPRECATION")
            wifiManager.setWifiEnabled(true)
            Thread.sleep(3000)
            if (myTaskId != wifiEnableTaskId) return@Thread
            if (isWifiEnabled()) { FileLogger.i(TAG, "方案6成功: 开关切换"); return@Thread }

            FileLogger.w(TAG, "所有自动方式均失败，打开WiFi设置页面")
            handler.post { openWifiSettingsWithPrompt() }
        }.start()
    }

    private fun openWifiSettingsWithPrompt() {
        FileLogger.i(TAG, "打开WiFi设置页面，等待用户手动开启WiFi")
        Toast.makeText(context, "请手动打开WiFi开关", Toast.LENGTH_LONG).show()
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            FileLogger.i(TAG, "已打开WiFi设置界面，等待用户操作...")
        } catch (e: Exception) { FileLogger.e(TAG, "打开WiFi设置失败", e) }
    }

    @Suppress("DEPRECATION")
    fun enableWifiIfNeeded(): Boolean {
        return if (isWifiEnabled()) { FileLogger.i(TAG, "WiFi已是开启状态"); true }
        else {
            FileLogger.i(TAG, "WiFi未开启，正在开启(同步)")
            val r = wifiManager.setWifiEnabled(true)
            FileLogger.d(TAG, "setWifiEnabled(true)=$r")
            isWifiEnabled()
        }
    }

    private fun setupPendingWifiCallback(timeoutMs: Long, callback: (Boolean) -> Unit) {
        // 丢弃旧回调，不触发它（避免旧回调干扰新流程）
        pendingWifiTimeoutRunnable?.let { handler.removeCallbacks(it) }
        pendingWifiTimeoutRunnable = null
        pendingWifiCallback = null
        // 设置新回调
        pendingWifiCallback = callback
        pendingWifiTimeoutRunnable = Runnable {
            val enabled = isWifiEnabled()
            FileLogger.w(TAG, "WiFi等待超时(${timeoutMs}ms), isWifiEnabled=$enabled")
            clearPendingWifiCallback(enabled)
        }
        handler.postDelayed(pendingWifiTimeoutRunnable!!, timeoutMs)
    }

    private fun clearPendingWifiCallback(success: Boolean) {
        pendingWifiTimeoutRunnable?.let { handler.removeCallbacks(it) }
        pendingWifiTimeoutRunnable = null
        pendingWifiCallback?.let { handler.post { it(success) } }
        pendingWifiCallback = null
    }

    fun openWifiSettings() {
        try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            FileLogger.i(TAG, "已打开WiFi设置界面")
        } catch (e: Exception) { FileLogger.e(TAG, "打开WiFi设置失败", e) }
    }

    fun registerBtStateListener() {
        if (btReceiverRegistered) return
        context.registerReceiver(btStateReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        btReceiverRegistered = true
        FileLogger.i(TAG, "BT状态监听已注册")
    }

    fun unregisterBtStateListener() {
        if (!btReceiverRegistered) return
        try { context.unregisterReceiver(btStateReceiver) } catch (e: Exception) { FileLogger.w(TAG, "注销BT监听异常", e) }
        btReceiverRegistered = false
    }

    fun registerWifiStateListener() {
        if (wifiReceiverRegistered) return
        context.registerReceiver(wifiStateReceiver, IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION))
        wifiReceiverRegistered = true
        FileLogger.i(TAG, "WiFi状态监听已注册")
    }

    fun unregisterWifiStateListener() {
        if (!wifiReceiverRegistered) return
        try { context.unregisterReceiver(wifiStateReceiver) } catch (e: Exception) { FileLogger.w(TAG, "注销WiFi监听异常", e) }
        wifiReceiverRegistered = false
    }

    fun release() {
        clearPendingBtCallback(false)
        clearPendingWifiCallback(false)
        unregisterBtStateListener()
        unregisterWifiStateListener()
        onBtStateChanged = null
    }

    /**
     * 取消所有挂起的 BT/WiFi 异步回调，丢弃旧回调不触发。
     * 用于状态机取消/重启时清理，防止旧回调干扰新流程。
     */
    fun cancelPendingRequests() {
        FileLogger.i(TAG, "取消所有挂起的BT/WiFi回调")
        // BT
        pendingBtTimeoutRunnable?.let { handler.removeCallbacks(it) }
        pendingBtTimeoutRunnable = null
        pendingTargetState = -1
        pendingBtCallback = null
        // WiFi
        pendingWifiTimeoutRunnable?.let { handler.removeCallbacks(it) }
        pendingWifiTimeoutRunnable = null
        pendingWifiCallback = null
        // 使后台WiFi使能线程自动退出
        wifiEnableTaskId++
    }
}
