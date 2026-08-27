package com.maomao.hulian

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

class FloatingWindow(
    private val context: Context,
    private val prefs: PrefsHelper
) {
    private val TAG = "FloatingWindow"

    private val windowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var rootView: View? = null
    private var circleBg: View? = null
    private var statusText: TextView? = null
    private var isShowing = false

    // 外部注入回调（由MainService设置）
    var onSingleClick: (() -> Unit)? = null
    var onLongClick: (() -> Unit)? = null

    // 拖动相关
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private val dragThreshold = 10f

    // ── WindowManager参数 ─────────────────────────────────

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val size = prefs.floatWinSize
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            size, size,
            prefs.floatWinX,
            prefs.floatWinY,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).also {
            it.gravity = Gravity.TOP or Gravity.START
        }
    }

    // ── 显示悬浮窗 ────────────────────────────────────────

    fun show() {
        if (isShowing) return
        if (!prefs.floatWindowEnabled) {
            Log.d(TAG, "悬浮窗已在设置中关闭，跳过显示")
            return
        }

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.layout_floating_window, null)
        rootView = view
        circleBg = view.findViewById(R.id.floatCircleBg)
        statusText = view.findViewById(R.id.floatStatusText)

        applyCircleShape(COLOR_IDLE)
        setupTouchListener(view)

        try {
            windowManager.addView(view, buildLayoutParams())
            isShowing = true
            Log.d(TAG, "悬浮窗已显示")
        } catch (e: Exception) {
            Log.e(TAG, "悬浮窗显示失败: ${e.message}")
        }
    }

    // ── 隐藏悬浮窗 ────────────────────────────────────────

    fun hide() {
        if (!isShowing) return
        try {
            rootView?.let { windowManager.removeView(it) }
            isShowing = false
            rootView = null
            circleBg = null
            statusText = null
            Log.d(TAG, "悬浮窗已隐藏")
        } catch (e: Exception) {
            Log.e(TAG, "悬浮窗隐藏失败: ${e.message}")
        }
    }

    // ── 根据状态更新外观 ──────────────────────────────────

    fun updateState(state: ConnectionState) {
        when (state) {
            ConnectionState.IDLE -> {
                show() // IDLE时确保显示
                applyCircleShape(COLOR_IDLE)
                setStatusText("")
            }
            ConnectionState.INITIATING -> {
                show()
                applyCircleShape(COLOR_ACTIVE)
                setStatusText("")
            }
            ConnectionState.WAITING_HOTSPOT -> {
                show()
                applyCircleShape(COLOR_ACTIVE)
                setStatusText("")
            }
            ConnectionState.TIMEOUT -> {
                show()
                applyCircleShape(COLOR_TIMEOUT)
                setStatusText("超时")
            }
            ConnectionState.LAUNCHING -> {
                show()
                applyCircleShape(COLOR_SUCCESS)
                setStatusText("")
            }
            ConnectionState.CONNECTED -> {
                // 互联中隐藏，避免挡住互联画面
                hide()
            }
        }
    }

    // ── 圆形外观设置 ──────────────────────────────────────

    private fun applyCircleShape(color: Int) {
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        circleBg?.background = drawable
    }

    private fun setStatusText(text: String) {
        statusText?.text = text
    }

    // ── 拖动+点击手势处理 ─────────────────────────────────

    private fun setupTouchListener(view: View) {
        view.setOnTouchListener { _, event ->
            val params = view.layoutParams as WindowManager.LayoutParams
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    false // 让长按事件也能触发
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!isDragging &&
                        (Math.abs(dx) > dragThreshold || Math.abs(dy) > dragThreshold)
                    ) {
                        isDragging = true
                        view.cancelLongPress()
                    }
                    if (isDragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (e: Exception) {
                            Log.w(TAG, "updateViewLayout失败: ${e.message}")
                        }
                    }
                    isDragging
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        // 保存新位置
                        prefs.floatWinX = params.x
                        prefs.floatWinY = params.y
                        Log.d(TAG, "悬浮窗位置已保存: (${params.x}, ${params.y})")
                    }
                    isDragging
                }
                else -> false
            }
        }

        // 单击
        view.setOnClickListener {
            if (!isDragging) {
                Log.d(TAG, "悬浮窗单击")
                onSingleClick?.invoke()
            }
        }

        // 长按
        view.setOnLongClickListener {
            Log.d(TAG, "悬浮窗长按")
            onLongClick?.invoke()
            true
        }
    }

    // ── 更新悬浮窗大小（设置改变时调用）─────────────────────

    fun updateSize(sizeDp: Int) {
        val view = rootView ?: return
        val params = view.layoutParams as WindowManager.LayoutParams
        params.width = sizeDp
        params.height = sizeDp
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "更新大小失败: ${e.message}")
        }
    }

    // ── 释放资源 ──────────────────────────────────────────

    fun release() {
        hide()
        onSingleClick = null
        onLongClick = null
    }

    // ── 颜色常量 ──────────────────────────────────────────

    companion object {
        const val COLOR_IDLE    = 0xCC7B61FF.toInt()  // 紫色半透明
        const val COLOR_ACTIVE  = 0xCC4FC3F7.toInt()  // 蓝色
        const val COLOR_TIMEOUT = 0xCCEF5350.toInt()  // 红色
        const val COLOR_SUCCESS = 0xCC66BB6A.toInt()  // 绿色
    }
}