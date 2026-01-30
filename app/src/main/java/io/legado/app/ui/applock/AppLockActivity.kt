package io.legado.app.ui.applock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityAppLockBinding
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 应用锁验证界面
 */
class AppLockActivity : BaseActivity<ActivityAppLockBinding>(
    fullScreen = true,
    imageBg = false
) {

    override val binding by viewBinding(ActivityAppLockBinding::inflate)
    
    private var enteredPin = StringBuilder()
    private val pinLength = 6
    private val dots by lazy {
        listOf(
            binding.dot1,
            binding.dot2,
            binding.dot3,
            binding.dot4,
            binding.dot5,
            binding.dot6
        )
    }
    
    private var isSettingMode = false
    private var isChangePinMode = false  // 修改 PIN 模式：先验证再设置
    private var isVerifyingOldPin = false  // 是否正在验证旧 PIN
    private var firstPin: String? = null
    private var failedAttempts = 0
    private val maxFailedAttempts = 5
    
    companion object {
        private const val EXTRA_SET_PIN = "set_pin"
        private const val EXTRA_CHANGE_PIN = "change_pin"
        const val RESULT_UNLOCKED = 100
        const val RESULT_PIN_SET = 101
        
        fun createSetPinIntent(context: Context): Intent {
            return Intent(context, AppLockActivity::class.java).apply {
                putExtra(EXTRA_SET_PIN, true)
            }
        }
        
        fun createChangePinIntent(context: Context): Intent {
            return Intent(context, AppLockActivity::class.java).apply {
                putExtra(EXTRA_CHANGE_PIN, true)
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        // 防止截屏
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        
        isSettingMode = intent.getBooleanExtra(EXTRA_SET_PIN, false)
        isChangePinMode = intent.getBooleanExtra(EXTRA_CHANGE_PIN, false)
        
        // 修改 PIN 模式下，先验证旧 PIN
        if (isChangePinMode) {
            isVerifyingOldPin = true
        }
        
        setupUI()
        setupKeypad()
        
        // 只有验证模式（非设置、非修改）才显示指纹
        if (!isSettingMode && !isChangePinMode && LocalConfig.useBiometric && isBiometricAvailable()) {
            binding.btnBiometric.visibility = View.VISIBLE
            // 隐藏 PIN 输入界面，等指纹验证失败后再显示
            binding.llPinDots.visibility = View.INVISIBLE
            binding.keypad.visibility = View.INVISIBLE
            binding.tvTitle.visibility = View.INVISIBLE
            // 自动弹出指纹验证
            binding.root.post { showBiometricPrompt() }
        } else {
            binding.btnBiometric.visibility = View.GONE
        }
    }
    
    private fun showPinUI() {
        binding.llPinDots.visibility = View.VISIBLE
        binding.keypad.visibility = View.VISIBLE
        binding.tvTitle.visibility = View.VISIBLE
    }
    
    private fun setupUI() {
        binding.ivLock.setColorFilter(accentColor)
        
        when {
            isSettingMode -> {
                binding.tvTitle.setText(R.string.set_pin)
                binding.btnBiometric.visibility = View.GONE
            }
            isChangePinMode && isVerifyingOldPin -> {
                binding.tvTitle.setText(R.string.verify_old_pin)
                binding.btnBiometric.visibility = View.GONE
            }
            else -> {
                binding.tvTitle.setText(R.string.enter_pin)
            }
        }
        
        // 根据已设置的PIN长度调整显示的圆点数量
        val savedPin = LocalConfig.appLockPin
        val actualPinLength = if (isSettingMode || (isChangePinMode && !isVerifyingOldPin)) {
            pinLength
        } else {
            savedPin?.length ?: pinLength
        }
        updateDotsVisibility(actualPinLength)
    }
    
    private fun updateDotsVisibility(length: Int) {
        dots.forEachIndexed { index, dot ->
            dot.visibility = if (index < length) View.VISIBLE else View.GONE
        }
    }
    
    private fun setupKeypad() {
        val numberButtons = listOf(
            binding.btn0, binding.btn1, binding.btn2, binding.btn3, binding.btn4,
            binding.btn5, binding.btn6, binding.btn7, binding.btn8, binding.btn9
        )
        
        numberButtons.forEachIndexed { index, button ->
            button.setOnClickListener { onNumberClick(index) }
        }
        
        binding.btnDelete.setOnClickListener { onDeleteClick() }
        binding.btnBiometric.setOnClickListener { showBiometricPrompt() }
    }
    
    private fun onNumberClick(number: Int) {
        val targetLength = when {
            isSettingMode -> pinLength
            isChangePinMode && !isVerifyingOldPin -> pinLength  // 设置新 PIN
            else -> LocalConfig.appLockPin?.length ?: pinLength  // 验证模式使用已保存的 PIN 长度
        }
        
        if (enteredPin.length < targetLength) {
            enteredPin.append(number)
            updateDots()
            
            if (enteredPin.length == targetLength) {
                onPinComplete()
            }
        }
    }
    
    private fun onDeleteClick() {
        if (enteredPin.isNotEmpty()) {
            enteredPin.deleteCharAt(enteredPin.length - 1)
            updateDots()
        }
    }
    
    private fun updateDots() {
        dots.forEachIndexed { index, dot ->
            if (dot.visibility == View.VISIBLE) {
                dot.setBackgroundResource(
                    if (index < enteredPin.length) R.drawable.pin_dot_filled
                    else R.drawable.pin_dot_empty
                )
            }
        }
    }
    
    private fun onPinComplete() {
        when {
            isSettingMode -> handleSetPin()
            isChangePinMode && isVerifyingOldPin -> handleVerifyOldPin()
            isChangePinMode && !isVerifyingOldPin -> handleSetPin()
            else -> handleVerifyPin()
        }
    }
    
    private fun handleVerifyOldPin() {
        val savedPin = LocalConfig.appLockPin
        if (enteredPin.toString() == savedPin) {
            // 验证成功，进入设置新 PIN 模式
            isVerifyingOldPin = false
            enteredPin.clear()
            updateDotsVisibility(pinLength)
            updateDots()
            binding.tvTitle.setText(R.string.set_new_pin)
            binding.tvMessage.visibility = View.GONE
            failedAttempts = 0
        } else {
            // 验证失败
            failedAttempts++
            if (failedAttempts >= maxFailedAttempts) {
                binding.tvMessage.setText(R.string.too_many_attempts)
                binding.tvMessage.visibility = View.VISIBLE
                disableKeypad(true)
                binding.root.postDelayed({
                    disableKeypad(false)
                    failedAttempts = 0
                    binding.tvMessage.visibility = View.GONE
                }, 30000)
            } else {
                binding.tvMessage.text = getString(R.string.wrong_pin_attempts, maxFailedAttempts - failedAttempts)
                binding.tvMessage.visibility = View.VISIBLE
            }
            enteredPin.clear()
            updateDots()
            shakeDotsAnimation()
        }
    }
    
    private fun handleSetPin() {
        if (firstPin == null) {
            // 第一次输入
            firstPin = enteredPin.toString()
            enteredPin.clear()
            updateDots()
            binding.tvTitle.setText(R.string.confirm_pin)
            binding.tvMessage.visibility = View.GONE
        } else {
            // 确认输入
            if (enteredPin.toString() == firstPin) {
                // PIN 设置成功
                LocalConfig.appLockPin = firstPin
                LocalConfig.appLockEnabled = true
                toastOnUi(R.string.pin_set_success)
                setResult(RESULT_PIN_SET)
                finish()
            } else {
                // PIN 不匹配
                binding.tvMessage.setText(R.string.pin_not_match)
                binding.tvMessage.visibility = View.VISIBLE
                firstPin = null
                enteredPin.clear()
                updateDots()
                binding.tvTitle.setText(R.string.set_pin)
                shakeDotsAnimation()
            }
        }
    }
    
    private fun handleVerifyPin() {
        val savedPin = LocalConfig.appLockPin
        if (enteredPin.toString() == savedPin) {
            // 验证成功
            setResult(RESULT_UNLOCKED)
            finish()
        } else {
            // 验证失败
            failedAttempts++
            if (failedAttempts >= maxFailedAttempts) {
                binding.tvMessage.setText(R.string.too_many_attempts)
                binding.tvMessage.visibility = View.VISIBLE
                // 禁用输入一段时间
                disableKeypad(true)
                binding.root.postDelayed({
                    disableKeypad(false)
                    failedAttempts = 0
                    binding.tvMessage.visibility = View.GONE
                }, 30000) // 30秒后解锁
            } else {
                binding.tvMessage.text = getString(R.string.wrong_pin_attempts, maxFailedAttempts - failedAttempts)
                binding.tvMessage.visibility = View.VISIBLE
            }
            enteredPin.clear()
            updateDots()
            shakeDotsAnimation()
        }
    }
    
    private fun disableKeypad(disable: Boolean) {
        val alpha = if (disable) 0.5f else 1f
        binding.keypad.alpha = alpha
        binding.keypad.isEnabled = !disable
        // 遍历键盘的所有行（LinearLayout）
        for (i in 0 until binding.keypad.childCount) {
            val row = binding.keypad.getChildAt(i)
            if (row is android.view.ViewGroup) {
                // 遍历行内的所有按钮
                for (j in 0 until row.childCount) {
                    row.getChildAt(j).isEnabled = !disable
                }
            } else {
                row.isEnabled = !disable
            }
        }
    }
    
    private fun shakeDotsAnimation() {
        binding.llPinDots.animate()
            .translationX(20f)
            .setDuration(50)
            .withEndAction {
                binding.llPinDots.animate()
                    .translationX(-20f)
                    .setDuration(50)
                    .withEndAction {
                        binding.llPinDots.animate()
                            .translationX(10f)
                            .setDuration(50)
                            .withEndAction {
                                binding.llPinDots.animate()
                                    .translationX(0f)
                                    .setDuration(50)
                                    .start()
                            }
                            .start()
                    }
                    .start()
            }
            .start()
    }
    
    private fun isBiometricAvailable(): Boolean {
        val biometricManager = BiometricManager.from(this)
        // 支持强生物识别（指纹、面容等）
        val canAuthStrong = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        val canAuthWeak = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        return canAuthStrong == BiometricManager.BIOMETRIC_SUCCESS ||
               canAuthWeak == BiometricManager.BIOMETRIC_SUCCESS
    }
    
    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    setResult(RESULT_UNLOCKED)
                    finish()
                }
                
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // 显示 PIN 输入界面
                    showPinUI()
                    // 用户取消或其他错误，继续使用PIN
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        toastOnUi(errString.toString())
                    }
                }
                
                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // 指纹不匹配时不立即显示 PIN，让用户可以重试
                }
            })
        
        // 使用 BIOMETRIC_WEAK 以支持更多类型的生物识别（包括面容）
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_login_title))
            .setSubtitle(getString(R.string.biometric_login_subtitle))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .setNegativeButtonText(getString(R.string.use_pin))
            .setConfirmationRequired(false)  // 面容识别成功后直接进入，无需点击确认
            .build()
        
        biometricPrompt.authenticate(promptInfo)
    }
    
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isSettingMode || isChangePinMode) {
            // 设置模式和修改 PIN 模式可以取消
            setResult(RESULT_CANCELED)
            @Suppress("DEPRECATION")
            super.onBackPressed()
        } else {
            // 验证模式不允许返回，移到后台
            moveTaskToBack(true)
        }
    }
}
