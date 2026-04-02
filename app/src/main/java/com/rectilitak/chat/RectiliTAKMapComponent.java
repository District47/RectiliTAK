package com.rectilitak.chat;

import android.content.Context;
import android.content.Intent;

import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;
import com.atakmap.android.maps.AbstractMapComponent;
import com.atakmap.android.maps.MapView;

import com.rectilitak.chat.plugin.R;

public class RectiliTAKMapComponent extends AbstractMapComponent {

    public static final String TAG = "RectiliTAKMapComponent";
    public static final String SHOW_CHAT = "com.rectilitak.chat.SHOW_CHAT";

    private Context pluginContext;

    @Override
    public void onCreate(Context context, Intent intent, MapView view) {
        context.setTheme(R.style.ATAKPluginTheme);
        pluginContext = context;
    }

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
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
