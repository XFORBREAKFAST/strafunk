package com.dzaky.strafunk;

import android.Manifest;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * Pedometer offline: baca sensor TYPE_STEP_COUNTER bawaan HP.
 * Nilainya = total langkah sejak HP dinyalakan; aplikasi menghitung selisihnya.
 */
@CapacitorPlugin(
    name = "StepCounter",
    permissions = {
        @Permission(alias = "activity", strings = { Manifest.permission.ACTIVITY_RECOGNITION })
    }
)
public class StepCounterPlugin extends Plugin implements SensorEventListener {

    private SensorManager sensorManager;
    private Sensor stepSensor;
    private boolean watching = false;
    private double lastValue = -1;

    @Override
    public void load() {
        sensorManager = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        }
    }

    private boolean granted() {
        if (Build.VERSION.SDK_INT < 29) return true;
        return getPermissionState("activity") == PermissionState.GRANTED;
    }

    @PluginMethod
    public void isAvailable(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("available", stepSensor != null);
        ret.put("granted", granted());
        call.resolve(ret);
    }

    @PluginMethod
    public void requestPerm(PluginCall call) {
        if (granted()) {
            JSObject ret = new JSObject();
            ret.put("granted", true);
            call.resolve(ret);
            return;
        }
        requestPermissionForAlias("activity", call, "permCallback");
    }

    @PermissionCallback
    private void permCallback(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("granted", granted());
        call.resolve(ret);
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (stepSensor == null) {
            call.reject("Sensor langkah tidak tersedia");
            return;
        }
        if (!granted()) {
            call.reject("Izin aktivitas fisik belum diberikan");
            return;
        }
        if (!watching) {
            sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI);
            watching = true;
        }
        JSObject ret = new JSObject();
        ret.put("steps", lastValue);
        call.resolve(ret);
    }

    @PluginMethod
    public void stop(PluginCall call) {
        if (watching && sensorManager != null) {
            sensorManager.unregisterListener(this);
            watching = false;
        }
        call.resolve();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        lastValue = event.values[0];
        JSObject data = new JSObject();
        data.put("steps", lastValue);
        notifyListeners("step", data);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    @Override
    protected void handleOnDestroy() {
        if (watching && sensorManager != null) {
            sensorManager.unregisterListener(this);
            watching = false;
        }
    }
}
