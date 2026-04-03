package com.rectilitak.chat;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Bundle;
import android.preference.PreferenceManager;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.preference.PluginPreferenceFragment;
import com.rectilitak.chat.plugin.R;

public class RectiliTAKPreferenceFragment extends PluginPreferenceFragment {

    private static Context pluginContext;

    public RectiliTAKPreferenceFragment() {
        super(pluginContext, R.xml.preferences);
    }

    @SuppressLint("ValidFragment")
    public RectiliTAKPreferenceFragment(Context context) {
        super(context, R.xml.preferences);
        pluginContext = context;
    }

    @Override
    public String getSubTitle() {
        return getSubTitle("Tools Preferences", "RectiliTAK Settings");
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Redirect preferences to ATAK's shared prefs (not the plugin's)
        Context atakContext = MapView.getMapView().getContext();
        PreferenceManager pm = getPreferenceManager();
        pm.setSharedPreferencesName("rectilitak_prefs");
        pm.setSharedPreferencesMode(Context.MODE_PRIVATE);

        super.onCreate(savedInstanceState);
    }
}
