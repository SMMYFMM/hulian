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

    private val density = context.resources.displayMetrics.density
    private fun dpToPx(dp: Int): Int = (dp * density + 0.5f).toInt()

    private var rootView: View? = null
    private var circleBg: View? = null
    private var statusText: TextView? = null
    private var isShowing = false

    var onSingleClick: (() -> Unit)? = null
    var onLongClick: (() -> Unit)? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var isDragging = false
    private val dragThreshold = 10f

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val sizePx = dpToPx(prefs.floatWinSize)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            sizePx, sizePx,
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

    fun show() {
        if (isShowing) return
        if (!prefs.floatWindowEnabled) {
            Log.d(TAG, "悬浮窗已在设置中关闭，跳过显示")
            FileLogger.d(TAG, "悬浮窗已关闭，跳过显示")
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
            FileLogger.i(TAG, "悬浮窗已显示, 大小=${prefs.floatWinSize}dp")
        } catch (e: Exception) {
            FileLogger.e(TAG, "悬浮窗显示失败", e)
        }
    }

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

    fun updateState(state: ConnectionState) {
        FileLogger.d(TAG, "悬浮窗状态更新: $state")
        when (state) {
            ConnectionState.IDLE -> {
                show()
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
                hide()
            }
        }
    }

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
                    false
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
                        prefs.floatWinX = params.x
                        prefs.floatWinY = params.y
                        Log.d(TAG, "悬浮窗位置已保存: (${params.x}, ${params.y})")
                    }
                    isDragging
                }
                else -> false
            }
        }

        view.setOnClickListener {
            if (!isDragging) {
                Log.d(TAG, "悬浮窗单击")
                onSingleClick?.invoke()
            }
        }

        view.setOnLongClickListener {
            Log.d(TAG, "悬浮窗长按")
            onLongClick?.invoke()
            true
        }
    }

    fun updateSize(sizeDp: Int) {
        val view = rootView ?: return
        val params = view.layoutParams as WindowManager.LayoutParams
        val sizePx = dpToPx(sizeDp)
        params.width = sizePx
        params.height = sizePx
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "更新大小失败: ${e.message}")
        }
    }

    fun release() {
        hide()
        onSingleClick = null
        onLongClick = null
    }

    companion object {
        const val COLOR_IDLE    = 0xCC7B61FF.toInt()
        const val COLOR_ACTIVE  = 0xCC4FC3F7.toInt()
        const val COLOR_TIMEOUT = 0xCCEF5350.toInt()
        const val COLOR_SUCCESS = 0xCC66BB6A.toInt()
    }
}
