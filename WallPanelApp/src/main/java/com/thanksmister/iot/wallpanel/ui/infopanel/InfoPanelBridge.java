/*
 * Copyright (c) 2026
 *
 * Licensed under the Apache License, Version 2.0.
 */

package com.thanksmister.iot.wallpanel.ui.infopanel;

import android.webkit.JavascriptInterface;

public class InfoPanelBridge {
    private final InfoPanelControlHub controlHub;

    public InfoPanelBridge(InfoPanelControlHub controlHub) {
        this.controlHub = controlHub;
    }

    @JavascriptInterface
    public String getState() {
        return controlHub.getStateJson().toString();
    }

    @JavascriptInterface
    public void performAction(String action) {
        controlHub.performAction(action);
    }
}
