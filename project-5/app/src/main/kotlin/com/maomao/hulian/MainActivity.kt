package com.maomao.hulian

import android.view.WindowManager
import android.util.DisplayMetrics
import androidx.core.view.WindowCompat
import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val TAG = "MainActivity"

    private lateinit var prefs: PrefsHelper

    // ── 顶栏 ──────────────────────────────────────────────
    private lateinit var tvTopState: TextView
    private lateinit var tvTopMode: TextView

    // ── 状态卡片 ──────────────────────────────────────────
    private lateinit var vStatusDot: View
    private lateinit var tvStateName: TextView
    private lateinit var tvStatusHint: TextView

    // ── App选择 ───────────────────────────────────────────
    private lateinit var tvNoAppWarning: TextView
    private lateinit var rbYilian: RadioButton
    private lateinit var rbBaidu: RadioButton
    private lateinit var tvYilianInstalled: TextView
    private lateinit var tvBaiduInstalled: TextView

    // ── 热点SSID ──────────────────────────────────────────
    private lateinit var etSsid: EditText
    private lateinit var btnSsidPick: Button

    // ── 自动模式 ──────────────────────────────────────────
    private lateinit var switchAutoMode: Switch
    private lateinit var etBootDelay: EditText
    private lateinit var etHotspotTimeout: EditText

    // ── 悬浮窗 ────────────────────────────────────────────
    private lateinit var switchFloatEnabled: Switch
    private lateinit var etFloatSize: EditText
    private lateinit var tvFloatPosition: TextView

    // ── 高级 ─────────────────────────────────────────────
    private lateinit var switchBootStart: Switch
    private lateinit var btnGrantOverlay: Button
    private lateinit var btnViewDisclaimer: Button

    // 防止TextWatcher循环触发
    private var isLoadingPrefs = false

    companion object {
        const val PREF_FIRST_LAUNCH = "first_launch_done"
        const val REQUEST_LOCATION = 1001
    }

    // ── 广播接收器 ────────────────────────────────────────

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != MainService.BROADCAST_STATE_CHANGED) return
            val stateName = intent.getStringExtra(MainService.EXTRA_STATE) ?: return
            val modeName = intent.getStringExtra(MainService.EXTRA_MODE) ?: return
            val state = try {
                ConnectionState.valueOf(stateName)
            } catch (e: Exception) { ConnectionState.IDLE }
            val mode = try {
                RunMode.valueOf(modeName)
            } catch (e: Exception) { RunMode.MANUAL }
            updateStateUI(state, mode)
            updateFloatPositionText()
        }
    }

    // ── 生命周期 ──────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
        View.SYSTEM_UI_FLAG_FULLSCREEN or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        setContentView(R.layout.activity_main)
        LicenseManager.check(this)
        prefs = PrefsHelper(this)
        initViews()

        // 首次启动显示免责声明
        val firstLaunchDone =
            prefs.firstLaunchDone
            

        if (!prefs.firstLaunchDone) {
            showDisclaimerDialog(isFirstLaunch = true)
        } else {
            onDisclaimerAgreed()
        }
    }

    override fun onResume() {
        super.onResume()
        checkOverlayPermission()
        loadPrefsToUI()
        updateFloatPositionText()
        // 同步当前Service状态
        MainService.instance?.let {
            updateStateUI(it.getCurrentState(), it.getCurrentMode())
        }
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(stateReceiver, IntentFilter(MainService.BROADCAST_STATE_CHANGED))
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(stateReceiver) } catch (e: Exception) { }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_LOCATION) {
            val granted = grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Toast.makeText(
                    this,
                    "位置权限被拒绝，SSID检测可能失败",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ── 初始化Views ───────────────────────────────────────

    private fun initViews() {
        tvTopState       = findViewById(R.id.tvTopState)
        tvTopMode        = findViewById(R.id.tvTopMode)
        vStatusDot       = findViewById(R.id.vStatusDot)
        tvStateName      = findViewById(R.id.tvStateName)
        tvStatusHint     = findViewById(R.id.tvStatusHint)
        tvNoAppWarning   = findViewById(R.id.tvNoAppWarning)
        rbYilian         = findViewById(R.id.rbYilian)
        rbBaidu          = findViewById(R.id.rbBaidu)
        tvYilianInstalled= findViewById(R.id.tvYilianInstalled)
        tvBaiduInstalled = findViewById(R.id.tvBaiduInstalled)
        etSsid           = findViewById(R.id.etSsid)
        btnSsidPick      = findViewById(R.id.btnSsidPick)
        switchAutoMode   = findViewById(R.id.switchAutoMode)
        etBootDelay      = findViewById(R.id.etBootDelay)
        etHotspotTimeout = findViewById(R.id.etHotspotTimeout)
        switchFloatEnabled = findViewById(R.id.switchFloatEnabled)
        etFloatSize      = findViewById(R.id.etFloatSize)
        tvFloatPosition  = findViewById(R.id.tvFloatPosition)
        switchBootStart  = findViewById(R.id.switchBootStart)
        btnGrantOverlay  = findViewById(R.id.btnGrantOverlay)
        btnViewDisclaimer= findViewById(R.id.btnViewDisclaimer)

        // 状态点设为圆形
        vStatusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorOf(R.color.state_idle))
        }

        setupListeners()
    }

    // ── 事件绑定 ──────────────────────────────────────────

    private fun setupListeners() {

        // App选择（互斥）
        rbYilian.setOnClickListener {
            rbBaidu.isChecked = false
            prefs.targetAppPkg   = PrefsHelper.PKG_YILIAN
            prefs.targetAppLabel = getString(R.string.app_label_yilian)
        }
        rbBaidu.setOnClickListener {
            rbYilian.isChecked = false
            prefs.targetAppPkg   = PrefsHelper.PKG_BAIDU
            prefs.targetAppLabel = getString(R.string.app_label_baidu)
        }

        // SSID
        etSsid.addTextChangedListener(makeWatcher {
            if (!isLoadingPrefs) {
                prefs.targetSsid = it.trim().ifEmpty { PrefsHelper.DEFAULT_TARGET_SSID }
            }
        })
        btnSsidPick.setOnClickListener { showSsidPickerDialog() }

        // 自动模式
        switchAutoMode.setOnCheckedChangeListener { _, v ->
            if (!isLoadingPrefs) prefs.autoModeEnabled = v
        }
        etBootDelay.addTextChangedListener(makeWatcher {
            if (!isLoadingPrefs)
                prefs.bootDelaySeconds = it.toIntOrNull() ?: PrefsHelper.DEFAULT_BOOT_DELAY
        })
        etHotspotTimeout.addTextChangedListener(makeWatcher {
            if (!isLoadingPrefs)
                prefs.hotspotTimeoutSeconds =
                    it.toIntOrNull() ?: PrefsHelper.DEFAULT_HOTSPOT_TIMEOUT
        })

        // 悬浮窗
        switchFloatEnabled.setOnCheckedChangeListener { _, v ->
            if (!isLoadingPrefs) {
                prefs.floatWindowEnabled = v
                MainService.instance?.refreshFloatWindow()
            }
        }
        etFloatSize.addTextChangedListener(makeWatcher {
            if (!isLoadingPrefs) {
                prefs.floatWinSize = it.toIntOrNull() ?: PrefsHelper.DEFAULT_FLOAT_WIN_SIZE
                MainService.instance?.refreshFloatWindowSize()
            }
        })

        // 高级
        switchBootStart.setOnCheckedChangeListener { _, v ->
            if (!isLoadingPrefs) prefs.bootAutoStart = v
        }
        

        // 授予悬浮窗权限
        btnGrantOverlay.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }

        // 查看免责声明
        btnViewDisclaimer.setOnClickListener {
            showDisclaimerDialog(isFirstLaunch = false)
        }
    }

    // ── 加载设置到UI ──────────────────────────────────────

    private fun loadPrefsToUI() {
        isLoadingPrefs = true

        detectAndUpdateApps()

        etSsid.setText(prefs.targetSsid)
        switchAutoMode.isChecked = prefs.autoModeEnabled
        etBootDelay.setText(prefs.bootDelaySeconds.toString())
        etHotspotTimeout.setText(prefs.hotspotTimeoutSeconds.toString())
        switchFloatEnabled.isChecked = prefs.floatWindowEnabled
        etFloatSize.setText(prefs.floatWinSize.toString())
        switchBootStart.isChecked = prefs.bootAutoStart
        

        isLoadingPrefs = false
    }

    // ── 互联App检测 ───────────────────────────────────────

    private fun detectAndUpdateApps() {
        val pm = packageManager

        val yilianOk = isAppInstalled(pm, PrefsHelper.PKG_YILIAN)
        val baiduOk  = isAppInstalled(pm, PrefsHelper.PKG_BAIDU)

        tvYilianInstalled.text      = if (yilianOk) "✓" else "✗"
        tvBaiduInstalled.text       = if (baiduOk)  "✓" else "✗"
        tvYilianInstalled.setTextColor(colorOf(if (yilianOk) R.color.text_success else R.color.text_warning))
        tvBaiduInstalled.setTextColor(colorOf(if (baiduOk)  R.color.text_success else R.color.text_warning))

        rbYilian.isEnabled = yilianOk
        rbBaidu.isEnabled  = baiduOk

        tvNoAppWarning.visibility =
            if (!yilianOk && !baiduOk) View.VISIBLE else View.GONE

        // 同步RadioButton到已保存的选择
        when (prefs.targetAppPkg) {
            PrefsHelper.PKG_YILIAN -> { rbYilian.isChecked = true;  rbBaidu.isChecked = false }
            PrefsHelper.PKG_BAIDU  -> { rbYilian.isChecked = false; rbBaidu.isChecked = true  }
            else -> {
                // 未配置时自动选唯一已安装的
                when {
                    yilianOk && !baiduOk -> {
                        rbYilian.isChecked = true
                        prefs.targetAppPkg   = PrefsHelper.PKG_YILIAN
                        prefs.targetAppLabel = getString(R.string.app_label_yilian)
                    }
                    baiduOk && !yilianOk -> {
                        rbBaidu.isChecked = true
                        prefs.targetAppPkg   = PrefsHelper.PKG_BAIDU
                        prefs.targetAppLabel = getString(R.string.app_label_baidu)
                    }
                }
            }
        }
    }

    private fun isAppInstalled(pm: PackageManager, pkg: String): Boolean {
        return try { pm.getPackageInfo(pkg, 0); true } catch (e: Exception) { false }
    }

    // ── SSID选择对话框 ────────────────────────────────────

    private fun showSsidPickerDialog() {
        val wifiMgr = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val ssidSet = mutableSetOf<String>()

        try {
            wifiMgr.configuredNetworks?.forEach { cfg ->
                val s = cfg.SSID?.removeSurrounding("\"") ?: return@forEach
                if (s.isNotEmpty() && s != "<unknown ssid>") ssidSet.add(s)
            }
        } catch (e: Exception) { Log.w(TAG, "获取已保存WiFi失败") }

        try {
            wifiMgr.scanResults?.forEach { r ->
                val s = r.SSID ?: return@forEach
                if (s.isNotEmpty()) ssidSet.add(s)
            }
        } catch (e: Exception) { Log.w(TAG, "获取扫描结果失败") }

        val list = ssidSet.toList().sorted()

        if (list.isEmpty()) {
            Toast.makeText(this, "未找到WiFi列表，请手动输入", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this, R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle(getString(R.string.label_ssid))
            .setItems(list.toTypedArray()) { _, i ->
                etSsid.setText(list[i])
                prefs.targetSsid = list[i]
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // ── 悬浮窗权限检查 ────────────────────────────────────

    private fun checkOverlayPermission() {
        val has = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
        btnGrantOverlay.visibility = if (has) View.GONE else View.VISIBLE
    }

    // ── 位置权限申请 ──────────────────────────────────────

    private fun requestLocationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) return

        AlertDialog.Builder(this, R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle(getString(R.string.perm_location_title))
            .setMessage(getString(R.string.perm_location_msg))
            .setPositiveButton(getString(R.string.btn_ok)) { _, _ ->
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    REQUEST_LOCATION
                )
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // ── 启动Service ───────────────────────────────────────

    private fun startMainServiceIfNeeded() {
        if (MainService.instance != null) return
        val intent = Intent(this, MainService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // ── 状态UI更新 ────────────────────────────────────────

    private fun updateStateUI(state: ConnectionState, mode: RunMode) {
        val stateText  = getStateText(state)
        val stateColor = getStateColor(state)

        tvTopState.text      = stateText
        tvTopMode.text       = if (mode == RunMode.AUTO) getString(R.string.mode_auto)
                               else getString(R.string.mode_manual)
        tvTopState.setTextColor(stateColor)

        tvStateName.text     = stateText
        (vStatusDot.background as? GradientDrawable)?.setColor(stateColor)

        tvStatusHint.text = when (state) {
            ConnectionState.IDLE            -> getString(R.string.msg_tip_longpress)
            ConnectionState.INITIATING      -> getString(R.string.state_initiating)
            ConnectionState.WAITING_HOTSPOT -> getString(R.string.state_waiting)
            ConnectionState.TIMEOUT         -> getString(R.string.msg_auto_timeout)
            ConnectionState.LAUNCHING       -> getString(R.string.state_launching)
            ConnectionState.CONNECTED       -> getString(R.string.state_connected)
        }
    }

    private fun getStateText(state: ConnectionState): String = when (state) {
        ConnectionState.IDLE            -> getString(R.string.state_idle)
        ConnectionState.INITIATING      -> getString(R.string.state_initiating)
        ConnectionState.WAITING_HOTSPOT -> getString(R.string.state_waiting)
        ConnectionState.TIMEOUT         -> getString(R.string.state_timeout)
        ConnectionState.LAUNCHING       -> getString(R.string.state_launching)
        ConnectionState.CONNECTED       -> getString(R.string.state_connected)
    }

    private fun getStateColor(state: ConnectionState): Int = when (state) {
        ConnectionState.IDLE            -> colorOf(R.color.state_idle)
        ConnectionState.INITIATING      -> colorOf(R.color.state_active)
        ConnectionState.WAITING_HOTSPOT -> colorOf(R.color.state_active)
        ConnectionState.TIMEOUT         -> colorOf(R.color.state_timeout)
        ConnectionState.LAUNCHING       -> colorOf(R.color.state_success)
        ConnectionState.CONNECTED       -> colorOf(R.color.state_connected)
    }

    private fun updateFloatPositionText() {
        tvFloatPosition.text = "x=${prefs.floatWinX}  y=${prefs.floatWinY}"
    }

    // ── 免责声明 ──────────────────────────────────────────

    private fun showDisclaimerDialog(isFirstLaunch: Boolean) {
        val builder = AlertDialog.Builder(this, R.style.Theme_AppCompat_Light_Dialog_Alert)
            .setTitle(getString(R.string.disclaimer_title))
            .setMessage(getString(R.string.disclaimer_content))
            .setCancelable(!isFirstLaunch)

        if (isFirstLaunch) {
            builder
                .setPositiveButton(getString(R.string.btn_agree)) { _, _ ->
                    prefs.javaClass.getDeclaredField("prefs")
                        .also { it.isAccessible = true }
                        .get(prefs)
                        .let { (it as android.content.SharedPreferences).edit()
                            .putBoolean(PREF_FIRST_LAUNCH, true).apply() }
                    onDisclaimerAgreed()
                }
                .setNegativeButton(getString(R.string.btn_disagree)) { _, _ -> finish() }
        } else {
            builder.setPositiveButton(getString(R.string.btn_ok), null)
        }

        builder.show()
    }

    private fun onDisclaimerAgreed() {
        requestLocationPermission()
        startMainServiceIfNeeded()
    }

    // ── 工具方法 ──────────────────────────────────────────

    // 兼容API 21的颜色获取
    @Suppress("DEPRECATION")
    private fun colorOf(resId: Int): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            resources.getColor(resId, theme)
        } else {
            resources.getColor(resId)
        }
    }

    // TextWatcher简化封装
    private fun makeWatcher(afterChanged: (String) -> Unit): TextWatcher {
        return object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { afterChanged(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
    }
}