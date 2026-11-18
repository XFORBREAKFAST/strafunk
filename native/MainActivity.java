package com.dzaky.strafunk;

import android.content.Intent;
import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(StepCounterPlugin.class);
        registerPlugin(TrackingPlugin.class);
        super.onCreate(savedInstanceState);
        handle(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handle(intent);
    }

    /** Tombol "Selesai" di notifikasi membuka app dengan extra sf_action=finish. */
    private void handle(Intent i) {
        if (i == null || !i.hasExtra("sf_action")) return;
        String a = i.getStringExtra("sf_action");
        i.removeExtra("sf_action");
        TrackingService.pendingAction = a;
        TrackingService.Listener L = TrackingService.listener;
        if (L != null) {
            try { L.onAction(a); TrackingService.pendingAction = null; } catch (Exception e) { }
        }
    }
}
