package com.maomao.hulian

import android.app.Application
import com.umeng.commonsdk.UMConfigure

/**
 * Application 类 —— 负责签名校验 + 友盟SDK初始化
 *
 * 隐私合规说明：
 * preInit 仅预加载配置，不采集数据；
 * init 在用户同意免责声明后执行正式初始化，开始统计数据。
 */
class HulianApplication : Application() {

    companion object {
        private const val UMENG_APP_KEY = "6a938d78e87a8d65f0cd2e06"
        private const val UMENG_CHANNEL = "default"

        @Volatile
        private var initialized = false

        /** 签名校验是否通过 */
        fun isSignatureValid(): Boolean = SignatureVerifier.isVerified()
    }

    override fun onCreate() {
        super.onCreate()

        // 签名校验（防二次打包）
        val sigOk = SignatureVerifier.verify(this)
        if (!sigOk) {
            FileLogger.e("HulianApplication", "签名校验失败！APK可能被篡改，核心功能将被禁用")
        }

        // 友盟预初始化（不采集数据，仅准备配置）
        UMConfigure.preInit(this, UMENG_APP_KEY, UMENG_CHANNEL)
        FileLogger.i("HulianApplication", "友盟SDK preInit 完成")
    }

    /**
     * 正式初始化友盟SDK，开始采集统计数据。
     * 由 MainActivity 在用户同意免责声明后调用。
     */
    fun initUmengIfNeeded() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            UMConfigure.init(
                this,
                UMENG_APP_KEY,
                UMENG_CHANNEL,
                UMConfigure.DEVICE_TYPE_PHONE,
                null
            )
            initialized = true
            FileLogger.i("HulianApplication", "友盟SDK init 完成，开始统计数据采集")
        }
    }
}
