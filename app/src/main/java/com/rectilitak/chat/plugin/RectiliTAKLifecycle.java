package com.rectilitak.chat.plugin;

import com.atak.plugins.impl.AbstractPlugin;
import com.atak.plugins.impl.PluginContextProvider;
import com.rectilitak.chat.RectiliTAKMapComponent;

import gov.tak.api.plugin.IServiceController;

public class RectiliTAKLifecycle extends AbstractPlugin {

    public RectiliTAKLifecycle(IServiceController serviceController) {
        super(serviceController,
                new RectiliTAKTool(serviceController.getService(PluginContextProvider.class).getPluginContext()),
                new RectiliTAKMapComponent());
    }
}
