package com.maomao.hulian

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

class ConnectionStateMachine(
    private val context: Context,
    private val prefs: PrefsHelper,
    private val btWifiManager: BtWifiManager,
    private val hotspotManager: HotspotManager,
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

    private var wifiDisconnectedFromTarget = false
    private var btTurnedOff = false

    // 热点模式：互联App启动后的"无手机连接"提醒定时器
    private var hotspotReminderShown = false
    private val noConnectionReminderRunnable = Runnable {
        if (hotspotReminderShown) return@Runnable
        hotspotReminderShown = true
        FileLogger.w(TAG, "热点模式：互联App启动后60秒内无手机连接到车机热点，发出提醒")
        Toast.makeText(context, "请确认手机已连接车机热点", Toast.LENGTH_LONG).show()
    }

    private val timeoutRunnable = Runnable {
        Log.d(TAG, "热点等待超时")
        FileLogger.i(TAG, "热点等待超时")
        onEvent(ConnectionEvent.WaitTimeout)
    }

    private val autoStartRunnable = Runnable {
        Log.d(TAG, "自动模式延时到，开始连接")
        FileLogger.i(TAG, "自动模式延时到，开始连接")
        currentMode = RunMode.AUTO
        onEvent(ConnectionEvent.StartRequested)
    }

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

        btWifiManager.registerBtStateListener()

        Log.d(TAG, "状态机初始化完成")
        FileLogger.i(TAG, "状态机初始化完成, 连接模式=${prefs.connectMode}")
    }

    fun scheduleAutoStart() {
        if (!prefs.autoModeEnabled) {
            Log.d(TAG, "自动模式未启用，跳过")
            FileLogger.i(TAG, "自动模式未启用，跳过")
            return
        }
        val delayMs = prefs.bootDelaySeconds * 1000L
        Log.d(TAG, "自动模式将在 ${prefs.bootDelaySeconds} 秒后启动")
        FileLogger.i(TAG, "自动模式将在 ${prefs.bootDelaySeconds} 秒后启动")
        handler.postDelayed(autoStartRunnable, delayMs)
    }

    fun cancelAutoStart() {
        handler.removeCallbacks(autoStartRunnable)
        Log.d(TAG, "已取消自动模式延时")
    }

    fun onEvent(event: ConnectionEvent) {
        Log.d(TAG, "onEvent=$event  state=$currentState  mode=$currentMode")
        FileLogger.i(TAG, "事件: $event | 状态: $currentState | 模式: $currentMode | 连接模式: ${prefs.connectMode}")
        when (currentState) {
            ConnectionState.IDLE            -> handleIdle(event)
            ConnectionState.INITIATING      -> handleInitiating(event)
            ConnectionState.WAITING_HOTSPOT -> handleWaiting(event)
            ConnectionState.TIMEOUT         -> handleTimeout(event)
            ConnectionState.LAUNCHING       -> handleLaunching(event)
            ConnectionState.CONNECTED       -> handleConnected(event)
        }
    }

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
                // WiFi模式：进入等待热点连接
                hotspotMonitor.register()
                startTimeoutCountdown()
                transitionTo(ConnectionState.WAITING_HOTSPOT)
            }
            is ConnectionEvent.HotspotReady -> {
                // 热点模式：热点已开启，直接启动互联App
                FileLogger.i(TAG, "热点已开启，直接启动互联App（跳过等待）")
                transitionTo(ConnectionState.LAUNCHING)
                doLaunch()
            }
            is ConnectionEvent.BtRetryRequested -> {
                doBtRetryThenInitiate()
            }
            is ConnectionEvent.CancelRequested -> {
                btWifiManager.cancelPendingRequests()
                hotspotManager.cancelPendingCallback()
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
                btWifiManager.cancelPendingRequests()
                hotspotManager.cancelPendingCallback()
                resetToIdle()
            }
            else -> Unit
        }
    }

    private fun handleTimeout(event: ConnectionEvent) {
        when (event) {
            is ConnectionEvent.StartRequested -> {
                transitionTo(ConnectionState.INITIATING)
                doInitiating()
            }
            is ConnectionEvent.BtRetryRequested -> {
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
                wifiDisconnectedFromTarget = false
                btTurnedOff = false

                if (prefs.connectMode == ConnectMode.HOTSPOT_MODE) {
                    // 热点模式：启动后不等待连接，启动"无连接提醒"定时器
                    handler.postDelayed(noConnectionReminderRunnable, 60000L)
                } else {
                    // WiFi模式：重新注册热点监听
                    hotspotMonitor.register()
                }

                appMonitor.start(prefs.targetAppPkg)
                transitionTo(ConnectionState.CONNECTED)
            }
            is ConnectionEvent.CancelRequested -> {
                btWifiManager.cancelPendingRequests()
                hotspotManager.cancelPendingCallback()
                resetToIdle()
            }
            else -> Unit
        }
    }

    private fun handleConnected(event: ConnectionEvent) {
        when (event) {
            is ConnectionEvent.AppExited -> {
                Log.d(TAG, "互联App退出，回到IDLE")
                FileLogger.i(TAG, "互联App退出，回到IDLE")
                appMonitor.stop()
                hotspotMonitor.unregister()
                handler.removeCallbacks(noConnectionReminderRunnable)

                // 热点模式下，退出时关闭热点
                if (prefs.connectMode == ConnectMode.HOTSPOT_MODE) {
                    hotspotManager.disableHotspot()
                }

                resetToIdle()
            }

            is ConnectionEvent.WifiDisconnected -> {
                wifiDisconnectedFromTarget = true
                Log.d(TAG, "条件3：WiFi已断开，等待BT关闭")
                FileLogger.i(TAG, "条件3：WiFi已断开，等待BT关闭")
                checkCondition3()
            }

            is ConnectionEvent.BtTurnedOff -> {
                btTurnedOff = true
                if (!hotspotMonitor.isConnectedToTarget()) {
                    wifiDisconnectedFromTarget = true
                }
                Log.d(TAG, "条件3：BT已关闭，WiFi断开=$wifiDisconnectedFromTarget")
                FileLogger.i(TAG, "条件3：BT已关闭，WiFi断开=$wifiDisconnectedFromTarget")
                checkCondition3()
            }

            is ConnectionEvent.BtRetryRequested -> {
                appMonitor.stop()
                hotspotMonitor.unregister()
                handler.removeCallbacks(noConnectionReminderRunnable)
                transitionTo(ConnectionState.INITIATING)
                doBtRetryThenInitiate()
            }

            is ConnectionEvent.CancelRequested -> {
                appMonitor.stop()
                hotspotMonitor.unregister()
                handler.removeCallbacks(noConnectionReminderRunnable)
                btWifiManager.cancelPendingRequests()
                hotspotManager.cancelPendingCallback()
                resetToIdle()
            }

            else -> Unit
        }
    }

    private fun checkCondition3() {
        if (!wifiDisconnectedFromTarget || !btTurnedOff) return

        Log.d(TAG, "条件3满足，开始清理，模式=$currentMode")
        FileLogger.i(TAG, "条件3满足，开始清理，模式=$currentMode")
        appMonitor.stop()
        handler.removeCallbacks(noConnectionReminderRunnable)

        // 热点模式下，结束时关闭热点
        if (prefs.connectMode == ConnectMode.HOTSPOT_MODE) {
            hotspotManager.disableHotspot()
        }

        if (currentMode == RunMode.MANUAL) {
            ShellExecutor.killBluetooth(prefs.btKillCommand) { result ->
                Log.d(TAG, "vendor BT命令结果: success=${result.success}")
                FileLogger.i(TAG, "vendor BT命令结果: success=${result.success}")
                handler.post {
                    // 防止异步回调到达时状态已被重置（如用户重启任务）
                    if (currentState == ConnectionState.CONNECTED) {
                        resetToIdle()
                    } else {
                        FileLogger.i(TAG, "killBluetooth回调到达时状态=$currentState，跳过resetToIdle")
                    }
                }
            }
        } else {
            resetToIdle()
        }
    }

    private fun doInitiating() {
        FileLogger.i(TAG, "doInitiating: 开始初始化流程，连接模式=${prefs.connectMode}")

        btWifiManager.enableBtIfNeeded(onComplete = { btSuccess ->
            if (!btSuccess) FileLogger.w(TAG, "BT开启超时，继续流程")
            FileLogger.i(TAG, "BT已就绪")

            if (prefs.connectMode == ConnectMode.HOTSPOT_MODE) {
                // 热点模式：开启车机热点
                FileLogger.i(TAG, "热点模式：开始开启车机热点")
                hotspotManager.enableHotspot(onComplete = { hotspotSuccess ->
                    if (!hotspotSuccess) FileLogger.w(TAG, "热点开启超时或失败")
                    FileLogger.i(TAG, "热点流程完成, success=$hotspotSuccess")
                    handler.post {
                        if (hotspotSuccess) {
                            onEvent(ConnectionEvent.HotspotReady)
                        } else {
                            // 热点开启失败，仍打开设置页面
                            hotspotManager.openHotspotSettings()
                            onEvent(ConnectionEvent.HotspotReady)
                        }
                    }
                }, timeoutMs = 30000L)
            } else {
                // WiFi模式：开启WiFi
                btWifiManager.enableWifiIfNeeded(onComplete = { wifiSuccess ->
                    if (!wifiSuccess) FileLogger.w(TAG, "WiFi开启超时或失败")
                    FileLogger.i(TAG, "WiFi已就绪, success=$wifiSuccess")
                    handler.post {
                        if (wifiSuccess) {
                            btWifiManager.openWifiSettings()
                        }
                        onEvent(ConnectionEvent.BtWifiReady)
                    }
                }, timeoutMs = 30000L)
            }
        }, timeoutMs = 12000L)
    }

    private fun doLaunch() {
        val pkg = prefs.targetAppPkg
        if (pkg.isEmpty()) {
            Log.e(TAG, "目标App未配置")
            FileLogger.e(TAG, "目标App未配置")
            resetToIdle()
            return
        }
        FileLogger.i(TAG, "启动互联App: $pkg")
        val success = appLauncher.launchTarget(pkg)
        if (success) {
            FileLogger.i(TAG, "互联App启动成功")
            onEvent(ConnectionEvent.AppLaunched)
        } else {
            Log.e(TAG, "启动互联App失败")
            FileLogger.e(TAG, "启动互联App失败")
            resetToIdle()
        }
    }

    private fun doBtRetryThenInitiate() {
        btWifiManager.btRetry {
            doInitiating()
        }
    }

    private fun startTimeoutCountdown() {
        val timeoutMs = prefs.hotspotTimeoutSeconds * 1000L
        handler.postDelayed(timeoutRunnable, timeoutMs)
        Log.d(TAG, "超时计时开始：${prefs.hotspotTimeoutSeconds}秒")
        FileLogger.i(TAG, "超时计时开始：${prefs.hotspotTimeoutSeconds}秒")
    }

    private fun stopTimeoutCountdown() {
        handler.removeCallbacks(timeoutRunnable)
        Log.d(TAG, "超时计时已取消")
    }

    private fun transitionTo(newState: ConnectionState) {
        Log.d(TAG, "状态转换: $currentState → $newState")
        FileLogger.i(TAG, "状态转换: $currentState → $newState")
        currentState = newState
        stateListener?.onStateChanged(newState, currentMode)
    }

    private fun resetToIdle() {
        wifiDisconnectedFromTarget = false
        btTurnedOff = false
        hotspotReminderShown = false
        transitionTo(ConnectionState.IDLE)
    }

    fun triggerManual() {
        cancelAutoStart()
        currentMode = RunMode.MANUAL
        // 如果正在运行中，先取消再重启
        if (currentState != ConnectionState.IDLE && currentState != ConnectionState.TIMEOUT) {
            FileLogger.i(TAG, "手动触发：当前状态=$currentState，先取消当前任务再重启")
            forceCancel()
        }
        onEvent(ConnectionEvent.StartRequested)
    }

    fun triggerBtRetry() {
        cancelAutoStart()
        if (currentMode != RunMode.AUTO) currentMode = RunMode.MANUAL
        // 如果正在运行中，先取消再重启
        if (currentState != ConnectionState.IDLE && currentState != ConnectionState.TIMEOUT) {
            FileLogger.i(TAG, "BT重试触发：当前状态=$currentState，先取消当前任务再重启")
            forceCancel()
        }
        onEvent(ConnectionEvent.BtRetryRequested)
    }

    /**
     * 强制取消当前任务，清理所有异步回调，回到IDLE。
     * 与 CancelRequested 事件不同，此方法直接清理不依赖状态分支。
     */
    private fun forceCancel() {
        stopTimeoutCountdown()
        handler.removeCallbacks(noConnectionReminderRunnable)
        hotspotMonitor.unregister()
        appMonitor.stop()
        btWifiManager.cancelPendingRequests()
        hotspotManager.cancelPendingCallback()
        resetToIdle()
    }

    fun triggerCancel() {
        onEvent(ConnectionEvent.CancelRequested)
    }

    fun release() {
        cancelAutoStart()
        stopTimeoutCountdown()
        handler.removeCallbacks(noConnectionReminderRunnable)
        appMonitor.release()
        hotspotMonitor.release()
        btWifiManager.release()
        hotspotManager.release()
        stateListener = null
        Log.d(TAG, "状态机已释放")
        FileLogger.i(TAG, "状态机已释放")
    }
}
