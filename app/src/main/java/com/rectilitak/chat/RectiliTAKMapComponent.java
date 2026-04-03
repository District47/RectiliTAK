package com.rectilitak.chat;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;
import com.atakmap.android.maps.AbstractMapComponent;
import com.atakmap.android.maps.MapView;
import com.atakmap.app.preferences.ToolsPreferenceFragment;

import com.rectilitak.chat.plugin.R;

public class RectiliTAKMapComponent extends AbstractMapComponent {

    public static final String TAG = "RectiliTAKMapComponent";
    public static final String SHOW_CHAT = "com.rectilitak.chat.SHOW_CHAT";
    private static final String PREFS_KEY = "rectilitak_preferences";

    private Context pluginContext;
    private MainPanelDropDown mainPanel;

    @Override
    public void onCreate(Context context, Intent intent, MapView view) {
        context.setTheme(R.style.ATAKPluginTheme);
        pluginContext = context;

        // Register the main panel drop-down (bridge starts lazily on first open)
        mainPanel = new MainPanelDropDown(view, context);
        this.registerReceiver(view.getContext(), mainPanel,
                new DocumentedIntentFilter(SHOW_CHAT));
        Log.d(TAG, "Main panel registered");

        // Register preferences
        RectiliTAKPreferenceFragment preferenceFragment =
                new RectiliTAKPreferenceFragment(pluginContext);
        ToolsPreferenceFragment.register(
                new ToolsPreferenceFragment.ToolPreference(
                        "RectiliTAK Settings",
                        "Configure Reticulum mesh chat settings",
                        PREFS_KEY,
                        context.getResources().getDrawable(R.drawable.rectilitak_icon),
                        preferenceFragment));
        Log.d(TAG, "Preferences registered");
    }

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        ToolsPreferenceFragment.unregister(PREFS_KEY);
        if (mainPanel != null) {
            mainPanel.dispose();
        }
        RNSBridgeService.Companion.stop();
        Log.d(TAG, "RNS bridge stopped");
    }

    @Override
    public void onStart(Context context, MapView view) {
    }

    @Override
    public void onStop(Context context, MapView view) {
    }

    @Override
    public void onPause(Context context, MapView view) {
    }

    @Override
    public void onResume(Context context, MapView view) {
    }
}
