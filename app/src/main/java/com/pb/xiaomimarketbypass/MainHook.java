package com.pb.xiaomimarketbypass;

import android.app.Application;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsetsController;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;

import dalvik.system.BaseDexClassLoader;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "XiaomiMarketBypass";
    private static final String MARKET_PACKAGE = "com.xiaomi.market";
    private static final String DOWNLOAD_PROVIDER_PACKAGE = "com.android.providers.downloads";
    private static final String DOWNLOAD_PROVIDER_PROCESS = "android.process.media";
    private static final Object PENDING_INSTALL_LOCK = new Object();
    private static final ArrayDeque<PendingInstallAction> PENDING_INSTALL_ACTIONS = new ArrayDeque<>();

    private static volatile Context appContext;
    private static int resumedActivityCount;
    private static boolean reconcileScheduled;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (DOWNLOAD_PROVIDER_PACKAGE.equals(lpparam.packageName)
                || DOWNLOAD_PROVIDER_PROCESS.equals(lpparam.processName)) {
            log("loaded DownloadProvider package=" + lpparam.packageName
                    + " process=" + lpparam.processName);
            hookAospDownloadProvider(lpparam.classLoader);
            return;
        }
        if (!MARKET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        log("loaded " + lpparam.packageName + " process=" + lpparam.processName);
        boolean mainProcess = lpparam.processName == null || MARKET_PACKAGE.equals(lpparam.processName);
        hookApplicationContext(lpparam.classLoader, mainProcess);
        hookXiaomiMarket(lpparam.classLoader);
    }

    private static void appendModuleApkToTargetClassLoader(ClassLoader targetClassLoader, Context context) {
        if (!(targetClassLoader instanceof BaseDexClassLoader) || context == null) {
            log("skip add module apk: loader=" + targetClassLoader + " context=" + context);
            return;
        }

        try {
            String modulePath = context.getPackageManager()
                    .getApplicationInfo("com.pb.xiaomimarketbypass", 0)
                    .sourceDir;
            XposedHelpers.callMethod(targetClassLoader, "addDexPath", modulePath);
            log("added module apk to target classloader: " + modulePath);
        } catch (Throwable t) {
            log("add module apk failed", t);
        }
    }

    private static void hookMissingClasses(ClassLoader targetClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    ClassLoader.class,
                    "loadClass",
                    String.class,
                    boolean.class,
                    new XC_MethodHook() {
                        
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Class<?> stub = getMiuiStubClass((String) param.args[0]);
                            if (stub != null) {
                                param.setResult(stub);
                            }
                        }
                    });
            log("hooked ClassLoader.loadClass for missing MIUI classes");
        } catch (Throwable t) {
            log("ClassLoader.loadClass hook failed", t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    BaseDexClassLoader.class,
                    "findClass",
                    String.class,
                    new XC_MethodHook() {
                        
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Class<?> stub = getMiuiStubClass((String) param.args[0]);
                            if (stub != null) {
                                param.setResult(stub);
                            }
                        }
                    });
            log("hooked BaseDexClassLoader.findClass for missing MIUI classes");
        } catch (Throwable t) {
            log("BaseDexClassLoader.findClass hook failed", t);
        }
    }

    private static Class<?> getMiuiStubClass(String className) {
        if ("android.provider.MiuiSettings$System".equals(className)) {
            return android.provider.MiuiSettings.System.class;
        }
        if ("miui.os.Build".equals(className)) {
            return miui.os.Build.class;
        }
        if ("miui.util.FeatureParser".equals(className)) {
            return miui.util.FeatureParser.class;
        }
        return null;
    }

    private static void hookApplicationContext(ClassLoader classLoader, boolean mainProcess) {
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Context attachContext = (Context) param.args[0];
                    appContext = attachContext.getApplicationContext() != null
                            ? attachContext.getApplicationContext()
                            : attachContext;
                    appendModuleApkToTargetClassLoader(attachContext.getClassLoader(), attachContext);
                    if (mainProcess) {
                        reconcileInstalledMarketDownloads(attachContext.getClassLoader(), appContext);
                    }
                    log("context attached");
                }
            });
            if (!mainProcess) {
                return;
            }
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    resumedActivityCount++;
                    Activity activity = (Activity) param.thisObject;
                    Context context = appContext != null ? appContext : activity;
                    flushDeferredInstallIntent(context);
                    reconcileInstalledMarketDownloads(activity.getClassLoader(), context);
                }
            });
            XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    resumedActivityCount = Math.max(0, resumedActivityCount - 1);
                }
            });
        } catch (Throwable t) {
            log("Application.attach hook failed", t);
        }
    }

    private static void hookXiaomiMarket(ClassLoader classLoader) {
        hookMissingClasses(classLoader);
        hookMissingMiuiPackages();
        hookMiuiBuild(classLoader);
        hookUiUtils(classLoader);
        hookMarketUiUtils(classLoader);
        hookElderChecker(classLoader);
        hookTmpfsFetcher(classLoader);
        hookDownloadManagerInfo(classLoader);
        hookDisableCompressedApkDownloads(classLoader);
        hookCompressedApkInstall(classLoader);
        hookSessionSplits(classLoader);
        hookPendingInstallAction(classLoader);
        hookBaselineExperiment(classLoader);
        hookAospDownloadControls(classLoader);
        hookMissingFrameworkMiuiMethods(classLoader);
        hookMissingMarketIdentityProvider(classLoader);
        hookMissingMarketStorageMethod(classLoader);
        hookMissingMiuiPreloadPolicy(classLoader);
        hookMissingMiPushNotificationMethod(classLoader);
        hookMissingMiuiHybrid(classLoader);
        hookBlockedMarketSecureSettings(classLoader);
        hookOptionalMiuiReflectionNoise(classLoader);
    }

    private static void hookMissingMiuiPackages() {
        try {
            Class<?> apmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", null);
            XposedHelpers.findAndHookMethod(apmClass, "getPackageInfo", String.class, int.class,
                    new MissingMiuiPackageInfoHook());
            XposedHelpers.findAndHookMethod(apmClass, "getPackageInfoAsUser", String.class, int.class, int.class,
                    new MissingMiuiPackageInfoHook());
            XposedHelpers.findAndHookMethod(apmClass, "getApplicationInfo", String.class, int.class,
                    new MissingMiuiApplicationInfoHook());
            XposedHelpers.findAndHookMethod(apmClass, "getApplicationInfoAsUser", String.class, int.class, int.class,
                    new MissingMiuiApplicationInfoHook());
            hookFlagPackageQueries(apmClass);
            log("hooked missing MIUI package info");
        } catch (Throwable t) {
            log("missing MIUI package info hook failed", t);
        }
    }

    private static void hookFlagPackageQueries(Class<?> apmClass) {
        try {
            Class<?> packageInfoFlags = XposedHelpers.findClass(
                    "android.content.pm.PackageManager$PackageInfoFlags", null);
            XposedHelpers.findAndHookMethod(apmClass, "getPackageInfo", String.class, packageInfoFlags,
                    new MissingMiuiPackageInfoHook());
        } catch (Throwable t) {
            log("PackageInfoFlags hook skipped", t);
        }

        try {
            Class<?> applicationInfoFlags = XposedHelpers.findClass(
                    "android.content.pm.PackageManager$ApplicationInfoFlags", null);
            XposedHelpers.findAndHookMethod(apmClass, "getApplicationInfo", String.class, applicationInfoFlags,
                    new MissingMiuiApplicationInfoHook());
        } catch (Throwable t) {
            log("ApplicationInfoFlags hook skipped", t);
        }
    }

    private static class MissingMiuiPackageInfoHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            String packageName = (String) param.args[0];
            if (isStubbedMiuiPackage(packageName)) {
                param.setResult(makePackageInfo(packageName));
            }
        }
    }

    private static class MissingMiuiApplicationInfoHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            String packageName = (String) param.args[0];
            if (isStubbedMiuiPackage(packageName)) {
                param.setResult(makeApplicationInfo(packageName));
            }
        }
    }

    private static boolean isStubbedMiuiPackage(String packageName) {
        return "com.miui.packageinstaller".equals(packageName)
                || "com.miui.hybrid".equals(packageName);
    }

    private static ApplicationInfo makeApplicationInfo(String packageName) {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = packageName;
        appInfo.enabled = true;
        return appInfo;
    }

    private static PackageInfo makePackageInfo(String packageName) {
        PackageInfo info = new PackageInfo();
        info.packageName = packageName;
        info.applicationInfo = makeApplicationInfo(packageName);
        info.versionName = "1";
        info.versionCode = 1;
        return info;
    }

    private static void hookDisableCompressedApkDownloads(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.business_core.downloadinstall.data.DownloadSplitInfo",
                    classLoader,
                    "canUseCompress",
                    XC_MethodReplacement.returnConstant(false));
            log("hooked DownloadSplitInfo.canUseCompress=false");
        } catch (Throwable t) {
            log("DownloadSplitInfo.canUseCompress hook failed", t);
        }
    }

    private static void hookDownloadManagerInfo(ClassLoader classLoader) {
        try {
            Class<?> infoClass = XposedHelpers.findClass(
                    "com.xiaomi.market.business_core.downloadinstall.data.DownloadManagerInfo",
                    classLoader);
            XposedHelpers.findAndHookMethod(
                    infoClass,
                    "query",
                    Cursor.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                            return parseDownloadManagerInfo(infoClass, (Cursor) param.args[0]);
                        }
                    });
            log("hooked DownloadManagerInfo.query without local_filename");
        } catch (Throwable t) {
            log("DownloadManagerInfo.query hook failed", t);
        }
    }

    private static Object parseDownloadManagerInfo(Class<?> infoClass, Cursor cursor) throws Throwable {
        long id = getLong(cursor, "_id", 0L);
        Object info = XposedHelpers.newInstance(infoClass);
        XposedHelpers.setLongField(info, "id", id);
        XposedHelpers.setIntField(info, "status", getInt(cursor, "status", 0));
        XposedHelpers.setIntField(info, "reason", getInt(cursor, "reason", 0));
        XposedHelpers.setLongField(info, "currBytes", getLong(cursor, "bytes_so_far", 0L));
        XposedHelpers.setLongField(info, "totalBytes", getLong(cursor, "total_size", 0L));
        String hint = getString(cursor, "hint", null);
        String downloadFilePath = decodeFilePath(firstNonEmpty(
                getString(cursor, "_data", null),
                getString(cursor, "local_filename", null),
                fileUriToPath(hint)));
        XposedHelpers.setObjectField(info, "localUri", firstNonEmpty(getString(cursor, "local_uri", null), hint));
        XposedHelpers.setObjectField(info, "downloadFilePath", downloadFilePath);
        XposedHelpers.setObjectField(info, "title", getString(cursor, "title", null));
        XposedHelpers.setObjectField(info, "description", getString(cursor, "description", null));
        XposedHelpers.setObjectField(info, "hint", hint);
        XposedHelpers.setObjectField(info, "downloadUrl", getString(cursor, "uri", null));
        if (getInt(cursor, "status", 0) == 16) {
            XposedHelpers.setObjectField(info, "errorMsg", getString(cursor, "errorMsg", null));
        }
        return info;
    }

    private static int getInt(Cursor cursor, String column, int fallback) {
        try {
            int index = cursor.getColumnIndex(column);
            return index >= 0 ? cursor.getInt(index) : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static long getLong(Cursor cursor, String column, long fallback) {
        try {
            int index = cursor.getColumnIndex(column);
            return index >= 0 ? cursor.getLong(index) : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static String getString(Cursor cursor, String column, String fallback) {
        try {
            int index = cursor.getColumnIndex(column);
            return index >= 0 ? cursor.getString(index) : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isEmpty() && !"placeholder".equals(value)) {
                return value;
            }
        }
        return null;
    }

    private static String fileUriToPath(String value) {
        if (value == null) {
            return null;
        }
        if (value.startsWith("file://")) {
            return value.substring("file://".length());
        }
        return value;
    }

    private static String decodeFilePath(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Uri.decode(value);
        } catch (Throwable t) {
            return value;
        }
    }

    private static void hookCompressedApkInstall(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.business_core.downloadinstall.data.DownloadSplitInfo$BaseModuleProxy",
                    classLoader,
                    "shouldSkipDecompressInstall",
                    XC_MethodReplacement.returnConstant(true));
            log("hooked DownloadSplitInfo.BaseModuleProxy.shouldSkipDecompressInstall=true");
        } catch (Throwable t) {
            log("DownloadSplitInfo.shouldSkipDecompressInstall hook failed", t);
        }
    }

    private static void hookSessionSplits(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.business_core.downloadinstall.data.DownloadInstallInfo$SessionHelper",
                    classLoader,
                    "getSessionSplits",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object result = param.getResult();
                            if (!(result instanceof ArrayList<?>)) {
                                return;
                            }
                            rewriteCompressedSessionSplits(classLoader, (ArrayList<?>) result);
                        }
                    });
            log("hooked DownloadInstallInfo.SessionHelper.getSessionSplits");
        } catch (Throwable t) {
            log("SessionHelper.getSessionSplits hook failed", t);
        }
    }

    private static void rewriteCompressedSessionSplits(ClassLoader classLoader, ArrayList<?> splits) {
        for (Object split : splits) {
            if (split == null) {
                continue;
            }
            try {
                String path = (String) XposedHelpers.getObjectField(split, "sessionPath");
                if (path == null || !path.endsWith(".zst")) {
                    continue;
                }

                String apkPath = path.substring(0, path.length() - ".zst".length());
                File apkFile = new File(apkPath);
                if (!apkFile.isFile() || apkFile.length() == 0L) {
                    if (apkFile.exists() && !apkFile.delete()) {
                        log("could not remove invalid decompressed apk: " + apkPath);
                        continue;
                    }
                    String tmpPath = apkPath + ".tmp";
                    new File(tmpPath).delete();
                    Object decompressor = XposedHelpers.getStaticObjectField(
                            XposedHelpers.findClass("com.xiaomi.market.common.utils.DecompressUtils", classLoader),
                            "Companion");
                    Object error = XposedHelpers.callMethod(decompressor, "decompressApkFile", 1, path, tmpPath);
                    if (error != null) {
                        log("zstd decompress failed for " + path + ": " + error);
                        new File(tmpPath).delete();
                        continue;
                    }
                    File tmpFile = new File(tmpPath);
                    if (!tmpFile.isFile() || tmpFile.length() == 0L) {
                        log("zstd decompressor produced an empty output: " + tmpPath);
                        tmpFile.delete();
                        continue;
                    }
                    if (!tmpFile.renameTo(apkFile)) {
                        log("zstd decompress rename failed: " + tmpPath + " -> " + apkPath);
                        tmpFile.delete();
                        continue;
                    }
                }

                long apkBytes = apkFile.length();
                XposedHelpers.setObjectField(split, "sessionPath", apkPath);
                XposedHelpers.setBooleanField(split, "useDecompressLater", false);
                XposedHelpers.setLongField(split, "sessionBytes", 0L);
                XposedHelpers.setLongField(split, "lastUpdateBytes", 0L);
                File compressedFile = new File(path);
                if (compressedFile.exists() && !compressedFile.delete()) {
                    log("could not remove decompressed zstd source: " + path);
                }
                log("rewrote compressed session split to apk: " + apkPath + " bytes=" + apkBytes);
            } catch (Throwable t) {
                log("rewrite compressed session split failed", t);
            }
        }
    }

    private static void hookPendingInstallAction(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.business_core.downloadinstall.install.SessionInstallReceiver",
                    classLoader,
                    "handleSessionInstallByNormal",
                    Intent.class,
                    String.class,
                    int.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int status = (Integer) param.args[2];
                            if (status != -1) {
                                return;
                            }
                            Intent resultIntent = (Intent) param.args[0];
                            Intent pending = getPendingUserActionIntent(resultIntent);
                            Context context = appContext;
                            if (pending == null || context == null) {
                                log("pending install action missing: pending=" + pending + " context=" + context);
                                return;
                            }
                            pending.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            String packageName = (String) param.args[1];
                            if (resumedActivityCount > 0 || isMarketProcessForeground(context)) {
                                try {
                                    context.startActivity(pending);
                                    log("started PackageInstaller pending user action for " + packageName);
                                } catch (Throwable t) {
                                    deferInstallAction(pending, packageName);
                                    log("PackageInstaller pending action launch failed; deferred " + packageName, t);
                                }
                            } else {
                                deferInstallAction(pending, packageName);
                                log("deferred PackageInstaller pending user action for " + packageName);
                            }
                            param.setResult(null);
                        }
                    });
            log("hooked SessionInstallReceiver pending install action");
        } catch (Throwable t) {
            log("SessionInstallReceiver pending action hook failed", t);
        }
    }

    private static void hookBaselineExperiment(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.common.analytics.onetrack.ExperimentManager$Companion",
                    classLoader,
                    "disableOrEnableBaseLine",
                    XC_MethodReplacement.DO_NOTHING);
            log("hooked ExperimentManager.disableOrEnableBaseLine");
        } catch (Throwable t) {
            log("ExperimentManager.disableOrEnableBaseLine hook failed", t);
        }
    }

    private static void hookAospDownloadControls(ClassLoader classLoader) {
        try {
            Class<?> compatClass = XposedHelpers.findClass(
                    "com.xiaomi.market.common.compat.DownloadManagerCompat",
                    classLoader);
            XposedHelpers.findAndHookMethod(compatClass, "isPauseAndResumeSupported",
                    XC_MethodReplacement.returnConstant(true));
            XposedHelpers.findAndHookMethod(compatClass, "isPauseAndResumeSupported", boolean.class,
                    XC_MethodReplacement.returnConstant(true));
            XposedHelpers.findAndHookMethod(compatClass, "pause", long.class, int.class,
                    new AospDownloadControlHook(true));
            XposedHelpers.findAndHookMethod(compatClass, "resume", long.class, int.class,
                    new AospDownloadControlHook(false));
            log("hooked AOSP DownloadProvider pause/resume controls");
        } catch (Throwable t) {
            log("AOSP DownloadProvider controls hook failed", t);
        }
    }

    private static void hookAospDownloadProvider(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.providers.downloads.DownloadProvider",
                    classLoader,
                    "update",
                    Uri.class,
                    ContentValues.class,
                    String.class,
                    String[].class,
                    new XC_MethodHook() {
                        private final ThreadLocal<Boolean> internalUpdate = new ThreadLocal<>();

                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            if (Boolean.TRUE.equals(internalUpdate.get())) {
                                return;
                            }

                            ContentValues values = (ContentValues) param.args[1];
                            Integer status = values != null ? values.getAsInteger("status") : null;
                            if ((status == null || (status != 190 && status != 197))
                                    || !isCallerXiaomiMarket((Context) XposedHelpers.callMethod(
                                            param.thisObject, "getContext"))) {
                                return;
                            }

                            long token = Binder.clearCallingIdentity();
                            internalUpdate.set(true);
                            try {
                                Object result = XposedBridge.invokeOriginalMethod(
                                        param.method, param.thisObject, param.args);
                                param.setResult(result);
                                log("allowed Xiaomi Market DownloadProvider status=" + status
                                        + " uri=" + param.args[0] + " updated=" + result);
                            } finally {
                                internalUpdate.remove();
                                Binder.restoreCallingIdentity(token);
                            }
                        }
                    });
            log("hooked AOSP DownloadProvider status transitions");
        } catch (Throwable t) {
            log("AOSP DownloadProvider status hook failed", t);
        }
    }

    private static boolean isCallerXiaomiMarket(Context context) {
        if (context == null) {
            return false;
        }
        String[] packages = context.getPackageManager().getPackagesForUid(Binder.getCallingUid());
        if (packages == null) {
            return false;
        }
        for (String packageName : packages) {
            if (MARKET_PACKAGE.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private static void hookMissingFrameworkMiuiMethods(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.util.ReflectUtils",
                    classLoader,
                    "getMethod",
                    Class.class,
                    String.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Class<?> targetClass = (Class<?>) param.args[0];
                            String methodName = (String) param.args[1];
                            if (targetClass == android.app.DownloadManager.class
                                    && "setAccessFilename".equals(methodName)) {
                                param.setResult(null);
                                return;
                            }
                            if (targetClass == android.telephony.TelephonyManager.class
                                    && "getBitMaskForNetworkType".equals(methodName)) {
                                param.setResult(null);
                                return;
                            }
                            if (targetClass != null
                                    && "com.android.internal.policy.PhoneWindow".equals(targetClass.getName())
                                    && "addExtraFlags".equals(methodName)) {
                                param.setResult(null);
                            }
                        }
                    });
            log("hooked missing MIUI framework method lookups");
        } catch (Throwable t) {
            log("missing MIUI framework method lookup hook failed", t);
        }
    }

    private static void hookMissingMarketIdentityProvider(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.util.Client",
                    classLoader,
                    "getOaidFromIdProvider",
                    XC_MethodReplacement.returnConstant(""));
            log("hooked missing IdentifierManager OAID provider");
        } catch (Throwable t) {
            log("IdentifierManager OAID hook failed", t);
        }
    }

    private static void hookMissingMarketStorageMethod(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "e7.i",
                    classLoader,
                    "a",
                    Object.class,
                    String.class,
                    Class[].class,
                    Object[].class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if ("getPrimaryStorageSize".equals(param.args[1])) {
                                param.setResult(Long.valueOf(android.os.Environment.getDataDirectory().getTotalSpace()));
                                return;
                            }
                            if ("getEUASupportSize".equals(param.args[1])) {
                                param.setResult(Long.valueOf(0L));
                            }
                        }
                    });
            log("hooked missing StorageManager size methods");
        } catch (Throwable t) {
            log("StorageManager size method hook failed", t);
        }
    }

    private static void hookMissingMiuiPreloadPolicy(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.common.compat.PackageManagerCompat",
                    classLoader,
                    "isAllowDeleteSourceApp",
                    XC_MethodReplacement.returnConstant(false));
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.common.compat.PackageManagerCompat",
                    classLoader,
                    "isProtectedDataApp",
                    String.class,
                    XC_MethodReplacement.returnConstant(false));
            log("hooked missing PreloadedAppPolicy fallbacks");
        } catch (Throwable t) {
            log("PreloadedAppPolicy fallback hook failed", t);
        }
    }

    private static void hookMissingMiPushNotificationMethod(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.push.bj",
                    classLoader,
                    "a",
                    Object.class,
                    String.class,
                    Object[].class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args[0] instanceof android.app.NotificationManager
                                    && "isSystemConditionProviderEnabled".equals(param.args[1])) {
                                param.setResult(Boolean.FALSE);
                            }
                        }
                    });
            log("hooked missing MiPush notification condition provider check");
        } catch (Throwable t) {
            log("MiPush notification condition provider hook failed", t);
        }
    }

    private static void hookMissingMiuiHybrid(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.common.webview.MinaInterface",
                    classLoader,
                    "ensureInit",
                    XC_MethodReplacement.DO_NOTHING);
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.common.webview.MinaInterface",
                    classLoader,
                    "initData",
                    XC_MethodReplacement.DO_NOTHING);
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.common.webview.MinaInterface",
                    classLoader,
                    "checkIsSupportRpkInstall",
                    XC_MethodReplacement.DO_NOTHING);
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.common.webview.MinaInterface",
                    classLoader,
                    "getVersionCode",
                    XC_MethodReplacement.returnConstant(-1));
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.common.webview.MinaInterface",
                    classLoader,
                    "isInstalled",
                    XC_MethodReplacement.returnConstant(false));
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.common.webview.MinaInterface",
                    classLoader,
                    "isDisabled",
                    XC_MethodReplacement.returnConstant(true));
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.agent.AgentUtils",
                    classLoader,
                    "init",
                    XC_MethodReplacement.DO_NOTHING);
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.agent.AgentUtils",
                    classLoader,
                    "isSupportAgent",
                    boolean.class,
                    XC_MethodReplacement.returnConstant(false));
            log("hooked missing Miui Hybrid/Mina bridge");
        } catch (Throwable t) {
            log("Miui Hybrid/Mina bridge hook failed", t);
        }
    }

    private static void hookBlockedMarketSecureSettings(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.data.SystemSettingsManager",
                    classLoader,
                    "putMarketNeedUpdateAppCount",
                    int.class,
                    XC_MethodReplacement.DO_NOTHING);
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.data.SystemSettingsManager",
                    classLoader,
                    "putMarketNeedUpdateGameCount",
                    int.class,
                    XC_MethodReplacement.DO_NOTHING);
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.data.SystemSettingsManager",
                    classLoader,
                    "setMarketActivePushRecord",
                    XC_MethodReplacement.DO_NOTHING);
            log("hooked blocked Market Settings.Secure writes");
        } catch (Throwable t) {
            log("Market Settings.Secure write hook failed", t);
        }
    }

    private static void hookOptionalMiuiReflectionNoise(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.util.ReflectUtils",
                    classLoader,
                    "getFieldValue",
                    Class.class,
                    Object.class,
                    String.class,
                    new OptionalMiuiFieldValueHook());
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.util.ReflectUtils",
                    classLoader,
                    "getFieldValue",
                    Class.class,
                    Object.class,
                    String.class,
                    String.class,
                    new OptionalMiuiFieldValueHook());
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.common.compat.PackageManagerCompat",
                    classLoader,
                    "isInCompatibleTipsSupport",
                    XC_MethodReplacement.returnConstant(false));
            log("hooked optional MIUI reflection noise");
        } catch (Throwable t) {
            log("optional MIUI reflection noise hook failed", t);
        }
    }

    private static class OptionalMiuiFieldValueHook extends XC_MethodHook {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Class<?> targetClass = (Class<?>) param.args[0];
            String fieldName = (String) param.args[2];
            if (targetClass == android.app.Notification.class
                    && "extraNotification".equals(fieldName)) {
                param.setResult(null);
                return;
            }
            if (targetClass != null
                    && "com.android.org.bouncycastle.jce.provider.BouncyCastleProvider"
                            .equals(targetClass.getName())
                    && "PROVIDER_NAME".equals(fieldName)) {
                param.setResult("BC");
            }
        }
    }

    private static class AospDownloadControlHook extends XC_MethodHook {
        private final boolean pause;

        AospDownloadControlHook(boolean pause) {
            this.pause = pause;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            int engineType = (Integer) param.args[1];
            if (engineType == 1 || engineType == 4) {
                return;
            }

            long downloadId = (Long) param.args[0];
            if (setAospDownloadPaused(downloadId, pause)) {
                param.setResult(null);
            }
        }
    }

    private static boolean setAospDownloadPaused(long downloadId, boolean pause) {
        Context context = appContext;
        if (context == null || downloadId <= 0L) {
            log("skip AOSP download control: context=" + context + " id=" + downloadId);
            return false;
        }

        try {
            ContentValues values = new ContentValues();
            values.put("control", pause ? 1 : 0);
            // DownloadProvider only schedules a new job for STATUS_PENDING (190).
            // STATUS_RUNNING (192) changes the row but leaves a paused job canceled.
            values.put("status", pause ? 197 : 190);
            if (!pause) {
                values.put("allowed_network_types", -1);
            }
            Uri uri = Uri.parse("content://downloads/my_downloads/" + downloadId);
            int updated = context.getContentResolver().update(uri, values, null, null);
            log((pause ? "paused" : "resumed") + " AOSP download id=" + downloadId + " updated=" + updated);
            return updated > 0;
        } catch (Throwable t) {
            log("AOSP download control failed id=" + downloadId + " pause=" + pause, t);
            return false;
        }
    }

    private static void reconcileInstalledMarketDownloads(ClassLoader classLoader, Context context) {
        if (context == null) {
            return;
        }

        synchronized (MainHook.class) {
            if (reconcileScheduled) {
                return;
            }
            reconcileScheduled = true;
        }

        boolean posted = new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Class<?> infoClass = XposedHelpers.findClass(
                        "com.xiaomi.market.business_core.downloadinstall.data.DownloadInstallInfo",
                        classLoader);
                Object all = XposedHelpers.callStaticMethod(infoClass, "getAll");
                if (!(all instanceof Iterable<?>)) {
                    return;
                }

                for (Object info : (Iterable<?>) all) {
                    reconcileInstalledMarketDownload(classLoader, context, info);
                }
            } catch (Throwable t) {
                log("installed download reconcile scan failed", t);
            } finally {
                synchronized (MainHook.class) {
                    reconcileScheduled = false;
                }
            }
        }, 3000L);
        if (!posted) {
            synchronized (MainHook.class) {
                reconcileScheduled = false;
            }
        }
    }

    private static void reconcileInstalledMarketDownload(ClassLoader classLoader, Context context, Object info) {
        if (info == null) {
            return;
        }

        try {
            String packageName = (String) XposedHelpers.getObjectField(info, "packageName");
            int state = XposedHelpers.getIntField(info, "state");
            boolean committed = XposedHelpers.getBooleanField(info, "isSessionCommitted");
            if (packageName == null || state != -4 || !committed || !isPackageInstalled(context, packageName)) {
                retryCommittedUninstalledMarketDownload(classLoader, context, info, packageName, state, committed);
                return;
            }

            reconcileSuccessfulMarketInstall(classLoader, packageName, "installed package found after committed session");
            log("reconciled committed installed Market task for " + packageName);
        } catch (Throwable t) {
            log("installed download reconcile item failed", t);
        }
    }

    private static void retryCommittedUninstalledMarketDownload(ClassLoader classLoader, Context context, Object info,
            String packageName, int state, boolean committed) {
        if (packageName == null || state != -4 || !committed || isPackageInstalled(context, packageName)) {
            return;
        }

        try {
            long installTime = XposedHelpers.getLongField(info, "installTime");
            if (installTime > 0L && System.currentTimeMillis() - installTime < 15000L) {
                return;
            }

            XposedHelpers.callMethod(info, "resetSessionState");
            Object installManager = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(
                            "com.xiaomi.market.business_core.downloadinstall.install.InstallManager",
                            classLoader),
                    "getManager");
            XposedHelpers.callMethod(installManager, "retryInstall", info);
            log("retried committed but uninstalled Market task for " + packageName);
        } catch (Throwable t) {
            log("committed uninstalled retry failed for " + packageName, t);
        }
    }

    private static void flushDeferredInstallIntent(Context context) {
        if (context == null) {
            return;
        }

        PendingInstallAction action;
        synchronized (PENDING_INSTALL_LOCK) {
            action = PENDING_INSTALL_ACTIONS.pollFirst();
        }
        if (action == null) {
            return;
        }

        try {
            action.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(action.intent);
            log("started deferred PackageInstaller pending user action for " + action.packageName);
        } catch (Throwable t) {
            synchronized (PENDING_INSTALL_LOCK) {
                PENDING_INSTALL_ACTIONS.addFirst(action);
            }
            log("deferred PackageInstaller launch failed for " + action.packageName, t);
        }
    }

    private static Intent getPendingUserActionIntent(Intent resultIntent) {
        if (resultIntent == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return resultIntent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        }
        return resultIntent.getParcelableExtra(Intent.EXTRA_INTENT);
    }

    private static void deferInstallAction(Intent intent, String packageName) {
        PendingInstallAction action = new PendingInstallAction(new Intent(intent), packageName);
        synchronized (PENDING_INSTALL_LOCK) {
            if (packageName != null) {
                Iterator<PendingInstallAction> iterator = PENDING_INSTALL_ACTIONS.iterator();
                while (iterator.hasNext()) {
                    if (packageName.equals(iterator.next().packageName)) {
                        iterator.remove();
                    }
                }
            }
            PENDING_INSTALL_ACTIONS.addLast(action);
        }
    }

    private static final class PendingInstallAction {
        final Intent intent;
        final String packageName;

        PendingInstallAction(Intent intent, String packageName) {
            this.intent = intent;
            this.packageName = packageName;
        }
    }

    private static boolean isMarketProcessForeground(Context context) {
        try {
            ActivityManager activityManager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (activityManager == null) {
                return false;
            }
            int pid = android.os.Process.myPid();
            java.util.List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
            if (processes == null) {
                return false;
            }
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (process.pid == pid) {
                    return process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                }
            }
        } catch (Throwable t) {
            log("foreground check failed", t);
        }
        return false;
    }

    private static boolean isPackageInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            log("package install check failed for " + packageName, t);
            return false;
        }
    }

    private static void reconcileSuccessfulMarketInstall(ClassLoader classLoader, String packageName,
            String statusMessage) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }

        try {
            Class<?> infoClass = XposedHelpers.findClass(
                    "com.xiaomi.market.business_core.downloadinstall.data.DownloadInstallInfo",
                    classLoader);
            Object info = XposedHelpers.callStaticMethod(infoClass, "get", packageName);
            if (info == null) {
                log("skip install-success reconcile, no DownloadInstallInfo for " + packageName);
                return;
            }

            Class<?> observerClass = XposedHelpers.findClass(
                    "com.xiaomi.market.business_core.downloadinstall.install.PackageInstallObserver",
                    classLoader);
            Object observer = XposedHelpers.callStaticMethod(observerClass, "get", info, false);
            if (observer == null) {
                log("skip install-success reconcile, no observer for " + packageName);
                return;
            }

            XposedHelpers.callMethod(observer, "packageInstalled", packageName, 1, statusMessage);
            log("reconciled Market install success for " + packageName);
        } catch (Throwable t) {
            log("install-success reconcile failed for " + packageName, t);
        }
    }

    private static void hookMarketUiUtils(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.util.UIUtils",
                    classLoader,
                    "setStatusBarDarkMode",
                    Activity.class,
                    boolean.class,
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            Activity activity = (Activity) param.args[0];
                            boolean darkMode = (Boolean) param.args[1];
                            applyAospStatusBarMode(activity, darkMode);
                            return null;
                        }
                    });
            log("hooked UIUtils.setStatusBarDarkMode");
        } catch (Throwable t) {
            log("UIUtils.setStatusBarDarkMode hook failed", t);
        }
    }

    private static void applyAospStatusBarMode(Activity activity, boolean darkMode) {
        if (activity == null || activity.getWindow() == null) {
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsetsController controller = activity.getWindow().getInsetsController();
                if (controller != null) {
                    int lightStatusBar = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
                    controller.setSystemBarsAppearance(darkMode ? 0 : lightStatusBar, lightStatusBar);
                    return;
                }
            }
            View decorView = activity.getWindow().getDecorView();
            int flags = decorView.getSystemUiVisibility();
            if (darkMode) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            decorView.setSystemUiVisibility(flags);
        } catch (Throwable t) {
            log("applyAospStatusBarMode failed", t);
        }
    }

    private static void hookUiUtils(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.common.utils.UIUtils$Companion",
                    classLoader,
                    "getSystemFontSize",
                    XC_MethodReplacement.returnConstant("medium"));
            log("hooked UIUtils.getSystemFontSize=medium");
        } catch (Throwable t) {
            log("UIUtils.getSystemFontSize hook failed", t);
        }
    }

    private static void hookMiuiBuild(ClassLoader classLoader) {
        hookMiuiBuildBoolean(classLoader, "isAlphaBuild", false);
        hookMiuiBuildBoolean(classLoader, "isCtaBuild", false);
        hookMiuiBuildBoolean(classLoader, "isCtsBuild", false);
        hookMiuiBuildBoolean(classLoader, "isDevelopmentVersion", false);
        hookMiuiBuildBoolean(classLoader, "isInternationalBuild", false);
        hookMiuiBuildBoolean(classLoader, "isStableVersion", true);
        hookMiuiBuildBoolean(classLoader, "isTablet", false);
    }

    private static void hookMiuiBuildBoolean(ClassLoader classLoader, String methodName, boolean value) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.util.MiuiBuild",
                    classLoader,
                    methodName,
                    XC_MethodReplacement.returnConstant(value));
            log("hooked MiuiBuild." + methodName + "=" + value);
        } catch (Throwable t) {
            log("MiuiBuild." + methodName + " hook failed", t);
        }
    }

    private static void hookElderChecker(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.util.ElderChecker",
                    classLoader,
                    "isSystemElderMode",
                    XC_MethodReplacement.returnConstant(false));
            log("hooked ElderChecker.isSystemElderMode");
        } catch (Throwable t) {
            log("ElderChecker.isSystemElderMode hook failed", t);
        }

        try {
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.util.ElderChecker",
                    classLoader,
                    "isElderMode",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            return readSystemBoolean("elderly_mode", false);
                        }
                    });
            log("hooked ElderChecker.isElderMode");
        } catch (Throwable t) {
            log("ElderChecker.isElderMode hook failed", t);
        }
    }

    private static void hookTmpfsFetcher(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.xiaomi.market.util.Client$21",
                    classLoader,
                    "doFetch",
                    new XC_MethodReplacement() {
                        @Override
                        protected Object replaceHookedMethod(MethodHookParam param) {
                            return readSystemString("miui_support_tmpfs", "");
                        }
                    });
            log("hooked Client tmpfsSupport fetcher");
        } catch (Throwable t) {
            log("Client tmpfsSupport hook failed", t);
        }
    }

    private static boolean readSystemBoolean(String key, boolean fallback) {
        Context context = appContext;
        if (context == null) {
            return fallback;
        }

        try {
            return Settings.System.getInt(context.getContentResolver(), key, fallback ? 1 : 0) != 0;
        } catch (Throwable t) {
            log("read boolean failed key=" + key, t);
            return fallback;
        }
    }

    private static String readSystemString(String key, String fallback) {
        Context context = appContext;
        if (context == null) {
            return fallback;
        }

        try {
            String value = Settings.System.getString(context.getContentResolver(), key);
            return value != null ? value : fallback;
        } catch (Throwable t) {
            log("read string failed key=" + key, t);
            return fallback;
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    private static void log(String message, Throwable t) {
        XposedBridge.log(TAG + ": " + message);
        XposedBridge.log(t);
    }
}
