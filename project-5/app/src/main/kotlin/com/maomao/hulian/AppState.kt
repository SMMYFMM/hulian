package com.maomao.hulian

// 连接状态
enum class ConnectionState {
    IDLE,             // 空闲，等待触发
    INITIATING,       // 正在开启BT/WiFi
    WAITING_HOTSPOT,  // 等待目标热点连接
    TIMEOUT,          // 等待超时
    LAUNCHING,        // 正在启动互联App
    CONNECTED         // 互联中
}

// 运行模式
enum class RunMode {
    MANUAL,   // 手动模式：用户点击触发，结束时永久关BT
    AUTO      // 自动模式：开机延时触发，结束时不执行vendor命令
}

// 状态变化事件，状态机内部流转用
sealed class ConnectionEvent {
    object StartRequested : ConnectionEvent()       // 用户点击 / 自动延时到
    object BtWifiReady : ConnectionEvent()          // BT+WiFi 均已开启
    object HotspotConnected : ConnectionEvent()     // 目标SSID已连接
    object AppLaunched : ConnectionEvent()          // 互联App已启动
    object AppExited : ConnectionEvent()            // 互联App退出/切走
    object WaitTimeout : ConnectionEvent()          // 等待热点超时
    object BtTurnedOff : ConnectionEvent()          // BT被关闭
    object WifiDisconnected : ConnectionEvent()     // WiFi从目标SSID断开
    object BtRetryRequested : ConnectionEvent()     // 长按圆圈BT复位重试
    object CancelRequested : ConnectionEvent()      // 取消/重置
}

// 状态变化回调接口，FloatingWindow和MainActivity通过此接口更新UI
interface StateListener {
    fun onStateChanged(newState: ConnectionState, mode: RunMode)
}