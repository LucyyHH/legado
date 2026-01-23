package io.legado.app.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import io.legado.app.constant.AppLog
import splitties.init.appCtx

/**
 * 安全存储工具类
 * 用于存储敏感信息（如密码、token等）
 * 
 * - API 23+ (Android 6.0+): 使用 EncryptedSharedPreferences 加密存储
 * - API 23 以下: 降级使用普通 SharedPreferences（设备较旧，加密库不支持）
 */
object SecurePreferences {
    private const val SECURE_PREFS_NAME = "secure_prefs"
    private const val TAG = "SecurePreferences"
    
    private val securePrefs: SharedPreferences by lazy {
        createSecurePreferences(appCtx)
    }
    
    private fun createSecurePreferences(context: Context): SharedPreferences {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                
                EncryptedSharedPreferences.create(
                    context,
                    SECURE_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                // 如果加密存储创建失败，降级使用普通存储
                AppLog.put("创建加密存储失败，降级使用普通存储: ${e.message}", e)
                context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
            }
        } else {
            // API 23 以下不支持 EncryptedSharedPreferences
            context.getSharedPreferences(SECURE_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }
    
    /**
     * 存储字符串
     */
    fun putString(key: String, value: String?) {
        securePrefs.edit().putString(key, value).apply()
    }
    
    /**
     * 获取字符串
     */
    fun getString(key: String, defValue: String? = null): String? {
        return securePrefs.getString(key, defValue)
    }
    
    /**
     * 存储长整型
     */
    fun putLong(key: String, value: Long) {
        securePrefs.edit().putLong(key, value).apply()
    }
    
    /**
     * 获取长整型
     */
    fun getLong(key: String, defValue: Long = 0L): Long {
        return securePrefs.getLong(key, defValue)
    }
    
    /**
     * 删除指定键
     */
    fun remove(key: String) {
        securePrefs.edit().remove(key).apply()
    }
    
    /**
     * 清除所有数据
     */
    fun clear() {
        securePrefs.edit().clear().apply()
    }
    
    /**
     * 检查是否包含指定键
     */
    fun contains(key: String): Boolean {
        return securePrefs.contains(key)
    }
    
    /**
     * 从普通 SharedPreferences 迁移敏感数据到加密存储
     * @param normalPrefs 普通 SharedPreferences
     * @param keys 需要迁移的键列表
     */
    fun migrateFromNormalPrefs(normalPrefs: SharedPreferences, vararg keys: String) {
        var migrated = false
        keys.forEach { key ->
            if (normalPrefs.contains(key) && !securePrefs.contains(key)) {
                val value = normalPrefs.getString(key, null)
                if (!value.isNullOrEmpty()) {
                    putString(key, value)
                    migrated = true
                }
            }
        }
        
        // 迁移完成后，从普通存储中删除敏感数据
        if (migrated) {
            val editor = normalPrefs.edit()
            keys.forEach { key ->
                if (normalPrefs.contains(key)) {
                    editor.remove(key)
                }
            }
            editor.apply()
            AppLog.put("敏感数据已迁移到加密存储")
        }
    }
}
