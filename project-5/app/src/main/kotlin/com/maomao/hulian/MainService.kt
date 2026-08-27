package com.maomao.hulian

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

class MainService : Service(), StateListener {

    private val TAG = "MainService"

    private lateinit var prefs: PrefsHelper
    private lateinit var btWifiManager: BtWifiManager
    private lateinit var hotspotMonitor: HotspotMonitor
    private lateinit var appMonitor: AppMonitor
    private lateinit var appLauncher: AppLauncher
    private lateinit var stateMachine: ConnectionStateMachine
    private lateinit var floatingWindow: FloatingWindow

    companion object {
        // MainActivity通过此引用访问Service，为null则Service未运行
        var instance: MainService? = null

        const val CHANNEL_ID = "hulian_channel"
        const val NOTIFICATION_ID = 1001

        // 广播Action，通知MainActivity更新UI
        const val BROADCAST_STATE_CHANGED = "com.maomao.hulian.STATE_CHANGED"
        const val EXTRA_STATE = "state"
        const val EXTRA_MODE = "mode"
    }

    // ── 生命周期 ──────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "MainService onCreate")

        initModules()
        startForegroundWithNotification()

        // 显示悬浮窗
        if (prefs.floatWindowEnabled) {
            floatingWindow.show()
        }

        // 初始化状态机（注册BT广播等）
        stateMachine.init()

        // 自动模式延时启动
        stateMachine.scheduleAutoStart()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY：被系统杀死后自动重启
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        stateMachine.release()
        floatingWindow.release()
        Log.d(TAG, "MainService onDestroy")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── 初始化所有模块 ────────────────────────────────────

    private fun initModules() {
        prefs = PrefsHelper(this)

        btWifiManager = BtWifiManager(this)
        hotspotMonitor = HotspotMonitor(this, prefs)
        appMonitor = AppMonitor(this)
        appLauncher = AppLauncher(this)

        stateMachine = ConnectionStateMachine(
            context = this,
            prefs = prefs,
            btWifiManager = btWifiManager,
            hotspotMonitor = hotspotMonitor,
            appMonitor = appMonitor,
            appLauncher = appLauncher
        )
        stateMachine.stateListener = this

        floatingWindow = FloatingWindow(this, prefs)

        // 悬浮窗单击 → 手动触发主流程
        floatingWindow.onSingleClick = {
            stateMachine.triggerManual()
        }

        // 悬浮窗长按 → BT复位重试
        floatingWindow.onLongClick = {
            stateMachine.triggerBtRetry()
        }
    }

    // ── StateListener 实现 ────────────────────────────────

    override fun onStateChanged(newState: ConnectionState, mode: RunMode) {
        Log.d(TAG, "状态变化: $newState ($mode)")

        // 更新悬浮窗外观
        floatingWindow.updateState(newState)

        // 广播给MainActivity更新UI
        val intent = Intent(BROADCAST_STATE_CHANGED).apply {
            putExtra(EXTRA_STATE, newState.name)
            putExtra(EXTRA_MODE, mode.name)
        }
        sendBroadcast(intent)
    }

    // ── 前台通知 ──────────────────────────────────────────

    private fun startForegroundWithNotification() {
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            flags
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    // ── 供MainActivity调用的公开方法 ──────────────────────

    // 获取当前状态
    fun getCurrentState(): ConnectionState = stateMachine.currentState
    fun getCurrentMode(): RunMode = stateMachine.currentMode

    // 手动操作
    fun triggerManual() = stateMachine.triggerManual()
    fun triggerBtRetry() = stateMachine.triggerBtRetry()
    fun triggerCancel() = stateMachine.triggerCancel()

    // 设置改变后刷新悬浮窗
    fun refreshFloatWindow() {
        if (prefs.floatWindowEnabled) {
            floatingWindow.show()
            floatingWindow.updateState(stateMachine.currentState)
        } else {
            floatingWindow.hide()
        }
    }

    // 悬浮窗大小改变后刷新
    fun refreshFloatWindowSize() {
        floatingWindow.updateSize(prefs.floatWinSize)
    }
}