package io.legado.app.ui.welcome

import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.postDelayed
import io.legado.app.base.BaseActivity
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Theme
import io.legado.app.data.appDb
import io.legado.app.databinding.ActivityWelcomeBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.applock.AppLockActivity
import io.legado.app.ui.applock.AppLockHelper
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.fullScreen
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.setStatusBarColorAuto
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import io.legado.app.utils.windowSize

open class WelcomeActivity : BaseActivity<ActivityWelcomeBinding>() {

    override val binding by viewBinding(ActivityWelcomeBinding::inflate)
    
    private val appLockLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == AppLockActivity.RESULT_UNLOCKED) {
            // 解锁成功，继续进入主界面
            proceedToMain()
        } else {
            // 解锁失败或取消，关闭应用
            finish()
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.ivBook.setColorFilter(accentColor)
        binding.vwTitleLine.setBackgroundColor(accentColor)
        // 避免从桌面启动程序后，会重新实例化入口类的activity
        if (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT != 0) {
            finish()
        } else {
            binding.root.postDelayed(600) { startMainActivity() }
        }
    }

    override fun setupSystemBar() {
        fullScreen()
        setStatusBarColorAuto(backgroundColor, true, fullScreen)
        upNavigationBarColor()
    }

    override fun upBackgroundImage() {
        if (getPrefBoolean(PreferKey.customWelcome)) {
            kotlin.runCatching {
                when (ThemeConfig.getTheme()) {
                    Theme.Dark -> getPrefString(PreferKey.welcomeImageDark)?.let { path ->
                        val size = windowManager.windowSize
                        BitmapUtils.decodeBitmap(path, size.widthPixels, size.heightPixels).let {
                            binding.tvLegado.visible(AppConfig.welcomeShowTextDark)
                            binding.ivBook.visible(AppConfig.welcomeShowIconDark)
                            binding.tvGzh.visible(AppConfig.welcomeShowTextDark)
                            window.decorView.background = BitmapDrawable(resources, it)
                            return
                        }
                    }

                    else -> getPrefString(PreferKey.welcomeImage)?.let { path ->
                        val size = windowManager.windowSize
                        BitmapUtils.decodeBitmap(path, size.widthPixels, size.heightPixels).let {
                            binding.tvLegado.visible(AppConfig.welcomeShowText)
                            binding.ivBook.visible(AppConfig.welcomeShowIcon)
                            binding.tvGzh.visible(AppConfig.welcomeShowText)
                            window.decorView.background = BitmapDrawable(resources, it)
                            return
                        }
                    }
                }
            }
        }
        super.upBackgroundImage()
    }

    private fun startMainActivity() {
        // 检查应用锁是否启用
        if (AppLockHelper.isAppLockEnabled()) {
            // 启动应用锁验证界面
            val intent = Intent(this, AppLockActivity::class.java)
            appLockLauncher.launch(intent)
        } else {
            // 直接进入主界面
            proceedToMain()
        }
    }
    
    private fun proceedToMain() {
        startActivity<MainActivity>()
        if (getPrefBoolean(PreferKey.defaultToRead) && appDb.bookDao.lastReadBook != null) {
            startActivity<ReadBookActivity>()
        }
        finish()
    }

}

class Launcher1 : WelcomeActivity()
class Launcher2 : WelcomeActivity()
class Launcher3 : WelcomeActivity()
class Launcher4 : WelcomeActivity()
class Launcher5 : WelcomeActivity()
class Launcher6 : WelcomeActivity()