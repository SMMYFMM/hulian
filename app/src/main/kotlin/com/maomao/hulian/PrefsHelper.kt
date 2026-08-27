package com.maomao.hulian

import android.content.Context
import android.content.SharedPreferences

class PrefsHelper(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("hulian_prefs", Context.MODE_PRIVATE)

    companion object {
        // 互联App
        const val KEY_TARGET_APP_PKG = "target_app_pkg"
        const val KEY_TARGET_APP_LABEL = "target_app_label"

        // 热点SSID
        const val KEY_TARGET_SSID = "target_ssid"

        // 自动模式
        const val KEY_AUTO_MODE_ENABLED = "auto_mode_enabled"
        const val KEY_BOOT_DELAY_SECONDS = "boot_delay_seconds"
        const val KEY_HOTSPOT_TIMEOUT_SECONDS = "hotspot_timeout_seconds"

        // 悬浮窗
        const val KEY_FLOAT_WINDOW_ENABLED = "float_window_enabled"
        const val KEY_FLOAT_WIN_X = "float_win_x"
        const val KEY_FLOAT_WIN_Y = "float_win_y"
        const val KEY_FLOAT_WIN_SIZE = "float_win_size"

        // 高级
        const val KEY_BT_KILL_COMMAND = "bt_kill_command"
        const val KEY_BOOT_AUTO_START = "boot_auto_start"

        // 连接模式
        const val KEY_CONNECT_MODE = "connect_mode"

        // 默认值
        const val DEFAULT_TARGET_SSID = "SMMY80"
        const val DEFAULT_BOOT_DELAY = 15
        const val DEFAULT_HOTSPOT_TIMEOUT = 60
        const val DEFAULT_FLOAT_WIN_SIZE = 80
        const val DEFAULT_BT_KILL_COMMAND = "service call fce-bt-misc-ctrl 2"

        // 已知互联App包名
        const val PKG_YILIAN = "net.easyconn"
        const val PKG_BAIDU = "com.baidu.carlifevehicle"
    }

    // ── 互联App ──────────────────────────────────────

    var targetAppPkg: String
        get() = prefs.getString(KEY_TARGET_APP_PKG, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TARGET_APP_PKG, v).apply()

    var targetAppLabel: String
        get() = prefs.getString(KEY_TARGET_APP_LABEL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TARGET_APP_LABEL, v).apply()

    // ── 热点SSID ─────────────────────────────────────

    var targetSsid: String
        get() = prefs.getString(KEY_TARGET_SSID, DEFAULT_TARGET_SSID) ?: DEFAULT_TARGET_SSID
        set(v) = prefs.edit().putString(KEY_TARGET_SSID, v).apply()

    // ── 自动模式 ──────────────────────────────────────

    var autoModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_MODE_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_MODE_ENABLED, v).apply()

    var bootDelaySeconds: Int
        get() = prefs.getInt(KEY_BOOT_DELAY_SECONDS, DEFAULT_BOOT_DELAY)
        set(v) = prefs.edit().putInt(KEY_BOOT_DELAY_SECONDS, v).apply()

    var hotspotTimeoutSeconds: Int
        get() = prefs.getInt(KEY_HOTSPOT_TIMEOUT_SECONDS, DEFAULT_HOTSPOT_TIMEOUT)
        set(v) = prefs.edit().putInt(KEY_HOTSPOT_TIMEOUT_SECONDS, v).apply()

    // ── 悬浮窗 ────────────────────────────────────────

    var floatWindowEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLOAT_WINDOW_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_FLOAT_WINDOW_ENABLED, v).apply()

    var floatWinX: Int
        get() = prefs.getInt(KEY_FLOAT_WIN_X, 100)
        set(v) = prefs.edit().putInt(KEY_FLOAT_WIN_X, v).apply()

    var floatWinY: Int
        get() = prefs.getInt(KEY_FLOAT_WIN_Y, 100)
        set(v) = prefs.edit().putInt(KEY_FLOAT_WIN_Y, v).apply()

    var floatWinSize: Int
        get() = prefs.getInt(KEY_FLOAT_WIN_SIZE, DEFAULT_FLOAT_WIN_SIZE)
        set(v) = prefs.edit().putInt(KEY_FLOAT_WIN_SIZE, v).apply()

    // ── 高级 ─────────────────────────────────────────

    var btKillCommand: String
        get() = prefs.getString(KEY_BT_KILL_COMMAND, DEFAULT_BT_KILL_COMMAND) ?: DEFAULT_BT_KILL_COMMAND
        set(v) = prefs.edit().putString(KEY_BT_KILL_COMMAND, v).apply()

    var bootAutoStart: Boolean
        get() = prefs.getBoolean(KEY_BOOT_AUTO_START, true)
        set(v) = prefs.edit().putBoolean(KEY_BOOT_AUTO_START, v).apply()

    // ── 连接模式 ─────────────────────────────────────

    var connectMode: ConnectMode
        get() {
            val name = prefs.getString(KEY_CONNECT_MODE, ConnectMode.WIFI_MODE.name) ?: ConnectMode.WIFI_MODE.name
            return try { ConnectMode.valueOf(name) } catch (e: Exception) { ConnectMode.WIFI_MODE }
        }
        set(v) = prefs.edit().putString(KEY_CONNECT_MODE, v.name).apply()

    var firstLaunchDone: Boolean
        get() = prefs.getBoolean("first_launch_done", false)
        set(v) = prefs.edit().putBoolean("first_launch_done", v).apply()

    // ── 工具方法 ──────────────────────────────────────

    // 检查是否已完成基本配置（App和SSID都设置了）
    fun isConfigured(): Boolean {
        return targetAppPkg.isNotEmpty() && targetSsid.isNotEmpty()
    }

    // 重置所有设置为默认值
    fun resetAll() {
        prefs.edit().clear().apply()
    }
}