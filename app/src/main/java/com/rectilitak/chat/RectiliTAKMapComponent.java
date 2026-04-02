package com.rectilitak.chat;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.atakmap.android.maps.AbstractMapComponent;
import com.atakmap.android.maps.MapView;

import com.rectilitak.chat.plugin.R;

public class RectiliTAKMapComponent extends AbstractMapComponent {

    public static final String TAG = "RectiliTAKMapComponent";
    public static final String SHOW_CHAT = "com.rectilitak.chat.SHOW_CHAT";

    private Context pluginContext;
    private Intent bridgeServiceIntent;

    @Override
    public void onCreate(Context context, Intent intent, MapView view) {
        context.setTheme(R.style.ATAKPluginTheme);
        pluginContext = context;

        // Start the RNS bridge service
        bridgeServiceIntent = new Intent(view.getContext(), RNSBridgeService.class);
        view.getContext().startService(bridgeServiceIntent);
        Log.d(TAG, "RNS bridge service started");
    }

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        if (bridgeServiceIntent != null) {
            view.getContext().stopService(bridgeServiceIntent);
            Log.d(TAG, "RNS bridge service stopped");
        }
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
