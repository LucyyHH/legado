package io.legado.app.ui.config

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuProvider
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.ReaderServerSync
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.ImportOldData
import io.legado.app.help.storage.Restore
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.about.AppLogDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.FileDoc
import io.legado.app.utils.applyTint
import io.legado.app.utils.checkWrite
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.SecurePreferences
import io.legado.app.utils.removePref
import io.legado.app.utils.launch
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.toEditable
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

class BackupConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener,
    MenuProvider {

    private val viewModel by activityViewModels<ConfigViewModel>()
    private val waitDialog by lazy { WaitDialog(requireContext()) }
    private var backupJob: Job? = null
    private var restoreJob: Job? = null

    private val selectBackupPath = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.isContentScheme()) {
                AppConfig.backupPath = uri.toString()
            } else {
                AppConfig.backupPath = uri.path
            }
        }
    }
    private val backupDir = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let { uri ->
            if (uri.isContentScheme()) {
                AppConfig.backupPath = uri.toString()
                backup(uri.toString())
            } else {
                uri.path?.let { path ->
                    AppConfig.backupPath = path
                    backup(path)
                }
            }
        }
    }
    private val restoreDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            waitDialog.setText("恢复中…")
            waitDialog.show()
            val task = Coroutine.async {
                Restore.restore(appCtx, uri)
            }.onFinally {
                waitDialog.dismiss()
            }
            waitDialog.setOnCancelListener {
                task.cancel()
            }
        }
    }
    private val restoreOld = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            ImportOldData.importUri(appCtx, uri)
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_config_backup)
        
        // Reader Server 配置
        findPreference<EditTextPreference>(PreferKey.readerServerPassword)?.let {
            it.setOnBindEditTextListener { editText ->
                editText.inputType =
                    InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT
                editText.setSelection(editText.text.length)
            }
        }
        upPreferenceSummary(PreferKey.readerServerUrl, getPrefString(PreferKey.readerServerUrl))
        upPreferenceSummary(PreferKey.readerServerUsername, getPrefString(PreferKey.readerServerUsername))
        // 从加密存储读取密码摘要
        upPreferenceSummary(PreferKey.readerServerPassword, SecurePreferences.getString(PreferKey.readerServerPassword))
        
        // WebDAV 配置
        findPreference<EditTextPreference>(PreferKey.webDavPassword)?.let {
            it.setOnBindEditTextListener { editText ->
                editText.inputType =
                    InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_CLASS_TEXT
                editText.setSelection(editText.text.length)
            }
        }
        findPreference<EditTextPreference>(PreferKey.webDavDir)?.let {
            it.setOnBindEditTextListener { editText ->
                editText.text = AppConfig.webDavDir?.toEditable()
                editText.setSelection(editText.text.length)
            }
        }
        findPreference<EditTextPreference>(PreferKey.webDavDeviceName)?.let {
            it.setOnBindEditTextListener { editText ->
                editText.text = AppConfig.webDavDeviceName?.toEditable()
                editText.setSelection(editText.text.length)
            }
        }
        upPreferenceSummary(PreferKey.webDavUrl, getPrefString(PreferKey.webDavUrl))
        upPreferenceSummary(PreferKey.webDavAccount, getPrefString(PreferKey.webDavAccount))
        upPreferenceSummary(PreferKey.webDavPassword, getPrefString(PreferKey.webDavPassword))
        upPreferenceSummary(PreferKey.webDavDir, AppConfig.webDavDir)
        upPreferenceSummary(PreferKey.webDavDeviceName, AppConfig.webDavDeviceName)
        upPreferenceSummary(PreferKey.backupPath, getPrefString(PreferKey.backupPath))
        findPreference<io.legado.app.lib.prefs.Preference>("web_dav_restore")
            ?.onLongClick {
                restoreFromLocal()
                true
            }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.backup_restore)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        listView.setEdgeEffectColor(primaryColor)
        activity?.addMenuProvider(this, viewLifecycleOwner)
        if (!LocalConfig.backupHelpVersionIsLast) {
            showHelp("webDavHelp")
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.backup_restore, menu)
        menu.applyTint(requireContext())
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.menu_help -> {
                showHelp("webDavHelp")
                return true
            }

            R.id.menu_log -> showDialogFragment<AppLogDialog>()
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.backupPath -> upPreferenceSummary(key, getPrefString(key))
            PreferKey.webDavUrl,
            PreferKey.webDavAccount,
            PreferKey.webDavPassword,
            PreferKey.webDavDir -> listView.post {
                upPreferenceSummary(key, appCtx.getPrefString(key))
                viewModel.upWebDavConfig()
            }

            PreferKey.webDavDeviceName -> upPreferenceSummary(key, getPrefString(key))
            
            // Reader Server 配置变更
            PreferKey.readerServerUrl,
            PreferKey.readerServerUsername -> listView.post {
                upPreferenceSummary(key, appCtx.getPrefString(key))
                viewModel.upReaderServerConfig()
            }
            
            // Reader Server 密码变更 - 需要存储到加密存储
            PreferKey.readerServerPassword -> listView.post {
                // 从普通 SharedPreferences 获取密码
                val password = appCtx.getPrefString(key)
                if (!password.isNullOrEmpty()) {
                    // 存储到加密存储
                    SecurePreferences.putString(key, password)
                    // 从普通存储中删除
                    appCtx.removePref(key)
                }
                // 更新摘要显示（从加密存储读取）
                upPreferenceSummary(key, SecurePreferences.getString(key))
                viewModel.upReaderServerConfig()
            }
        }
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String?) {
        val preference = findPreference<Preference>(preferenceKey) ?: return
        when (preferenceKey) {
            // Reader Server
            PreferKey.readerServerUrl ->
                if (value.isNullOrBlank()) {
                    preference.summary = getString(R.string.reader_server_url_s)
                } else {
                    preference.summary = value
                }

            PreferKey.readerServerUsername ->
                if (value.isNullOrBlank()) {
                    preference.summary = getString(R.string.reader_server_username_s)
                } else {
                    preference.summary = value
                }

            PreferKey.readerServerPassword ->
                if (value.isNullOrEmpty()) {
                    preference.summary = getString(R.string.reader_server_password_s)
                } else {
                    preference.summary = "*".repeat(value.length)
                }

            // WebDAV
            PreferKey.webDavUrl ->
                if (value.isNullOrBlank()) {
                    preference.summary = getString(R.string.web_dav_url_s)
                } else {
                    preference.summary = value
                }

            PreferKey.webDavAccount ->
                if (value.isNullOrBlank()) {
                    preference.summary = getString(R.string.web_dav_account_s)
                } else {
                    preference.summary = value
                }

            PreferKey.webDavPassword ->
                if (value.isNullOrEmpty()) {
                    preference.summary = getString(R.string.web_dav_pw_s)
                } else {
                    preference.summary = "*".repeat(value.length)
                }

            PreferKey.webDavDir -> preference.summary = when (value) {
                null -> "legado"
                else -> value
            }

            else -> {
                if (preference is ListPreference) {
                    val index = preference.findIndexOfValue(value)
                    // Set the summary to reflect the new value.
                    preference.summary = if (index >= 0) preference.entries[index] else null
                } else {
                    preference.summary = value
                }
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            PreferKey.backupPath -> selectBackupPath.launch()
            PreferKey.restoreIgnore -> backupIgnore()
            "web_dav_backup" -> backup()
            "web_dav_restore" -> restore()
            "import_old" -> restoreOld.launch()
            "reader_server_test" -> testReaderServerConnection()
            "reader_server_sync_now" -> syncReaderServerNow()
        }
        return super.onPreferenceTreeClick(preference)
    }

    /**
     * 备份忽略设置
     */
    private fun backupIgnore() {
        val checkedItems = BooleanArray(BackupConfig.ignoreKeys.size) {
            BackupConfig.ignoreConfig[BackupConfig.ignoreKeys[it]] ?: false
        }
        alert(R.string.restore_ignore) {
            multiChoiceItems(BackupConfig.ignoreTitle, checkedItems) { _, which, isChecked ->
                BackupConfig.ignoreConfig[BackupConfig.ignoreKeys[which]] = isChecked
            }
            onDismiss {
                BackupConfig.saveIgnoreConfig()
            }
        }
    }


    fun backup() {
        val backupPath = AppConfig.backupPath
        if (backupPath.isNullOrEmpty()) {
            backupDir.launch()
        } else {
            if (backupPath.isContentScheme()) {
                lifecycleScope.launch {
                    val canWrite = withContext(IO) {
                        FileDoc.fromDir(backupPath).checkWrite()
                    }
                    if (canWrite) {
                        backup(backupPath)
                    } else {
                        backupDir.launch()
                    }
                }
            } else {
                backupUsePermission(backupPath)
            }
        }
    }

    private fun backup(backupPath: String) {
        waitDialog.setText("备份中…")
        waitDialog.setOnCancelListener {
            backupJob?.cancel()
        }
        waitDialog.show()
        backupJob?.cancel()
        backupJob = lifecycleScope.launch {
            try {
                Backup.backupLocked(requireContext(), backupPath)
                appCtx.toastOnUi(R.string.backup_success)
            } catch (e: Throwable) {
                ensureActive()
                AppLog.put("备份出错\n${e.localizedMessage}", e)
                appCtx.toastOnUi(
                    appCtx.getString(
                        R.string.backup_fail,
                        e.localizedMessage
                    )
                )
            } finally {
                ensureActive()
                waitDialog.dismiss()
            }
        }
    }

    private fun backupUsePermission(path: String) {
        PermissionsCompat.Builder()
            .addPermissions(*Permissions.Group.STORAGE)
            .rationale(R.string.tip_perm_request_storage)
            .onGranted {
                backup(path)
            }
            .request()
    }

    fun restore() {
        waitDialog.setText(R.string.loading)
        waitDialog.setOnCancelListener {
            restoreJob?.cancel()
        }
        waitDialog.show()
        Coroutine.async {
            restoreJob = coroutineContext[Job]
            showRestoreDialog(requireContext())
        }.onError {
            AppLog.put("恢复备份出错WebDavError\n${it.localizedMessage}", it)
            if (context == null) {
                return@onError
            }
            alert {
                setTitle(R.string.restore)
                setMessage("WebDavError\n${it.localizedMessage}\n将从本地备份恢复。")
                okButton {
                    restoreFromLocal()
                }
                cancelButton()
            }
        }.onFinally {
            waitDialog.dismiss()
        }
    }

    private suspend fun showRestoreDialog(context: Context) {
        val names = withContext(IO) { AppWebDav.getBackupNames() }
        if (AppWebDav.isJianGuoYun && names.size > 700) {
            context.toastOnUi("由于坚果云限制列出文件数量，部分备份可能未显示，请及时清理旧备份")
        }
        if (names.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            withContext(Main) {
                context.selector(
                    title = context.getString(R.string.select_restore_file),
                    items = names
                ) { _, index ->
                    if (index in 0 until names.size) {
                        listView.post {
                            restoreWebDav(names[index])
                        }
                    }
                }
            }
        } else {
            throw NoStackTraceException("Web dav no back up file")
        }
    }

    private fun restoreWebDav(name: String) {
        waitDialog.setText("恢复中…")
        waitDialog.show()
        val task = Coroutine.async {
            AppWebDav.restoreWebDav(name)
        }.onError {
            AppLog.put("WebDav恢复出错\n${it.localizedMessage}", it)
            appCtx.toastOnUi("WebDav恢复出错\n${it.localizedMessage}")
        }.onFinally {
            waitDialog.dismiss()
        }
        waitDialog.setOnCancelListener {
            task.cancel()
        }
    }

    private fun restoreFromLocal() {
        restoreDoc.launch {
            title = getString(R.string.select_restore_file)
            mode = HandleFileContract.FILE
            allowExtensions = arrayOf("zip")
        }
    }

    /**
     * 测试 Reader Server 连接
     */
    private fun testReaderServerConnection() {
        if (!AppConfig.readerServerConfigured) {
            appCtx.toastOnUi(R.string.reader_server_not_configured)
            return
        }
        
        val serverUrl = AppConfig.readerServerUrl ?: ""
        
        // 检测 HTTP URL，如果 SSL 验证开启则提示
        if (serverUrl.startsWith("http://", ignoreCase = true) && AppConfig.readerServerStrictSsl) {
            showSslHintDialog(getString(R.string.reader_server_http_detected))
            return
        }
        
        doTestReaderServerConnection()
    }
    
    /**
     * 执行实际的连接测试
     */
    private fun doTestReaderServerConnection() {
        waitDialog.setText(R.string.testing_connection)
        waitDialog.show()
        lifecycleScope.launch {
            try {
                withContext(IO) {
                    ReaderServerSync.initConfig()
                }
                val result = withContext(IO) {
                    ReaderServerSync.testConnection()
                }
                result.onSuccess { success ->
                    if (success) {
                        appCtx.toastOnUi(R.string.reader_server_connect_success)
                    } else {
                        appCtx.toastOnUi(R.string.reader_server_connect_fail)
                    }
                }.onFailure { e ->
                    handleConnectionError(e)
                }
            } catch (e: Exception) {
                handleConnectionError(e)
            } finally {
                waitDialog.dismiss()
            }
        }
    }
    
    /**
     * 处理连接错误，检测是否为 SSL 相关错误
     */
    private fun handleConnectionError(e: Throwable) {
        AppLog.put("Reader Server 连接测试失败: ${e.localizedMessage}", e)
        
        // 检测是否为 SSL 相关错误
        val isSslError = e is javax.net.ssl.SSLException ||
                e is javax.net.ssl.SSLHandshakeException ||
                e.cause is javax.net.ssl.SSLException ||
                e.cause is javax.net.ssl.SSLHandshakeException ||
                e.message?.contains("SSL", ignoreCase = true) == true ||
                e.message?.contains("Certificate", ignoreCase = true) == true
        
        if (isSslError && AppConfig.readerServerStrictSsl) {
            showSslHintDialog(getString(R.string.reader_server_ssl_error))
        } else {
            appCtx.toastOnUi(getString(R.string.reader_server_connect_fail) + "\n${e.localizedMessage}")
        }
    }
    
    /**
     * 显示 SSL 提示对话框
     */
    private fun showSslHintDialog(message: String) {
        alert(R.string.reader_server_ssl_hint_title) {
            setMessage(message + "\n\n" + getString(R.string.reader_server_disable_ssl_confirm))
            okButton {
                AppConfig.readerServerStrictSsl = false
                // 更新设置界面的开关状态
                findPreference<io.legado.app.lib.prefs.SwitchPreference>(PreferKey.readerServerStrictSsl)?.isChecked = false
                // 自动重试连接
                doTestReaderServerConnection()
            }
            cancelButton()
        }
    }

    /**
     * 立即同步 Reader Server
     */
    private fun syncReaderServerNow() {
        if (!AppConfig.readerServerConfigured) {
            appCtx.toastOnUi(R.string.reader_server_not_configured)
            return
        }
        waitDialog.setText(R.string.syncing)
        waitDialog.show()
        lifecycleScope.launch {
            try {
                withContext(IO) {
                    ReaderServerSync.initConfig()
                }
                val result = withContext(IO) {
                    ReaderServerSync.syncAll()
                }
                result.onSuccess { results ->
                    val summary = buildString {
                        results["bookSource"]?.let {
                            appendLine("书源: ${it}")
                        }
                        results["bookshelf"]?.let {
                            appendLine("书架: ${it}")
                        }
                        results["rssSource"]?.let {
                            appendLine("订阅源: ${it}")
                        }
                    }
                    appCtx.toastOnUi(getString(R.string.reader_server_sync_success) + "\n$summary")
                }.onFailure { e ->
                    AppLog.put("Reader Server 同步失败: ${e.localizedMessage}", e)
                    appCtx.toastOnUi(getString(R.string.reader_server_sync_fail) + "\n${e.localizedMessage}")
                }
            } catch (e: Exception) {
                AppLog.put("Reader Server 同步失败: ${e.localizedMessage}", e)
                appCtx.toastOnUi(getString(R.string.reader_server_sync_fail) + "\n${e.localizedMessage}")
            } finally {
                waitDialog.dismiss()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        waitDialog.dismiss()
    }

}