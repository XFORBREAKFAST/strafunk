package com.dzaky.strafunk;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.util.ArrayList;

@CapacitorPlugin(
    name = "Tracker",
    permissions = {
        @Permission(alias = "location", strings = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION }),
        @Permission(alias = "activity", strings = { Manifest.permission.ACTIVITY_RECOGNITION }),
        @Permission(alias = "notif", strings = { Manifest.permission.POST_NOTIFICATIONS })
    }
)
public class TrackingPlugin extends Plugin implements TrackingService.Listener {

    @Override
    public void load() { TrackingService.listener = this; }

    @Override
    protected void handleOnDestroy() {
        if (TrackingService.listener == this) TrackingService.listener = null;
    }

    // ---- event dari service ----
    @Override public void onTrack(JSObject d) { notifyListeners("track", d); }

    @Override public void onSplit(int km, int sec) {
        JSObject o = new JSObject(); o.put("km", km); o.put("sec", sec);
        notifyListeners("split", o);
    }

    @Override public void onAction(String action) {
        JSObject o = new JSObject(); o.put("action", action);
        notifyListeners("action", o);
    }

    // ---- izin & status ----
    private boolean has(String alias) { return getPermissionState(alias) == PermissionState.GRANTED; }

    private JSObject statusObj() {
        Context c = getContext();
        JSObject r = new JSObject();
        r.put("location", has("location"));
        r.put("activity", Build.VERSION.SDK_INT < 29 || has("activity"));
        r.put("notif", Build.VERSION.SDK_INT < 33 || has("notif"));
        boolean gps = false;
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            gps = lm != null && lm.isProviderEnabled(LocationManager.GPS_PROVIDER);
        } catch (Exception e) { }
        r.put("gps", gps);
        SensorManager sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        r.put("stepSensor", sm != null && sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null);
        boolean batt = true;
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (pm != null && Build.VERSION.SDK_INT >= 23) batt = pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Exception e) { }
        r.put("battery", batt);
        r.put("tracking", TrackingService.active);
        r.put("sdk", Build.VERSION.SDK_INT);
        r.put("model", Build.MANUFACTURER + " " + Build.MODEL);
        return r;
    }

    @PluginMethod
    public void status(PluginCall call) { call.resolve(statusObj()); }

    @PluginMethod
    public void requestAll(PluginCall call) {
        ArrayList<String> a = new ArrayList<>();
        if (!has("location")) a.add("location");
        if (Build.VERSION.SDK_INT >= 29 && !has("activity")) a.add("activity");
        if (Build.VERSION.SDK_INT >= 33 && !has("notif")) a.add("notif");
        if (a.isEmpty()) { call.resolve(statusObj()); return; }
        requestPermissionForAliases(a.toArray(new String[0]), call, "permCb");
    }

    @PermissionCallback
    private void permCb(PluginCall call) { call.resolve(statusObj()); }

    // ---- kontrol rekaman ----
    @PluginMethod
    public void start(PluginCall call) {
        if (!has("location")) { call.reject("Izin lokasi belum diberikan", "NO_PERMISSION"); return; }
        Intent i = new Intent(getContext(), TrackingService.class).setAction(TrackingService.A_START);
        i.putExtra("type", call.getString("type", "Lari"));
        i.putExtra("stride", call.getDouble("stride", 72.0).doubleValue());
        i.putExtra("weight", call.getDouble("weight", 60.0).doubleValue());
        try {
            ContextCompat.startForegroundService(getContext(), i);
            call.resolve();
        } catch (Exception e) {
            call.reject("Gagal memulai tracking: " + e.getMessage());
        }
    }

    private void send(String action) {
        try { getContext().startService(new Intent(getContext(), TrackingService.class).setAction(action)); } catch (Exception e) { }
    }

    @PluginMethod public void pause(PluginCall call) { send(TrackingService.A_PAUSE); call.resolve(); }
    @PluginMethod public void resume(PluginCall call) { send(TrackingService.A_RESUME); call.resolve(); }

    @PluginMethod
    public void stop(PluginCall call) {
        JSObject st = TrackingService.state(true);
        st.put("elapsed", TrackingService.elapsedMs());
        send(TrackingService.A_STOP);
        call.resolve(st);
    }

    @PluginMethod
    public void getState(PluginCall call) {
        JSObject st = TrackingService.state(true);
        st.put("pendingAction", TrackingService.pendingAction);
        TrackingService.pendingAction = null;
        call.resolve(st);
    }

    // ---- shortcut ke pengaturan HP ----
    private void open(Intent i, PluginCall call) {
        try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); getContext().startActivity(i); } catch (Exception e) { }
        call.resolve();
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName())), call);
    }

    @PluginMethod
    public void openGpsSettings(PluginCall call) { open(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS), call); }

    @PluginMethod
    public void requestBattery(PluginCall call) {
        if (Build.VERSION.SDK_INT < 23) { call.resolve(); return; }
        open(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getContext().getPackageName())), call);
    }
}
