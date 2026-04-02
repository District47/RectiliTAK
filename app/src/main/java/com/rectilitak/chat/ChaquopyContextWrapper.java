package com.rectilitak.chat;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;

import java.io.File;

/**
 * Bridges ATAK's application context with the plugin's asset context
 * for Chaquopy initialization.
 *
 * Chaquopy's AndroidPlatform does:
 *   mContext = (Application) context.getApplicationContext()
 *   am = mContext.getAssets()
 *   sp = mContext.getSharedPreferences(...)
 *
 * So getApplicationContext() must return an Application-like object
 * whose getAssets() returns the plugin's assets.
 */
public class ChaquopyContextWrapper extends ContextWrapper {

    private final ApplicationProxy appProxy;

    public ChaquopyContextWrapper(Context atakContext, Context pluginContext) {
        super(atakContext);
        Application realApp = (Application) atakContext.getApplicationContext();
        this.appProxy = new ApplicationProxy(realApp, pluginContext);
    }

    @Override
    public Context getApplicationContext() {
        return appProxy;
    }

    @Override
    public AssetManager getAssets() {
        return appProxy.getAssets();
    }

    /**
     * Wraps the real ATAK Application but overrides getAssets()
     * to return the plugin's assets (which contain chaquopy/build.json
     * and the Python stdlib).
     */
    private static class ApplicationProxy extends Application {
        private final Application realApp;
        private final Context pluginContext;

        ApplicationProxy(Application realApp, Context pluginContext) {
            this.realApp = realApp;
            this.pluginContext = pluginContext;
        }

        @Override
        public AssetManager getAssets() {
            return pluginContext.getAssets();
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return realApp.getSharedPreferences(name, mode);
        }

        @Override
        public File getFilesDir() {
            return realApp.getFilesDir();
        }

        @Override
        public File getCacheDir() {
            return realApp.getCacheDir();
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }

        @Override
        public String getPackageName() {
            return realApp.getPackageName();
        }

        @Override
        public File getDir(String name, int mode) {
            return realApp.getDir(name, mode);
        }

        @Override
        public ApplicationInfo getApplicationInfo() {
            return realApp.getApplicationInfo();
        }

        @Override
        public PackageManager getPackageManager() {
            return realApp.getPackageManager();
        }

        @Override
        public Resources getResources() {
            return realApp.getResources();
        }

        @Override
        public Object getSystemService(String name) {
            return realApp.getSystemService(name);
        }

        @Override
        public File getNoBackupFilesDir() {
            return realApp.getNoBackupFilesDir();
        }

        @Override
        public File getCodeCacheDir() {
            return realApp.getCodeCacheDir();
        }
    }
}
