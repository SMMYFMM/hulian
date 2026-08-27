package com.maomao.hulian

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

class ConnectionStateMachine(
    private val context: Context,
    private val prefs: PrefsHelper,
    private val btWifiManager: BtWifiManager,
    private val hotspotMonitor: HotspotMonitor,
    private val appMonitor: AppMonitor,
    private val appLauncher: AppLauncher
) {
    private val TAG = "ConnectionStateMachine"
    private val handler = Handler(Looper.getMainLooper())

    var currentState: ConnectionState = ConnectionState.IDLE
        private set

    var currentMode: RunMode = RunMode.MANUAL
        private set

    var stateListener: StateListener? = null

    // 条件3：两个条件需同时满足
    private var wifiDisconnectedFromTarget = false
    private var btTurnedOff = false

    // 超时Runnable
    private val timeoutRunnable = Runnable {
        Log.d(TAG, "热点等待超时")
        onEvent(ConnectionEvent.WaitTimeout)
    }

    // 自动模式延时启动Runnable
    private val autoStartRunnable = Runnable {
        Log.d(TAG, "自动模式延时到，开始连接")
        currentMode = RunMode.AUTO
        onEvent(ConnectionEvent.StartRequested)
    }

    // ── 初始化 ────────────────────────────────────────────

    fun init() {
        btWifiManager.onBtStateChanged = { isOn ->
            if (!isOn) onEvent(ConnectionEvent.BtTurnedOff)
        }

        hotspotMonitor.onTargetSsidConnected = {
            onEvent(ConnectionEvent.HotspotConnected)
        }

        hotspotMonitor.onTargetSsidDisconnected = {
            onEvent(ConnectionEvent.WifiDisconnected)
        }

        appMonitor.onAppExited = {
            onEvent(ConnectionEvent.AppExited)
        }

        // BT状态监听常驻，用于条件3检测
        btWifiManager.registerBtStateListener()

        Log.d(TAG, "状态机初始化完成")
    }

    // ── 自动模式延时启动 ───────────────────────────────────

    fun scheduleAutoStart() {
        if (!prefs.autoModeEnabled) {
            Log.d(TAG, "自动模式未启用，跳过")
            return
        }
        val delayMs = prefs.bootDelaySeconds * 1000L
        Log.d(TAG, "自动模式将在 ${prefs.bootDelaySeconds} 秒后启动")
        handler.postDelayed(autoStartRunnable, delayMs)
    }

    fun cancelAutoStart() {
        handler.removeCallbacks(autoStartRunnable)
        Log.d(TAG, "已取消自动模式延时")
    }

    // ── 核心事件分发 ──────────────────────────────────────

    fun onEvent(event: ConnectionEvent) {
        Log.d(TAG, "onEvent=$event  state=$currentState  mode=$currentMode")
        when (currentState) {
            ConnectionState.IDLE            -> handleIdle(event)
            ConnectionState.INITIATING      -> handleInitiating(event)
            ConnectionState.WAITING_HOTSPOT -> handleWaiting(event)
            ConnectionState.TIMEOUT         -> handleTimeout(event)
            ConnectionState.LAUNCHING       -> handleLaunching(event)
            ConnectionState.CONNECTED       -> handleConnected(event)
        }
    }

    // ── 各状态事件处理 ────────────────────────────────────

    private fun handleIdle(event: ConnectionEvent) {
        when (event) {
            is ConnectionEvent.StartRequested -> {
                transitionTo(ConnectionState.INITIATING)
                doInitiating()
            }
            is ConnectionEvent.BtRetryRequested -> {
                currentMode = RunMode.MANUAL
                transitionTo(ConnectionState.INITIATING)
                doBtRetryThenInitiate()
            }
            else -> Unit
        }
    }

    private fun handleInitiating(event: ConnectionEvent) {
        when (event) {
            is ConnectionEvent.BtWifiReady -> {
                hotspotMonitor.register()
                startTimeoutCountdown()
                transitionTo(ConnectionState.WAITING_HOTSPOT)
            }
            is ConnectionEvent.BtRetryRequested -> {
                doBtRetryThenInitiate()
            }
            is ConnectionEvent.CancelRequested -> {
                resetToIdle()
            }
            else -> Unit
        }
    }

    private fun handleWaiting(event: ConnectionEvent) {
        when (event) {
            is ConnectionEvent.HotspotConnected -> {
                stopTimeoutCountdown()
                hotspotMonitor.unregister()
                transitionTo(ConnectionState.LAUNCHING)
                doLaunch()
            }
            is ConnectionEvent.WaitTimeout -> {
                hotspotMonitor.unregister()
                transitionTo(ConnectionState.TIMEOUT)
            }
            is ConnectionEvent.BtRetryRequested -> {
                stopTimeoutCountdown()
                hotspotMonitor.unregister()
                transitionTo(ConnectionState.INITIATING)
                doBtRetryThenInitiate()
            }
            is ConnectionEvent.CancelRequested -> {
                stopTimeoutCountdown()
                hotspotMonitor.unregister()
                resetToIdle()
            }
            else -> Unit
        }
    }

    private fun handleTimeout(event: ConnectionEvent) {
        when (event) {
            is ConnectionEvent.StartRequested -> {
                // 超时后单击：重新走流程
                transitionTo(ConnectionState.INITIATING)
                doInitiating()
            }
            is ConnectionEvent.BtRetryRequested -> {
                // 超时后长按：BT复位再走流程
                transitionTo(ConnectionState.INITIATING)
                doBtRetryThenInitiate()
            }
            is ConnectionEvent.CancelRequested -> {
                resetToIdle()
            }
            else -> Unit
        }
    }

    private fun handleLaunching(event: ConnectionEvent) {
        when (event) {
            is ConnectionEvent.AppLaunched -> {
                // 重置条件3标志位
                wifiDisconnectedFromTarget = false
                btTurnedOff = false
                // 重新注册热点监听（用于条件3的WiFi断开检测）
                hotspotMonitor.register()
                appMonitor.start(prefs.targetAppPkg)
                transitionTo(ConnectionState.CONNECTED)
            }
            is ConnectionEvent.CancelRequested -> {
                resetToIdle()
            }
            else -> Unit
        }
    }

    private fun handleConnected(event: ConnectionEvent) {
        when (event) {

            is ConnectionEvent.AppExited -> {
                // 用户主动退出App：直接回IDLE，不执行vendor命令
                Log.d(TAG, "互联App退出，回到IDLE")
                appMonitor.stop()
                hotspotMonitor.unregister()
                resetToIdle()
            }

            is ConnectionEvent.WifiDisconnected -> {
                wifiDisconnectedFromTarget = true
                Log.d(TAG, "条件3：WiFi已断开，等待BT关闭")
                checkCondition3()
            }

            is ConnectionEvent.BtTurnedOff -> {
                btTurnedOff = true
                // BT关了，同步检查WiFi是否也已断开
                if (!hotspotMonitor.isConnectedToTarget()) {
                    wifiDisconnectedFromTarget = true
                }
                Log.d(TAG, "条件3：BT已关闭，WiFi断开=$wifiDisconnectedFromTarget")
                checkCondition3()
            }

            is ConnectionEvent.BtRetryRequested -> {
                // CONNECTED状态长按：不屏蔽，重新走流程
                appMonitor.stop()
                hotspotMonitor.unregister()
                transitionTo(ConnectionState.INITIATING)
                doBtRetryThenInitiate()
            }

            is ConnectionEvent.CancelRequested -> {
                appMonitor.stop()
                hotspotMonitor.unregister()
                resetToIdle()
            }

            else -> Unit
        }
    }

    // ── 条件3判断与执行 ───────────────────────────────────

    private fun checkCondition3() {
        if (!wifiDisconnectedFromTarget || !btTurnedOff) return

        Log.d(TAG, "条件3满足，开始清理，模式=$currentMode")
        appMonitor.stop()

        if (currentMode == RunMode.MANUAL) {
            // 手动模式：执行vendor命令永久关BT
            ShellExecutor.killBluetooth(prefs.btKillCommand) { result ->
                Log.d(TAG, "vendor BT命令结果: success=${result.success}")
                resetToIdle()
            }
        } else {
            // 自动模式：不执行vendor命令，保留BT下次开机自动开的能力
            resetToIdle()
        }
    }

    // ── 流程动作 ──────────────────────────────────────────

    private fun doInitiating() {
        Thread {
            btWifiManager.enableBtIfNeeded()
            Thread.sleep(500)
            btWifiManager.enableWifiIfNeeded()
            Thread.sleep(500)
            handler.post {
                btWifiManager.openWifiSettings()
                onEvent(ConnectionEvent.BtWifiReady)
            }
        }.start()
    }

    private fun doLaunch() {
        val pkg = prefs.targetAppPkg
        if (pkg.isEmpty()) {
            Log.e(TAG, "目标App未配置")
            resetToIdle()
            return
        }
        val success = appLauncher.launchTarget(pkg)
        if (success) {
            onEvent(ConnectionEvent.AppLaunched)
        } else {
            Log.e(TAG, "启动互联App失败")
            resetToIdle()
        }
    }

    private fun doBtRetryThenInitiate() {
        btWifiManager.btRetry {
            doInitiating()
        }
    }

    // ── 超时计时 ──────────────────────────────────────────

    private fun startTimeoutCountdown() {
        val timeoutMs = prefs.hotspotTimeoutSeconds * 1000L
        handler.postDelayed(timeoutRunnable, timeoutMs)
        Log.d(TAG, "超时计时开始：${prefs.hotspotTimeoutSeconds}秒")
    }

    private fun stopTimeoutCountdown() {
        handler.removeCallbacks(timeoutRunnable)
        Log.d(TAG, "超时计时已取消")
    }

    // ── 状态转换 ──────────────────────────────────────────

    private fun transitionTo(newState: ConnectionState) {
        Log.d(TAG, "状态转换: $currentState → $newState")
        currentState = newState
        stateListener?.onStateChanged(newState, currentMode)
    }

    private fun resetToIdle() {
        wifiDisconnectedFromTarget = false
        btTurnedOff = false
        transitionTo(ConnectionState.IDLE)
    }

    // ── 外部触发入口 ──────────────────────────────────────

    // 单击：主流程
    fun triggerManual() {
        cancelAutoStart()
        currentMode = RunMode.MANUAL
        onEvent(ConnectionEvent.StartRequested)
    }

    // 长按：BT复位重试
    fun triggerBtRetry() {
        cancelAutoStart()
        if (currentMode != RunMode.AUTO) currentMode = RunMode.MANUAL
        onEvent(ConnectionEvent.BtRetryRequested)
    }

    // 取消
    fun triggerCancel() {
        onEvent(ConnectionEvent.CancelRequested)
    }

    // ── 释放资源 ──────────────────────────────────────────

    fun release() {
        cancelAutoStart()
        stopTimeoutCountdown()
        appMonitor.release()
        hotspotMonitor.release()
        btWifiManager.release()
        stateListener = null
        Log.d(TAG, "状态机已释放")
    }
}