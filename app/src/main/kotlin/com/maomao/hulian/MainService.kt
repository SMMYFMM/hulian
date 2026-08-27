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
    private lateinit var hotspotManager: HotspotManager
    private lateinit var hotspotMonitor: HotspotMonitor
    private lateinit var appMonitor: AppMonitor
    private lateinit var appLauncher: AppLauncher
    private lateinit var stateMachine: ConnectionStateMachine
    private lateinit var floatingWindow: FloatingWindow

    companion object {
        var instance: MainService? = null

        const val CHANNEL_ID = "hulian_channel"
        const val NOTIFICATION_ID = 1001

        const val BROADCAST_STATE_CHANGED = "com.maomao.hulian.STATE_CHANGED"
        const val EXTRA_STATE = "state"
        const val EXTRA_MODE = "mode"
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d(TAG, "MainService onCreate")

        FileLogger.init(this)
        FileLogger.i(TAG, "=== MainService onCreate ===")

        startForegroundWithNotification()

        initModules()

        if (prefs.floatWindowEnabled) {
            floatingWindow.show()
            FileLogger.i(TAG, "悬浮窗已显示")
        }

        stateMachine.init()
        FileLogger.i(TAG, "状态机已初始化")

        stateMachine.scheduleAutoStart()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        FileLogger.i(TAG, "=== MainService onDestroy ===")
        stateMachine.release()
        floatingWindow.release()
        Log.d(TAG, "MainService onDestroy")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun initModules() {
        prefs = PrefsHelper(this)

        btWifiManager = BtWifiManager(this)
        hotspotManager = HotspotManager(this)
        hotspotMonitor = HotspotMonitor(this, prefs)
        appMonitor = AppMonitor(this)
        appLauncher = AppLauncher(this)

        stateMachine = ConnectionStateMachine(
            context = this,
            prefs = prefs,
            btWifiManager = btWifiManager,
            hotspotManager = hotspotManager,
            hotspotMonitor = hotspotMonitor,
            appMonitor = appMonitor,
            appLauncher = appLauncher
        )
        stateMachine.stateListener = this

        floatingWindow = FloatingWindow(this, prefs)

        floatingWindow.onSingleClick = {
            stateMachine.triggerManual()
        }

        floatingWindow.onLongClick = {
            stateMachine.triggerBtRetry()
        }
    }

    override fun onStateChanged(newState: ConnectionState, mode: RunMode) {
        Log.d(TAG, "状态变化: $newState ($mode)")
        FileLogger.i(TAG, "状态变化通知: $newState ($mode)")

        floatingWindow.updateState(newState)

        val intent = Intent(BROADCAST_STATE_CHANGED).apply {
            putExtra(EXTRA_STATE, newState.name)
            putExtra(EXTRA_MODE, mode.name)
        }
        sendBroadcast(intent)
    }

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

    fun getCurrentState(): ConnectionState = stateMachine.currentState
    fun getCurrentMode(): RunMode = stateMachine.currentMode

    fun triggerManual() = stateMachine.triggerManual()
    fun triggerBtRetry() = stateMachine.triggerBtRetry()
    fun triggerCancel() = stateMachine.triggerCancel()

    fun refreshFloatWindow() {
        if (prefs.floatWindowEnabled) {
            floatingWindow.show()
            floatingWindow.updateState(stateMachine.currentState)
        } else {
            floatingWindow.hide()
        }
    }

    fun refreshFloatWindowSize() {
        floatingWindow.updateSize(prefs.floatWinSize)
    }
}
