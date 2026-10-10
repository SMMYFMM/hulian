package com.maomao.hulian

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

class AppLauncher(private val context: Context) {

    private val TAG = "AppLauncher"

    // 互联App信息数据类
    data class AppInfo(
        val packageName: String,
        val label: String
    )

    // ── 检测已安装的互联App ───────────────────────────────

    fun detectInstalledApps(): List<AppInfo> {
        val result = mutableListOf<AppInfo>()
        val candidates = listOf(
            PrefsHelper.PKG_YILIAN to "亿连手机互联",
            PrefsHelper.PKG_BAIDU to "百度CarLife"
        )
        candidates.forEach { (prefix, defaultLabel) ->
            val pkg = findInstalledPackage(prefix) ?: return@forEach
            val label = getAppLabel(pkg) ?: defaultLabel
            result.add(AppInfo(pkg, label))
            Log.d(TAG, "检测到互联App: $label ($pkg)")
        }
        if (result.isEmpty()) {
            Log.w(TAG, "未检测到任何互联App")
        }
        return result
    }

    // ── 检测单个包名是否已安装 ────────────────────────────

    fun isInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    // ── 按前缀查找已安装包名（兼容共存版：原包名后追加英文）──

    /**
     * 优先精确匹配 [prefix]（原版包名），找不到则返回第一个以 [prefix] 开头的
     * 包名（共存版，如 net.easyconnxxx）。均未安装时返回 null。
     */
    fun findInstalledPackage(prefix: String): String? {
        if (prefix.isEmpty()) return null
        val installed = try {
            context.packageManager.getInstalledPackages(0)
        } catch (e: Exception) {
            FileLogger.w(TAG, "枚举已安装应用失败: ${e.message}")
            emptyList()
        }
        installed.firstOrNull { it.packageName == prefix }?.let { return it.packageName }
        return installed.firstOrNull { it.packageName.startsWith(prefix) }?.packageName
    }

    // ── 获取App显示名称 ───────────────────────────────────

    private fun getAppLabel(packageName: String): String? {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            null
        }
    }

    // ── 启动互联App ───────────────────────────────────────

    fun launch(packageName: String): Boolean {
        if (packageName.isEmpty()) {
            Log.e(TAG, "包名为空，无法启动")
            return false
        }
        if (!isInstalled(packageName)) {
            Log.e(TAG, "App未安装: $packageName")
            return false
        }
        return try {
            val intent = context.packageManager
                .getLaunchIntentForPackage(packageName)
                ?: run {
                    Log.e(TAG, "无法获取启动Intent: $packageName")
                    return false
                }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.d(TAG, "已启动: $packageName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "启动失败: $packageName，原因: ${e.message}")
            false
        }
    }

    // ── 亿连专用启动（指定具体Activity，响应更快）─────────

    fun launchYilian(packageName: String = PrefsHelper.PKG_YILIAN): Boolean {
        FileLogger.i(TAG, "尝试亿连指定Activity启动: $packageName")
        return try {
            val intent = Intent().apply {
                setClassName(
                    packageName,
                    "net.easyconn.ui.StandMainActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "亿连已启动（指定Activity）")
            true
        } catch (e: Exception) {
            Log.w(TAG, "亿连指定Activity启动失败，回退到包名启动: ${e.message}")
            launch(packageName)
        }
    }

    // ── 统一启动入口（根据包名自动选择最优启动方式）────────

    fun launchTarget(packageName: String): Boolean {
        FileLogger.i(TAG, "launchTarget: $packageName")
        return when {
            packageName.startsWith(PrefsHelper.PKG_YILIAN) -> launchYilian(packageName)
            else -> launch(packageName)
        }
    }
}