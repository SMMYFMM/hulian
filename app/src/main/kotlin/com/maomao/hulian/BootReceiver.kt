package com.maomao.hulian

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    private val TAG = "BootReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d(TAG, "开机广播收到，准备启动 MainService")
        FileLogger.init(context)
        FileLogger.i(TAG, "=== 开机广播收到 ===")

        val prefs = PrefsHelper(context)

        // 未开启开机自启则不启动
        if (!prefs.bootAutoStart) {
            Log.d(TAG, "开机自启已关闭，跳过")
            FileLogger.i(TAG, "开机自启已关闭")
            return
        }

        startMainService(context)
    }

    private fun startMainService(context: Context) {
        val serviceIntent = Intent(context, MainService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "MainService 已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动 MainService 失败: ${e.message}")
        }
    }
}