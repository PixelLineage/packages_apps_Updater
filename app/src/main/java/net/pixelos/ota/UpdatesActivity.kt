/*
 * Copyright (C) 2017-2023 The LineageOS Project
 * Copyright (C) 2025 PixelOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.pixelos.ota

import android.app.ComponentCaller
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.res.Resources
import android.icu.text.DateFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.text.format.Formatter
import android.util.Log
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomappbar.BottomAppBar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.pixelos.ota.controller.UpdaterController
import net.pixelos.ota.controller.UpdaterService
import net.pixelos.ota.controller.UpdaterService.LocalBinder
import net.pixelos.ota.download.DownloadClient
import net.pixelos.ota.misc.Constants
import net.pixelos.ota.misc.StringGenerator.getDateLocalized
import net.pixelos.ota.misc.StringGenerator.getDateLocalizedUTC
import net.pixelos.ota.misc.StringGenerator.getTimeLocalized
import net.pixelos.ota.misc.Utils.canInstall
import net.pixelos.ota.misc.Utils.checkForNewUpdates
import net.pixelos.ota.misc.Utils.getCachedUpdateList
import net.pixelos.ota.misc.Utils.getChangelogURL
import net.pixelos.ota.misc.Utils.getServerURL
import net.pixelos.ota.misc.Utils.isABUpdate
import net.pixelos.ota.misc.Utils.isUpdateCheckEnabled
import net.pixelos.ota.misc.Utils.parseJson
import net.pixelos.ota.misc.Utils.securityPatch
import net.pixelos.ota.misc.Utils.triggerUpdate
import net.pixelos.ota.model.Update
import net.pixelos.ota.model.UpdateInfo
import net.pixelos.ota.model.UpdateStatus
import org.json.JSONException
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.text.NumberFormat
import java.util.UUID

class UpdatesActivity : AppCompatActivity(), UpdateImporter.Callbacks {
    private val mBottomAppBar by lazy { requireViewById<BottomAppBar>(R.id.bottomAppBar) }
    private val mCircularProgress by lazy {
        requireViewById<CircularProgressIndicator>(R.id.updateRefreshProgress)
    }
    private val mCircularProgressContainer by lazy {
        requireViewById<FrameLayout>(R.id.updateRefreshProgressContainer)
    }
    private val mUpdateIcon by lazy { requireViewById<ImageView>(R.id.updateIcon) }
    private val mCurrentBuildInfo by lazy { requireViewById<LinearLayout>(R.id.buildInfo) }
    private val headerBuildVersion by lazy { requireViewById<TextView>(R.id.currentBuildVersion) }
    private val headerSecurityPatch by lazy { requireViewById<TextView>(R.id.currentSecurityPatch) }
    private val mProgress by lazy { requireViewById<LinearLayout>(R.id.downloadProgress) }
    private val mUpdateStatusLayout by lazy { requireViewById<LinearLayout>(R.id.updateStatusLayout) }
    private val mProgressBar by lazy { requireViewById<LinearProgressIndicator>(R.id.progressBar) }
    private val mPrimaryActionButton by lazy { requireViewById<MaterialButton>(R.id.primaryButton) }
    private val mSecondaryActionButton by lazy {
        requireViewById<MaterialButton>(R.id.secondaryButton)
    }
    private val mWarnMeteredConnectionCard by lazy {
        requireViewById<MaterialCardView>(R.id.meteredWarningCard)
    }
    private val mSwipeRefresh by lazy { requireViewById<SwipeRefreshLayout>(R.id.swipeRefresh) }
    private val mChangelogSection by lazy { requireViewById<TextView>(R.id.changelogSection) }
    private val mProgressText by lazy { requireViewById<TextView>(R.id.progressText) }
    private val mProgressPercent by lazy { requireViewById<TextView>(R.id.progressPercent) }
    private val mUpdateInfoWarning by lazy { requireViewById<TextView>(R.id.updateInfoWarning) }
    private val mUpdateStatus by lazy { requireViewById<TextView>(R.id.updateStatus) }
    private val toolbar by lazy { requireViewById<MaterialToolbar>(R.id.toolbar) }
    private val mNestedScrollView by lazy { requireViewById<NestedScrollView>(R.id.nestedScrollView) }

    private var mBroadcastReceiver: BroadcastReceiver
    private var mLatestDownloadId: String
    private var mUpdateImporter: UpdateImporter

    private var mUpdaterController: UpdaterController? = null
    private var mUpdaterService: UpdaterService? = null

    init {
        mLatestDownloadId = ""

        mBroadcastReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val downloadId: String? =
                        intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID)
                    if (UpdaterController.ACTION_UPDATE_STATUS == intent.action) {
                        handleDownloadStatusChange(downloadId!!)
                        updateUI(downloadId)
                    } else if (
                        UpdaterController.ACTION_DOWNLOAD_PROGRESS == intent.action ||
                        UpdaterController.ACTION_INSTALL_PROGRESS == intent.action
                    ) {
                        updateUI(downloadId!!)
                    } else if (UpdaterController.ACTION_UPDATE_REMOVED == intent.action) {
                        removeUpdate(downloadId!!)
                        downloadUpdatesList(false)
                    }
                }
            }

        mUpdateImporter = UpdateImporter(this, this)
    }

    private val mConnection: ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(className: ComponentName, service: IBinder) {
                val binder: LocalBinder = service as LocalBinder
                mUpdaterService = binder.service
                mUpdaterService?.let { updaterService ->
                    mUpdaterController = updaterService.updaterController
                }
                if (mUpdaterController?.hasActiveDownloads() == true ||
                    mUpdaterController?.isVerifyingUpdate == true ||
                    mUpdaterController?.isInstallingUpdate == true) {
                    updatesList
                } else {
                    downloadUpdatesList(true)
                }
            }

            override fun onServiceDisconnected(componentName: ComponentName) {
                mUpdaterService = null
                mUpdaterController = null
            }
        }

    private var importDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_updates)

        setSupportActionBar(toolbar)
        supportActionBar?.apply { title = null }

        setupHeaderProperties()
        updateLastCheckedString()

        setupSwipeRefresh()
        setupInsets()
    }

    private fun setupHeaderProperties() {
        headerBuildVersion.text = getString(R.string.header_android_version, Build.VERSION.RELEASE)
        headerSecurityPatch.text = getString(R.string.header_android_security_patch, securityPatch)
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(this.requireViewById(android.R.id.content))
        { v: View,
          windowInsets: WindowInsetsCompat ->
            val insets: Insets =
                windowInsets.getInsets(
                    (WindowInsetsCompat.Type.systemBars() or
                            WindowInsetsCompat.Type.ime() or
                            WindowInsetsCompat.Type.displayCutout())
                )
            val statusBarHeight: Int =
                this.window.decorView.rootWindowInsets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(insets.left, statusBarHeight, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupSwipeRefresh() {
        mSwipeRefresh.setOnRefreshListener {
            Handler(Looper.getMainLooper())
                .postDelayed(
                    {
                        if (mSwipeRefresh.isRefreshing) {
                            mSwipeRefresh.isRefreshing = false
                        }
                    },
                    0,
                )
            mUpdateInfoWarning.isVisible = false
            downloadUpdatesList(true)
        }
        mSwipeRefresh.setProgressBackgroundColorSchemeResource(R.color.background)
        // Not sure if there's a better way
        val typedValue = TypedValue()
        theme.resolveAttribute(android.R.attr.colorAccent, typedValue, true)
        mSwipeRefresh.setColorSchemeColors(typedValue.data)
        mSwipeRefresh.isEnabled = mNestedScrollView.canScrollVertically(1)
    }

    public override fun onStart() {
        super.onStart()
        val intent = Intent(this, UpdaterService::class.java)
        startService(intent)
        bindService(intent, mConnection, BIND_AUTO_CREATE)

        val intentFilter = IntentFilter()
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_STATUS)
        intentFilter.addAction(UpdaterController.ACTION_DOWNLOAD_PROGRESS)
        intentFilter.addAction(UpdaterController.ACTION_INSTALL_PROGRESS)
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_REMOVED)
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, intentFilter)
    }

    override fun onPause() {
        if (importDialog != null) {
            importDialog!!.dismiss()
            importDialog = null
            mUpdateImporter.stopImport()
        }

        super.onPause()
    }

    public override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver)
        if (mUpdaterService != null) {
            unbindService(mConnection)
        }
        super.onStop()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_toolbar, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val itemId: Int = item.itemId
        if (itemId == R.id.menu_local_update) {
            mUpdateImporter.openImportPicker()
            return true
        } else if (itemId == R.id.menu_preferences) {
            val settingsActivity = Intent(this, SettingsActivity::class.java)
            startActivity(settingsActivity)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
        caller: ComponentCaller,
    ) {
        if (data == null || !mUpdateImporter.onResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data, caller)
        }
    }

    override fun onImportStarted() {
        if (importDialog != null && importDialog!!.isShowing) {
            importDialog!!.dismiss()
        }

        importDialog =
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.local_update_import)
                .setView(R.layout.progress_dialog)
                .setCancelable(false)
                .create()

        importDialog!!.show()
    }

    override fun onImportCompleted(update: Update?) {
        if (importDialog != null) {
            importDialog!!.dismiss()
            importDialog = null
        }

        if (update == null) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.local_update_import)
                .setMessage(R.string.local_update_import_failure)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val deleteUpdate = Runnable {
            UpdaterController.getInstance(this).deleteUpdate(update.downloadId)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.local_update_import)
            .setMessage(getString(R.string.local_update_import_success, update.version))
            .setPositiveButton(R.string.local_update_import_install) { _: DialogInterface?, _: Int ->
                // Update UI
                updateUI(update.downloadId)
                updatesList
                triggerUpdate(this, update.downloadId)
            }
            .setNegativeButton(android.R.string.cancel) { _: DialogInterface?, _: Int ->
                deleteUpdate.run()
            }
            .setOnCancelListener { deleteUpdate.run() }
            .show()
    }

    private fun startRefreshAnimation() {
        mCircularProgressContainer.isVisible = true
        mCircularProgress.isVisible = true
        mCurrentBuildInfo.isVisible = false
        mUpdateStatusLayout.isVisible = false
        mBottomAppBar.isVisible = false
        mUpdateStatus.setText(R.string.checking_for_update)
        mCircularProgress.indicatorSize = 600
        mCircularProgress.animate().alpha(1f).start()
        mCircularProgress.show()
    }

    private fun stopRefreshAnimation() {
        mCircularProgress.animate().alpha(0f).start()
        mCircularProgress.isVisible = false
        mCircularProgressContainer.isVisible = false
        mUpdateStatusLayout.isVisible = true
        mBottomAppBar.isVisible = true
    }

    private fun setupButtonAction(action: Action, button: MaterialButton, enabled: Boolean) {
        val clickListener: View.OnClickListener?
        when (action) {
            Action.CHECK_UPDATES -> {
                button.setText(R.string.check_for_update)
                button.isEnabled = enabled
                clickListener =
                    if (enabled)
                        View.OnClickListener {
                            mUpdateInfoWarning.isVisible = false
                            downloadUpdatesList(true)
                        }
                    else null
            }

            Action.DOWNLOAD -> {
                button.setText(R.string.action_download)
                button.isEnabled = enabled
                clickListener =
                    if (enabled)
                        View.OnClickListener { mUpdaterController!!.startDownload(mLatestDownloadId) }
                    else null
            }

            Action.PAUSE -> {
                button.setText(R.string.action_pause)
                button.isEnabled = enabled
                clickListener =
                    if (enabled)
                        View.OnClickListener { mUpdaterController!!.pauseDownload(mLatestDownloadId) }
                    else null
            }

            Action.RESUME -> {
                button.setText(R.string.action_resume)
                button.isEnabled = enabled
                clickListener =
                    if (enabled)
                        View.OnClickListener { _: View? ->
                            mUpdateInfoWarning.isVisible = false
                            val update: UpdateInfo =
                                mUpdaterController!!.getUpdate(mLatestDownloadId)
                            if (canInstall(update) || update.file.length() == update.fileSize) {
                                mUpdaterController!!.resumeDownload(mLatestDownloadId)
                            } else {
                                showUpdateInfo(R.string.snack_update_not_installable)
                            }
                        }
                    else null
            }

            Action.INSTALL -> {
                button.setText(R.string.action_install)
                button.isEnabled = enabled
                clickListener =
                    if (enabled)
                        View.OnClickListener { _: View? ->
                            if (canInstall(mUpdaterController!!.getUpdate(mLatestDownloadId))) {
                                getInstallDialog(mLatestDownloadId).show()
                            } else {
                                showUpdateInfo(R.string.snack_update_not_installable)
                            }
                        }
                    else null
            }

            Action.DELETE -> {
                button.setText(R.string.action_delete)
                button.isEnabled = enabled
                clickListener =
                    if (enabled)
                        View.OnClickListener { _: View? -> getDeleteDialog(mLatestDownloadId).show() }
                    else null
            }

            Action.CANCEL_INSTALLATION -> {
                button.setText(R.string.action_cancel)
                button.isEnabled = enabled
                clickListener =
                    if (enabled)
                        View.OnClickListener { _: View? ->
                            cancelInstallationDialog.show()
                            mWarnMeteredConnectionCard.isVisible = false
                        }
                    else null
            }

            Action.REBOOT -> {
                button.setText(R.string.action_reboot)
                button.isEnabled = enabled
                clickListener =
                    if (enabled) View.OnClickListener { _: View? -> rebootInstallationDialog.show() }
                    else null
            }
        }

        // Disable action mode when a button is clicked
        button.setOnClickListener { v: View? -> clickListener?.onClick(v) }
    }

    @Throws(IOException::class, JSONException::class)
    private fun loadUpdatesList(jsonFile: File, manualRefresh: Boolean) {
        Log.d(TAG, "Adding remote updates")
        val controller: UpdaterController = mUpdaterService!!.updaterController
        var newUpdates = false

        val updates: List<UpdateInfo> = parseJson(jsonFile, true)
        val updatesOnline: MutableList<String> = ArrayList()
        for (update: UpdateInfo in updates) {
            newUpdates = newUpdates or controller.addUpdate(update)
            updatesOnline.add(update.downloadId)
        }
        controller.setUpdatesAvailableOnline(updatesOnline, true)

        if (manualRefresh) {
            if (newUpdates) {
                mUpdateStatus.setText(R.string.system_update_available)
                mCurrentBuildInfo.isVisible = false
                setChangelogs(mChangelogSection)
            } else {
                mUpdateStatus.setText(R.string.system_up_to_date)
                mCurrentBuildInfo.isVisible = true
                mChangelogSection.isVisible = false
                mWarnMeteredConnectionCard.isVisible = false
            }
        }

        val sortedUpdates: List<UpdateInfo> = controller.updates
        if (sortedUpdates.isEmpty()) {
            updateUI("")
        } else {
            sortedUpdates.sortedByDescending { it.timestamp }
            mLatestDownloadId = sortedUpdates[0].downloadId
            updateUI(mLatestDownloadId)
        }
    }

    private val updatesList: Unit
        get() {
            val jsonFile: File = getCachedUpdateList(this)
            if (jsonFile.exists()) {
                try {
                    loadUpdatesList(jsonFile, false)
                    Log.d(TAG, "Cached list parsed")
                } catch (e: IOException) {
                    Log.e(TAG, "Error while parsing json list", e)
                } catch (e: JSONException) {
                    Log.e(TAG, "Error while parsing json list", e)
                }
            } else {
                downloadUpdatesList(false)
            }
        }

    private fun processNewJson(json: File, jsonNew: File, manualRefresh: Boolean) {
        try {
            val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            val millis: Long = System.currentTimeMillis()
            loadUpdatesList(jsonNew, manualRefresh)
            prefs.edit().putLong(Constants.PREF_LAST_UPDATE_CHECK, millis).apply()
            updateLastCheckedString()
            if (json.exists() && isUpdateCheckEnabled(this) && checkForNewUpdates(json, jsonNew)) {
                UpdatesCheckReceiver.updateRepeatingUpdatesCheck(this)
            }
            // In case we set a one-shot check because of a previous failure
            UpdatesCheckReceiver.cancelUpdatesCheck(this)
            jsonNew.renameTo(json)
        } catch (e: IOException) {
            Log.e(TAG, "Could not read json", e)
            mUpdateStatus.setText(R.string.check_for_update_failed)
            mUpdateIcon.setImageResource(R.drawable.ic_system_update_warning)
            showUpdateInfo(R.string.snack_updates_check_failed)
            mChangelogSection.isVisible = false
            mWarnMeteredConnectionCard.isVisible = false
            mCurrentBuildInfo.isVisible = true
            setupButtonAction(Action.CHECK_UPDATES, mPrimaryActionButton, true)
        } catch (e: JSONException) {
            Log.e(TAG, "Could not read json", e)
            mUpdateStatus.setText(R.string.check_for_update_failed)
            mUpdateIcon.setImageResource(R.drawable.ic_system_update_warning)
            showUpdateInfo(R.string.snack_updates_check_failed)
            mChangelogSection.isVisible = false
            mWarnMeteredConnectionCard.isVisible = false
            mCurrentBuildInfo.isVisible = true
            setupButtonAction(Action.CHECK_UPDATES, mPrimaryActionButton, true)
        }
    }

    private fun downloadUpdatesList(manualRefresh: Boolean) {
        val jsonFile: File = getCachedUpdateList(this)
        val jsonFileTmp = File(jsonFile.absolutePath + UUID.randomUUID())
        val url: String = getServerURL(this)
        Log.d(TAG, "Checking $url")

        val callback: DownloadClient.DownloadCallback =
            object : DownloadClient.DownloadCallback {
                override fun onFailure(cancelled: Boolean) {
                    Log.e(TAG, "Could not download updates list")
                    runOnUiThread {
                        if (!cancelled) {
                            showUpdateInfo(R.string.snack_updates_check_failed)
                        }
                        stopRefreshAnimation()
                        mUpdateIcon.setImageResource(R.drawable.ic_system_update_warning)
                        mUpdateStatus.setText(R.string.check_for_update_failed)
                        mChangelogSection.isVisible = false
                        mWarnMeteredConnectionCard.isVisible = false
                        mCurrentBuildInfo.isVisible = true
                        setupButtonAction(Action.CHECK_UPDATES, mPrimaryActionButton, true)
                    }
                }

                override fun onResponse(headers: DownloadClient.Headers) {}

                override fun onSuccess() {
                    runOnUiThread {
                        Log.d(TAG, "List downloaded")
                        processNewJson(jsonFile, jsonFileTmp, manualRefresh)
                        stopRefreshAnimation()
                    }
                }
            }

        val downloadClient: DownloadClient
        try {
            downloadClient =
                DownloadClient.Builder()
                    .setUrl(url)
                    .setDestination(jsonFileTmp)
                    .setDownloadCallback(callback)
                    .build()
        } catch (exception: IOException) {
            Log.e(TAG, "Could not build download client")
            mUpdateStatus.setText(R.string.check_for_update_failed)
            mUpdateIcon.setImageResource(R.drawable.ic_system_update_warning)
            showUpdateInfo(R.string.snack_updates_check_failed)
            mChangelogSection.isVisible = false
            mWarnMeteredConnectionCard.isVisible = false
            mCurrentBuildInfo.isVisible = true
            setupButtonAction(Action.CHECK_UPDATES, mPrimaryActionButton, true)
            return
        }

        startRefreshAnimation()
        downloadClient.start()
    }

    private fun setChangelogs(mShowChangelogs: TextView) {
        mShowChangelogs.isVisible = true
        val changelogUrl = getChangelogURL(this)

        lifecycleScope.launch {
            val markdown =
                withContext(Dispatchers.IO) {
                    try {
                        val url = URL(changelogUrl)
                        val connection = url.openConnection() as HttpURLConnection
                        connection.requestMethod = "GET"
                        connection.connectTimeout = 5000
                        connection.readTimeout = 5000

                        connection.inputStream.bufferedReader().use { reader ->
                            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                                reader.readText()
                            } else {
                                getString(R.string.fetch_changelogs_failed)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Could not load changelog", e)
                        getString(R.string.fetch_changelogs_failed)
                    }
                }

            val markwon = Markwon.create(this@UpdatesActivity)
            markwon.setMarkdown(mShowChangelogs, markdown)
        }
    }

    private fun updateLastCheckedString() {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val lastCheck: Long = prefs.getLong(Constants.PREF_LAST_UPDATE_CHECK, -1) / 1000
        val lastCheckString: String =
            getString(
                R.string.header_last_updates_check,
                getDateLocalized(this, DateFormat.LONG, lastCheck),
                getTimeLocalized(this, lastCheck),
            )
        val headerLastCheck: TextView = requireViewById(R.id.lastSuccessfulCheck)
        headerLastCheck.text = lastCheckString
    }

    private fun handleDownloadStatusChange(downloadId: String) {
        if (Update.LOCAL_ID == downloadId) {
            return
        }

        val update: UpdateInfo = mUpdaterController!!.getUpdate(downloadId)
        when (update.status) {
            UpdateStatus.PAUSED_ERROR -> {
                setupButtonAction(Action.DELETE, mSecondaryActionButton, true)
                mUpdateIcon.setImageResource(R.drawable.ic_system_update_warning)
                showUpdateInfo(R.string.snack_download_failed)
            }

            UpdateStatus.VERIFICATION_FAILED -> {
                mUpdateIcon.setImageResource(R.drawable.ic_system_update_warning)
                showUpdateInfo(R.string.snack_download_verification_failed)
            }

            UpdateStatus.VERIFIED -> {
                mUpdateInfoWarning.isVisible = false
                mUpdateStatus.setText(R.string.snack_download_verified)
            }

            else -> return
        }
    }

    private fun updateUI(downloadId: String) {
        if (mLatestDownloadId.isEmpty()) {
            setupButtonAction(Action.CHECK_UPDATES, mPrimaryActionButton, true)
            mUpdateIcon.setImageResource(R.drawable.ic_system_update)
            mUpdateStatus.setText(R.string.system_up_to_date)
            mCurrentBuildInfo.isVisible = true
            mSwipeRefresh.isEnabled = false
            return
        }

        val update: UpdateInfo = mUpdaterController!!.getUpdate(downloadId) ?: return

        mUpdateStatus.setText(R.string.system_update_available)
        mProgress.isVisible = false
        mCurrentBuildInfo.isVisible = false
        setChangelogs(mChangelogSection)

        val activeLayout: Boolean =
            update.persistentStatus == UpdateStatus.Persistent.INCOMPLETE ||
                    update.status == UpdateStatus.STARTING ||
                    update.status == UpdateStatus.INSTALLING ||
                    mUpdaterController!!.isVerifyingUpdate

        if (activeLayout) {
            handleActiveStatus(update)
        } else {
            handleNotActiveStatus(update)
        }

        mLatestDownloadId = downloadId
    }

    private fun handleActiveStatus(update: UpdateInfo) {
        var showCancelButton = false
        var canDelete = false
        val downloadId: String = update.downloadId
        if (mUpdaterController!!.isDownloading(downloadId)) {
            showCancelButton = true
            canDelete = true
            val downloaded: String = Formatter.formatShortFileSize(this, update.file.length())
            val total: String = Formatter.formatShortFileSize(this, update.fileSize)
            val percentage: String =
                NumberFormat.getPercentInstance().format((update.progress / 100f).toDouble())
            mProgressPercent.text = percentage
            mProgressText.text = getString(R.string.list_download_progress_newer, downloaded, total)
            mUpdateStatus.setText(R.string.system_update_downloading)
            mUpdateIcon.setImageResource(R.drawable.ic_system_update)
            setupButtonAction(Action.PAUSE, mPrimaryActionButton, true)
            mWarnMeteredConnectionCard.isVisible = true
            mProgressBar.isIndeterminate = update.status == UpdateStatus.STARTING
            mProgressBar.progress = update.progress
        } else if (mUpdaterController!!.isInstallingUpdate(downloadId)) {
            showCancelButton = true
            canDelete = true
            setupButtonAction(Action.CANCEL_INSTALLATION, mSecondaryActionButton, true)
            mUpdateStatus.setText(R.string.system_update_installing)
            mPrimaryActionButton.isVisible = false
            val notAB: Boolean = !mUpdaterController!!.isInstallingABUpdate
            if (Update.LOCAL_ID == update.downloadId) {
                mChangelogSection.isVisible = false
                mWarnMeteredConnectionCard.isVisible = false
                mUpdateStatus.setText(R.string.local_update_installing)
                showCancelButton = false
            }
            mProgressText.setText(
                if (notAB) R.string.dialog_prepare_zip_message
                else if (update.finalizing) R.string.finalizing_package
                else R.string.preparing_ota_first_boot
            )
            mProgressPercent.text =
                NumberFormat.getPercentInstance().format((update.installProgress / 100f).toDouble())
            mProgressBar.isIndeterminate = false
            mProgressBar.progress = update.installProgress
        } else if (mUpdaterController!!.isVerifyingUpdate(downloadId)) {
            setupButtonAction(Action.INSTALL, mPrimaryActionButton, false)
            mUpdateStatus.setText(R.string.system_update_verifying)
            mProgressText.setText(R.string.list_verifying_update)
            mProgressBar.isIndeterminate = true
        } else {
            showCancelButton = true
            canDelete = true
            setupButtonAction(Action.RESUME, mPrimaryActionButton, !isBusy)
            val downloaded: String = Formatter.formatShortFileSize(this, update.file.length())
            val total: String = Formatter.formatShortFileSize(this, update.fileSize)
            val percentage: String =
                NumberFormat.getPercentInstance().format((update.progress / 100f).toDouble())
            mUpdateIcon.setImageResource(R.drawable.ic_system_update)
            mWarnMeteredConnectionCard.isVisible = true
            mProgressPercent.text = percentage
            mProgressText.text = getString(R.string.list_download_progress_newer, downloaded, total)
            mProgressBar.isIndeterminate = false
            mProgressBar.progress = update.progress
            mUpdateStatus.setText(R.string.system_update_downloading_paused)
        }

        setupButtonAction(
            if (canDelete) Action.DELETE else Action.CANCEL_INSTALLATION,
            mSecondaryActionButton,
            !isBusy,
        )
        mSecondaryActionButton.visibility = if (showCancelButton) View.VISIBLE else View.GONE

        mProgress.isVisible = true
        mSwipeRefresh.isEnabled = false
    }

    private fun handleNotActiveStatus(update: UpdateInfo) {
        val downloadId: String = update.downloadId
        var showCancelButton = false
        if (mUpdaterController!!.isWaitingForReboot(downloadId)) {
            mUpdateIcon.setImageResource(R.drawable.ic_system_update_success)
            mUpdateStatus.setText(R.string.installing_update_finished)
            mChangelogSection.isVisible = false
            setupButtonAction(Action.REBOOT, mPrimaryActionButton, true)
            mPrimaryActionButton.isVisible = true
        } else if (update.persistentStatus == UpdateStatus.Persistent.VERIFIED) {
            showCancelButton = true
            if (canInstall(update)) {
                setupButtonAction(Action.INSTALL, mPrimaryActionButton, !isBusy)
            } else {
                mPrimaryActionButton.isVisible = false
                setupButtonAction(Action.DELETE, mSecondaryActionButton, !isBusy)
            }
        } else {
            mWarnMeteredConnectionCard.isVisible = true
            setupButtonAction(Action.DOWNLOAD, mPrimaryActionButton, !isBusy)
        }

        mSecondaryActionButton.visibility = if (showCancelButton) View.VISIBLE else View.GONE
        mProgress.isVisible = false
    }

    private fun removeUpdate(downloadId: String) {
        if (mLatestDownloadId == downloadId) {
            mLatestDownloadId = ""
            updateUI(mLatestDownloadId)
        }
    }

    private val isBusy: Boolean
        get() =
            mUpdaterController!!.hasActiveDownloads() ||
                    mUpdaterController!!.isVerifyingUpdate ||
                    mUpdaterController!!.isInstallingUpdate

    private fun showUpdateInfo(stringId: Int) {
        mUpdateInfoWarning.isVisible = true
        mUpdateInfoWarning.setText(stringId)
    }

    private fun getDeleteDialog(downloadId: String): MaterialAlertDialogBuilder {
        return MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_delete_dialog_title)
            .setMessage(R.string.confirm_delete_dialog_message)
            .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                mWarnMeteredConnectionCard.isVisible = false
                mUpdaterController!!.pauseDownload(downloadId)
                mUpdaterController!!.deleteUpdate(downloadId)
                mSecondaryActionButton.isVisible = false
                mUpdateInfoWarning.isVisible = false
                mSwipeRefresh.isEnabled = true
            }
            .setNegativeButton(android.R.string.cancel, null)
    }

    private fun getInstallDialog(downloadId: String): MaterialAlertDialogBuilder {
        if (!isBatteryLevelOk) {
            val resources: Resources = resources
            val message: String =
                resources.getString(
                    R.string.dialog_battery_low_message_pct,
                    resources.getInteger(R.integer.battery_ok_percentage_discharging),
                    resources.getInteger(R.integer.battery_ok_percentage_charging),
                )
            return MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_battery_low_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
        }
        if (isScratchMounted) {
            return MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_scratch_mounted_title)
                .setMessage(R.string.dialog_scratch_mounted_message)
                .setPositiveButton(android.R.string.ok, null)
        }
        val update: UpdateInfo = mUpdaterController!!.getUpdate(downloadId)
        val resId: Int =
            try {
                if (isABUpdate(update.file)) {
                    R.string.apply_update_dialog_message_ab
                } else {
                    R.string.apply_update_dialog_message
                }
            } catch (e: IOException) {
                Log.e(TAG, "Could not determine the type of the update")
            }

        val buildDate: String = getDateLocalizedUTC(this, DateFormat.MEDIUM, update.timestamp)
        val buildInfoText: String =
            getString(R.string.list_build_version_date, update.version, buildDate)
        return MaterialAlertDialogBuilder(this)
            .setTitle(R.string.apply_update_dialog_title)
            .setMessage(getString(resId, buildInfoText, getString(android.R.string.ok)))
            .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                triggerUpdate(this, downloadId)
            }
            .setNegativeButton(android.R.string.cancel, null)
    }

    private val rebootInstallationDialog: MaterialAlertDialogBuilder
        get() {
            return MaterialAlertDialogBuilder(this)
                .setMessage(R.string.reboot_installation_dialog_message)
                .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                    val pm: PowerManager = getSystemService(PowerManager::class.java)!!
                    pm.reboot(null)
                }
                .setNegativeButton(android.R.string.cancel, null)
        }

    private val cancelInstallationDialog: MaterialAlertDialogBuilder
        get() {
            return MaterialAlertDialogBuilder(this)
                .setMessage(R.string.cancel_installation_dialog_message)
                .setPositiveButton(android.R.string.ok) { _: DialogInterface?, _: Int ->
                    val intent = Intent(this, UpdaterService::class.java)
                    intent.setAction(UpdaterService.ACTION_INSTALL_STOP)
                    startService(intent)
                    mSecondaryActionButton.isVisible = false
                }
                .setNegativeButton(android.R.string.cancel, null)
        }

    private val isBatteryLevelOk: Boolean
        get() {
            val intent: Intent? =
                registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent == null || !intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) {
                return true
            }
            val percent: Int =
                Math.round(
                    100f * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) /
                            intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                )
            val plugged: Int = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            val required: Int =
                if ((plugged and BATTERY_PLUGGED_ANY) != 0)
                    resources.getInteger(R.integer.battery_ok_percentage_charging)
                else resources.getInteger(R.integer.battery_ok_percentage_discharging)
            return percent >= required
        }

    private enum class Action {
        CHECK_UPDATES,
        DOWNLOAD,
        PAUSE,
        RESUME,
        INSTALL,
        DELETE,
        CANCEL_INSTALLATION,
        REBOOT,
    }

    companion object {
        private const val TAG: String = "UpdatesActivity"
        private const val BATTERY_PLUGGED_ANY: Int =
            (BatteryManager.BATTERY_PLUGGED_AC or
                    BatteryManager.BATTERY_PLUGGED_USB or
                    BatteryManager.BATTERY_PLUGGED_WIRELESS)
        private val isScratchMounted: Boolean
            get() {
                try {
                    Files.lines(Path.of("/proc/mounts")).use { lines ->
                        return lines.anyMatch { x: String ->
                            x.split(" ".toRegex()).dropLastWhile { it.isEmpty() }
                                .toTypedArray()[1] ==
                                    "/mnt/scratch"
                        }
                    }
                } catch (e: IOException) {
                    return false
                }
            }
    }
}
