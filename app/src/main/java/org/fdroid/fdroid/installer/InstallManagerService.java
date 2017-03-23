package org.fdroid.fdroid.installer;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.IBinder;
import android.support.v4.content.LocalBroadcastManager;
import android.text.TextUtils;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.fdroid.fdroid.AppUpdateStatusManager;
import org.fdroid.fdroid.Hasher;
import org.fdroid.fdroid.Utils;
import org.fdroid.fdroid.compat.PackageManagerCompat;
import org.fdroid.fdroid.data.Apk;
import org.fdroid.fdroid.data.App;
import org.fdroid.fdroid.net.Downloader;
import org.fdroid.fdroid.net.DownloaderService;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;

/**
 * Manages the whole process when a background update triggers an install or the user
 * requests an APK to be installed.  It handles checking whether the APK is cached,
 * downloading it, putting up and maintaining a {@link Notification}, and more.
 * <p>
 * The {@link App} and {@link Apk} instances are sent via
 * {@link Intent#putExtra(String, android.os.Bundle)}
 * so that Android handles the message queuing and {@link Service} lifecycle for us.
 * For example, if this {@code InstallManagerService} gets killed, Android will cache
 * and then redeliver the {@link Intent} for us, which includes all of the data needed
 * for {@code InstallManagerService} to do its job for the whole lifecycle of an install.
 * <p>
 * The full URL for the APK file to download is also used as the unique ID to
 * represent the download itself throughout F-Droid.  This follows the model
 * of {@link Intent#setData(Uri)}, where the core data of an {@code Intent} is
 * a {@code Uri}.  The full download URL is guaranteed to be unique since it
 * points to files on a filesystem, where there cannot be multiple files with
 * the same name.  This provides a unique ID beyond just {@code packageName}
 * and {@code versionCode} since there could be different copies of the same
 * APK on different servers, signed by different keys, or even different builds.
 * <p><ul>
 * <li>for a {@link Uri} ID, use {@code Uri}, {@link Intent#getData()}
 * <li>for a {@code String} ID, use {@code urlString}, {@link Uri#toString()}, or
 * {@link Intent#getDataString()}
 * <li>for an {@code int} ID, use {@link String#hashCode()} or {@link Uri#hashCode()}
 * </ul></p>
 * The implementations of {@link Uri#toString()} and {@link Intent#getDataString()} both
 * include caching of the generated {@code String}, so it should be plenty fast.
 * <p>
 * This also handles downloading OBB "APK Extension" files for any APK that has one
 * assigned to it.  OBB files are queued up for download before the APK so that they
 * are hopefully in place before the APK starts.  That is not guaranteed though.
 *
 * @see <a href="https://developer.android.com/google/play/expansion-files.html">APK Expansion Files</a>
 */
public class InstallManagerService extends Service {
    private static final String TAG = "InstallManagerService";

    private static final String ACTION_INSTALL = "org.fdroid.fdroid.installer.action.INSTALL";
    private static final String ACTION_CANCEL = "org.fdroid.fdroid.installer.action.CANCEL";

    /**
     * The install manager service needs to monitor downloaded apks so that it can wait for a user to
     * install them and respond accordingly. Usually the thing which starts listening for such events
     * does so directly after a download is complete. This works great, except when the user then
     * subsequently closes F-Droid and opens it at a later date. Under these circumstances, a background
     * service will scan all downloaded apks and notify the user about them. When it does so, the
     * install manager service needs to add listeners for if the apks get installed.
     */
    private static final String ACTION_MANAGE_DOWNLOADED_APKS = "org.fdroid.fdroid.installer.action.ACTION_MANAGE_DOWNLOADED_APKS";

    private static final String EXTRA_APP = "org.fdroid.fdroid.installer.extra.APP";
    private static final String EXTRA_APK = "org.fdroid.fdroid.installer.extra.APK";

    private LocalBroadcastManager localBroadcastManager;
    private AppUpdateStatusManager appUpdateStatusManager;

    /**
     * This service does not use binding, so no need to implement this method
     */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Utils.debugLog(TAG, "creating Service");
        localBroadcastManager = LocalBroadcastManager.getInstance(this);
        appUpdateStatusManager = AppUpdateStatusManager.getInstance(this);

        BroadcastReceiver br = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String packageName = intent.getData().getSchemeSpecificPart();
                for (AppUpdateStatusManager.AppUpdateStatus status : appUpdateStatusManager.getByPackageName(packageName)) {
                    appUpdateStatusManager.updateApk(status.getUniqueKey(), AppUpdateStatusManager.Status.Installed, null);
                }
            }
        };
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addDataScheme("package");
        registerReceiver(br, intentFilter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Utils.debugLog(TAG, "onStartCommand " + intent);

        String action = intent.getAction();

        if (ACTION_MANAGE_DOWNLOADED_APKS.equals(action)) {
            registerInstallerReceiversForDownlaodedApks();
            return START_NOT_STICKY;
        }

        String urlString = intent.getDataString();
        if (TextUtils.isEmpty(urlString)) {
            Utils.debugLog(TAG, "empty urlString, nothing to do");
            return START_NOT_STICKY;
        }

        if (ACTION_CANCEL.equals(action)) {
            DownloaderService.cancel(this, urlString);
            Apk apk = appUpdateStatusManager.getApk(urlString);
            if (apk != null) {
                DownloaderService.cancel(this, apk.getPatchObbUrl());
                DownloaderService.cancel(this, apk.getMainObbUrl());
            }
            appUpdateStatusManager.removeApk(urlString);
            return START_NOT_STICKY;
        } else if (!ACTION_INSTALL.equals(action)) {
            Utils.debugLog(TAG, "Ignoring " + intent + " as it is not an " + ACTION_INSTALL + " intent");
            return START_NOT_STICKY;
        }

        if (!intent.hasExtra(EXTRA_APP) || !intent.hasExtra(EXTRA_APK)) {
            Utils.debugLog(TAG, urlString + " did not include both an App and Apk instance, ignoring");
            return START_NOT_STICKY;
        }

        if ((flags & START_FLAG_REDELIVERY) == START_FLAG_REDELIVERY
                && !DownloaderService.isQueuedOrActive(urlString)) {
            // TODO is there a case where we should allow an active urlString to pass through?
            Utils.debugLog(TAG, urlString + " finished downloading while InstallManagerService was killed.");
            appUpdateStatusManager.removeApk(urlString);
            return START_NOT_STICKY;
        }

        App app = intent.getParcelableExtra(EXTRA_APP);
        Apk apk = intent.getParcelableExtra(EXTRA_APK);
        if (app == null || apk == null) {
            Utils.debugLog(TAG, "Intent had null EXTRA_APP and/or EXTRA_APK: " + intent);
            return START_NOT_STICKY;
        }
        appUpdateStatusManager.addApk(apk, AppUpdateStatusManager.Status.Unknown, null);

        registerApkDownloaderReceivers(urlString);
        getObb(urlString, apk.getMainObbUrl(), apk.getMainObbFile(), apk.obbMainFileSha256);
        getObb(urlString, apk.getPatchObbUrl(), apk.getPatchObbFile(), apk.obbPatchFileSha256);

        File apkFilePath = ApkCache.getApkDownloadPath(this, intent.getData());
        long apkFileSize = apkFilePath.length();
        if (!apkFilePath.exists() || apkFileSize < apk.size) {
            Utils.debugLog(TAG, "download " + urlString + " " + apkFilePath);
            DownloaderService.queue(this, urlString);
        } else if (ApkCache.apkIsCached(apkFilePath, apk)) {
            Utils.debugLog(TAG, "skip download, we have it, straight to install " + urlString + " " + apkFilePath);
            sendBroadcast(intent.getData(), Downloader.ACTION_STARTED, apkFilePath);
            sendBroadcast(intent.getData(), Downloader.ACTION_COMPLETE, apkFilePath);
        } else {
            Utils.debugLog(TAG, "delete and download again " + urlString + " " + apkFilePath);
            apkFilePath.delete();
            DownloaderService.queue(this, urlString);
        }
        return START_REDELIVER_INTENT; // if killed before completion, retry Intent
    }

    private void sendBroadcast(Uri uri, String action, File file) {
        Intent intent = new Intent(action);
        intent.setData(uri);
        intent.putExtra(Downloader.EXTRA_DOWNLOAD_PATH, file.getAbsolutePath());
        localBroadcastManager.sendBroadcast(intent);
    }

    /**
     * Check if any OBB files are available, and if so, download and install them. This
     * also deletes any obsolete OBB files, per the spec, since there can be only one
     * "main" and one "patch" OBB installed at a time.
     *
     * @see <a href="https://developer.android.com/google/play/expansion-files.html">APK Expansion Files</a>
     */
    private void getObb(final String urlString, String obbUrlString,
                        final File obbDestFile, final String sha256) {
        if (obbDestFile == null || obbDestFile.exists() || TextUtils.isEmpty(obbUrlString)) {
            return;
        }
        final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Downloader.ACTION_STARTED.equals(action)) {
                    Utils.debugLog(TAG, action + " " + intent);
                } else if (Downloader.ACTION_PROGRESS.equals(action)) {

                    int bytesRead = intent.getIntExtra(Downloader.EXTRA_BYTES_READ, 0);
                    int totalBytes = intent.getIntExtra(Downloader.EXTRA_TOTAL_BYTES, 0);
                    appUpdateStatusManager.updateApkProgress(urlString, totalBytes, bytesRead);
                } else if (Downloader.ACTION_COMPLETE.equals(action)) {
                    localBroadcastManager.unregisterReceiver(this);
                    File localFile = new File(intent.getStringExtra(Downloader.EXTRA_DOWNLOAD_PATH));
                    Uri localApkUri = Uri.fromFile(localFile);
                    Utils.debugLog(TAG, "OBB download completed " + intent.getDataString()
                            + " to " + localApkUri);

                    try {
                        if (Hasher.isFileMatchingHash(localFile, sha256, "SHA-256")) {
                            Utils.debugLog(TAG, "Installing OBB " + localFile + " to " + obbDestFile);
                            FileUtils.forceMkdirParent(obbDestFile);
                            FileUtils.copyFile(localFile, obbDestFile);
                            FileFilter filter = new WildcardFileFilter(
                                    obbDestFile.getName().substring(0, 4) + "*.obb");
                            for (File f : obbDestFile.getParentFile().listFiles(filter)) {
                                if (!f.equals(obbDestFile)) {
                                    Utils.debugLog(TAG, "Deleting obsolete OBB " + f);
                                    FileUtils.deleteQuietly(f);
                                }
                            }
                        } else {
                            Utils.debugLog(TAG, localFile + " deleted, did not match hash: " + sha256);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        FileUtils.deleteQuietly(localFile);
                    }
                } else if (Downloader.ACTION_INTERRUPTED.equals(action)) {
                    localBroadcastManager.unregisterReceiver(this);
                } else {
                    throw new RuntimeException("intent action not handled!");
                }
            }
        };
        DownloaderService.queue(this, obbUrlString);
        localBroadcastManager.registerReceiver(downloadReceiver,
                DownloaderService.getIntentFilter(obbUrlString));
    }

    private void registerApkDownloaderReceivers(String urlString) {

        BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Uri downloadUri = intent.getData();
                String urlString = downloadUri.toString();

                switch (intent.getAction()) {
                    case Downloader.ACTION_STARTED:
                        // App should currently be in the "Unknown" state, so this changes it to "Downloading".
                        Intent intentObject = new Intent(context, InstallManagerService.class);
                        intentObject.setAction(ACTION_CANCEL);
                        intentObject.setData(downloadUri);
                        PendingIntent action = PendingIntent.getService(context, 0, intentObject, 0);
                        appUpdateStatusManager.updateApk(urlString, AppUpdateStatusManager.Status.Downloading, action);
                        break;
                    case Downloader.ACTION_PROGRESS:
                        int bytesRead = intent.getIntExtra(Downloader.EXTRA_BYTES_READ, 0);
                        int totalBytes = intent.getIntExtra(Downloader.EXTRA_TOTAL_BYTES, 0);
                        appUpdateStatusManager.updateApkProgress(urlString, totalBytes, bytesRead);
                        break;
                    case Downloader.ACTION_COMPLETE:
                        File localFile = new File(intent.getStringExtra(Downloader.EXTRA_DOWNLOAD_PATH));
                        Uri localApkUri = Uri.fromFile(localFile);

                        Utils.debugLog(TAG, "download completed of " + urlString + " to " + localApkUri);
                        appUpdateStatusManager.updateApk(urlString, AppUpdateStatusManager.Status.ReadyToInstall, null);

                        localBroadcastManager.unregisterReceiver(this);
                        registerInstallerReceivers(downloadUri);

                        Apk apk = appUpdateStatusManager.getApk(urlString);
                        if (apk != null) {
                            InstallerService.install(context, localApkUri, downloadUri, apk);
                        }
                        break;
                    case Downloader.ACTION_INTERRUPTED:
                        appUpdateStatusManager.updateApk(urlString, AppUpdateStatusManager.Status.Unknown, null);
                        localBroadcastManager.unregisterReceiver(this);
                        break;
                    default:
                        throw new RuntimeException("intent action not handled!");
                }
            }
        };

        localBroadcastManager.registerReceiver(downloadReceiver,
                DownloaderService.getIntentFilter(urlString));
    }

    /**
     * For each app in the {@link AppUpdateStatusManager.Status#ReadyToInstall} state, setup listeners
     * so that if the user installs it then we can respond accordingly. This makes sure that whether
     * the user just finished downloading it, or whether they downloaded it a day ago but have not yet
     * installed it, we get the same experience upon completing an install.
     */
    private void registerInstallerReceiversForDownlaodedApks() {
        for (AppUpdateStatusManager.AppUpdateStatus appStatus : AppUpdateStatusManager.getInstance(this).getAll()) {
            if (appStatus.status == AppUpdateStatusManager.Status.ReadyToInstall) {
                registerInstallerReceivers(Uri.parse(appStatus.getUniqueKey()));
            }
        }
    }

    private void registerInstallerReceivers(Uri downloadUri) {

        BroadcastReceiver installReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String downloadUrl = intent.getDataString();
                Apk apk;
                switch (intent.getAction()) {
                    case Installer.ACTION_INSTALL_STARTED:
                        appUpdateStatusManager.updateApk(downloadUrl, AppUpdateStatusManager.Status.Installing, null);
                        break;
                    case Installer.ACTION_INSTALL_COMPLETE:
                        appUpdateStatusManager.updateApk(downloadUrl, AppUpdateStatusManager.Status.Installed, null);
                        Apk apkComplete =  appUpdateStatusManager.getApk(downloadUrl);
                        if (apkComplete != null) {
                            PackageManagerCompat.setInstaller(getPackageManager(), apkComplete.packageName);
                        }
                        localBroadcastManager.unregisterReceiver(this);
                        break;
                    case Installer.ACTION_INSTALL_INTERRUPTED:
                        apk = intent.getParcelableExtra(Installer.EXTRA_APK);
                        String errorMessage =
                                intent.getStringExtra(Installer.EXTRA_ERROR_MESSAGE);
                        if (!TextUtils.isEmpty(errorMessage)) {
                            appUpdateStatusManager.setApkError(apk, errorMessage);
                        } else {
                            appUpdateStatusManager.removeApk(downloadUrl);
                        }
                        localBroadcastManager.unregisterReceiver(this);
                        break;
                    case Installer.ACTION_INSTALL_USER_INTERACTION:
                        apk = intent.getParcelableExtra(Installer.EXTRA_APK);
                        PendingIntent installPendingIntent = intent.getParcelableExtra(Installer.EXTRA_USER_INTERACTION_PI);
                        appUpdateStatusManager.addApk(apk, AppUpdateStatusManager.Status.ReadyToInstall, installPendingIntent);
                        break;
                    default:
                        throw new RuntimeException("intent action not handled!");
                }
            }
        };

        localBroadcastManager.registerReceiver(installReceiver,
                Installer.getInstallIntentFilter(downloadUri));
    }

    /**
     * Install an APK, checking the cache and downloading if necessary before starting the process.
     * All notifications are sent as an {@link Intent} via local broadcasts to be received by
     *
     * @param context this app's {@link Context}
     */
    public static void queue(Context context, App app, Apk apk) {
        String urlString = apk.getUrl();
        Uri downloadUri = Uri.parse(urlString);
        Installer.sendBroadcastInstall(context, downloadUri, Installer.ACTION_INSTALL_STARTED, apk,
                null, null);
        Utils.debugLog(TAG, "queue " + app.packageName + " " + apk.versionCode + " from " + urlString);
        Intent intent = new Intent(context, InstallManagerService.class);
        intent.setAction(ACTION_INSTALL);
        intent.setData(downloadUri);
        intent.putExtra(EXTRA_APP, app);
        intent.putExtra(EXTRA_APK, apk);
        context.startService(intent);
    }

    public static void cancel(Context context, String urlString) {
        Intent intent = new Intent(context, InstallManagerService.class);
        intent.setAction(ACTION_CANCEL);
        intent.setData(Uri.parse(urlString));
        context.startService(intent);
    }

    public static void managePreviouslyDownloadedApks(Context context) {
        Intent intent = new Intent(context, InstallManagerService.class);
        intent.setAction(ACTION_MANAGE_DOWNLOADED_APKS);
        context.startService(intent);
    }
}
