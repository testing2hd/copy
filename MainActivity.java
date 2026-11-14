package com.linkrouter.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(LinkRouterPlugin.class);
        super.onCreate(savedInstanceState);
        UpdateJob.schedule(this);
        new Thread(() -> { try { Downloader.ensureInit(this); } catch (Throwable ignored) { } }).start();
    }
}
