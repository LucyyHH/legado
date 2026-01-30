package io.legado.app.ui.applock

import android.content.Context
import androidx.biometric.BiometricManager
import io.legado.app.help.config.LocalConfig

/**
 * 应用锁辅助类
 */
object AppLockHelper {
    
    /**
     * 检查应用锁是否启用
     */
    fun isAppLockEnabled(): Boolean {
        return LocalConfig.appLockEnabled && !LocalConfig.appLockPin.isNullOrEmpty()
    }
    
    /**
     * 检查设备是否支持生物识别（指纹或面容）
     */
    fun isBiometricAvailable(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        val canAuthStrong = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        val canAuthWeak = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        return canAuthStrong == BiometricManager.BIOMETRIC_SUCCESS ||
               canAuthWeak == BiometricManager.BIOMETRIC_SUCCESS
    }
}
