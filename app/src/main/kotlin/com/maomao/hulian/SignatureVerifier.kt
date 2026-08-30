package com.maomao.hulian

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import java.security.MessageDigest

/**
 * APK 签名校验
 *
 * 运行时检测当前 APK 的签名证书 SHA256 是否与预期一致，
 * 防止二次打包/篡改。若签名不匹配则拒绝运行核心功能。
 */
object SignatureVerifier {

    private const val TAG = "SignatureVerifier"

    // hulian.keystore 证书 SHA256（固定值，勿改）
    private const val EXPECTED_SHA256_HEX =
        "9205074F82F10BA537A6416932E1F3D4987A01FA7C9C2987016E46DEBB8631AD"

    @Volatile
    private var verified = false

    fun isVerified(): Boolean = verified

    /**
     * 校验当前 APK 签名。
     * @return true 签名匹配，false 签名不匹配（被篡改）
     */
    fun verify(context: Context): Boolean {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNATURES
            )
            val signatures: Array<Signature> = packageInfo.signatures ?: emptyArray()
            if (signatures.isEmpty()) {
                FileLogger.e(TAG, "未获取到签名，校验失败")
                verified = false
                return false
            }

            val md = MessageDigest.getInstance("SHA-256")
            for (sig in signatures) {
                val hash = md.digest(sig.toByteArray())
                val hashHex = hash.toHex()
                if (hashHex.equals(EXPECTED_SHA256_HEX, ignoreCase = true)) {
                    verified = true
                    FileLogger.i(TAG, "签名校验通过")
                    return true
                }
            }

            // 打印实际签名便于调试
            val actualHex = signatures.joinToString(", ") { sig ->
                md.digest(sig.toByteArray()).toHex()
            }
            FileLogger.e(TAG, "签名校验失败！实际: $actualHex")
            verified = false
            false
        } catch (e: Exception) {
            FileLogger.e(TAG, "签名校验异常: ${e.message}", e)
            verified = false
            false
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { b -> "%02X".format(b) }
}
