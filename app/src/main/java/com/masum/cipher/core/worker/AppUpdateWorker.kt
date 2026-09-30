package com.masum.cipher.core.worker

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.UpdateAvailability
import com.masum.cipher.core.data.local.pref.UserPreferences
import com.masum.cipher.core.notifications.LocalNotificationManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AppUpdateWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WorkerEntryPoint {
        fun userPreferences(): UserPreferences
        fun localNotificationManager(): LocalNotificationManager
    }

    override suspend fun doWork(): Result {
        val isInstalledFromPlayStore = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val sourceInfo = applicationContext.packageManager.getInstallSourceInfo(applicationContext.packageName)
                sourceInfo.installingPackageName == "com.android.vending"
            } else {
                @Suppress("DEPRECATION")
                applicationContext.packageManager.getInstallerPackageName(applicationContext.packageName) == "com.android.vending"
            }
        } catch (_: Exception) {
            false
        }

        if (!isInstalledFromPlayStore) {
            return Result.success()
        }

        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, WorkerEntryPoint::class.java)
        val userPreferences = entryPoint.userPreferences()
        val localNotificationManager = entryPoint.localNotificationManager()

        val settings = userPreferences.settingsFlow.first()
        if (!settings.notifyAppUpdates) {
            return Result.success()
        }

        val appUpdateManager = AppUpdateManagerFactory.create(applicationContext)

        val appUpdateInfo: AppUpdateInfo? = suspendCancellableCoroutine { cont ->
            appUpdateManager.appUpdateInfo
                .addOnSuccessListener { info ->
                    if (cont.isActive) cont.resume(info)
                }
                .addOnFailureListener {
                    if (cont.isActive) cont.resume(null)
                }
        }

        if (appUpdateInfo != null && appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE) {
            val availableVersionCode = appUpdateInfo.availableVersionCode()
            val currentVersionCode = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0).longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0).versionCode
                }
            } catch (_: Exception) {
                0
            }

            if (availableVersionCode > currentVersionCode && availableVersionCode > settings.lastNotifiedUpdateVersionCode) {
                localNotificationManager.showAppUpdateAvailableNotification()
                userPreferences.setLastNotifiedUpdateVersionCode(availableVersionCode)
            }
        }

        return Result.success()
    }
}
