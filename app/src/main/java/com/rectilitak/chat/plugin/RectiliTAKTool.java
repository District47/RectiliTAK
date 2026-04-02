package com.rectilitak.chat.plugin;

import android.content.Context;

import com.atak.plugins.impl.AbstractPluginTool;

public class RectiliTAKTool extends AbstractPluginTool {

    public RectiliTAKTool(Context context) {
        super(context,
                context.getString(R.string.app_name),
                context.getString(R.string.app_name),
                context.getResources().getDrawable(R.drawable.rectilitak_icon),
                "com.rectilitak.chat.SHOW_CHAT");
    }
}
