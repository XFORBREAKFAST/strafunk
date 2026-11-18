package com.dzaky.strafunk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.RemoteViews;

import androidx.core.app.NotificationCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Mesin tracking Strafunk: GPS + sensor langkah, jalan terus sebagai foreground service
 * (tetap hidup saat layar mati), plus notifikasi live bergaya Strafunk.
 */
public class TrackingService extends Service implements LocationListener, SensorEventListener {

    public static final String A_START = "sf.START", A_PAUSE = "sf.PAUSE", A_RESUME = "sf.RESUME", A_STOP = "sf.STOP";
    static final int NID = 7301;
    static final String CH_TRACK = "sf_track", CH_KM = "sf_km_v1";

    public interface Listener {
        void onTrack(JSObject d);
        void onSplit(int km, int sec);
        void onAction(String action);
    }

    public static volatile Listener listener;
    public static volatile String pendingAction = null;

    // ---- state (static supaya plugin bisa baca kapan pun) ----
    public static boolean active = false, running = false, gotFix = false, est = false;
    public static String type = "Lari";
    public static double dist = 0, acc = -1, stride = 72, weight = 60;
    public static long steps = 0;
    static long startRt = 0, pausedTotal = 0, pausedAt = 0, lastGood = 0, lastT = 0;
    static double lastLat = Double.NaN, lastLng = 0, lastStep = -1;
    static int kmNext = 1000;
    public static final ArrayList<double[]> path = new ArrayList<>();
    public static final ArrayList<Integer> splits = new ArrayList<>();

    private LocationManager lm;
    private SensorManager sm;
    private Handler h;
    private int tickN = 0;

    static long elapsedMs() {
        if (startRt == 0) return 0;
        long now = SystemClock.elapsedRealtime();
        return now - startRt - pausedTotal - (running ? 0 : now - pausedAt);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent it, int flags, int startId) {
        String a = it != null ? it.getAction() : null;
        if (A_START.equals(a)) begin(it);
        else if (A_PAUSE.equals(a)) setRunning(false);
        else if (A_RESUME.equals(a)) setRunning(true);
        else if (A_STOP.equals(a)) { end(); stopSelf(); }
        else if (!active) stopSelf();
        return START_NOT_STICKY;
    }

    private void begin(Intent it) {
        if (active) { notifyUpdate(); return; }
        type = it.getStringExtra("type"); if (type == null) type = "Lari";
        stride = it.getDoubleExtra("stride", 72);
        weight = it.getDoubleExtra("weight", 60);
        dist = 0; steps = 0; acc = -1; gotFix = false; est = false;
        path.clear(); splits.clear();
        lastLat = Double.NaN; lastStep = -1; kmNext = 1000; lastGood = 0;
        startRt = SystemClock.elapsedRealtime(); pausedTotal = 0; pausedAt = 0;
        running = true; active = true;

        createChannels();
        Notification n = build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(NID, n);
        } catch (Exception e) {
            try { startForeground(NID, n); } catch (Exception ignored) { }
        }

        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this, Looper.getMainLooper());
        } catch (SecurityException e) {
            // izin belum diberikan
        } catch (Exception e) { }

        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor s = sm != null ? sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) : null;
        if (s != null) {
            try { sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI); } catch (Exception e) { }
        }

        h = new Handler(Looper.getMainLooper());
        h.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            emit(false);
            if (++tickN % 2 == 0) notifyUpdate();
            h.postDelayed(this, 1000);
        }
    };

    private void setRunning(boolean r) {
        if (!active || running == r) return;
        long now = SystemClock.elapsedRealtime();
        if (r) { pausedTotal += now - pausedAt; lastLat = Double.NaN; lastStep = -1; }
        else pausedAt = now;
        running = r;
        notifyUpdate();
        emit(false);
    }

    private void end() {
        active = false; running = false;
        if (h != null) h.removeCallbacks(tick);
        try { if (lm != null) lm.removeUpdates(this); } catch (Exception e) { }
        try { if (sm != null) sm.unregisterListener(this); } catch (Exception e) { }
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE); else stopForeground(true);
        } catch (Exception e) { }
    }

    @Override
    public void onDestroy() {
        if (active) end();
        super.onDestroy();
    }

    // ------------------------- GPS -------------------------
    private double maxSpeed() {
        if ("Jalan".equals(type)) return 4.5;
        if ("Sepeda".equals(type)) return 25;
        return 9;
    }

    @Override
    public void onLocationChanged(Location l) {
        acc = l.hasAccuracy() ? l.getAccuracy() : -1;
        gotFix = true;
        if (!running) { lastLat = Double.NaN; return; }
        if (l.hasAccuracy() && l.getAccuracy() > 50) return;
        long t = System.currentTimeMillis();
        if (!Double.isNaN(lastLat)) {
            float[] r = new float[1];
            Location.distanceBetween(lastLat, lastLng, l.getLatitude(), l.getLongitude(), r);
            double d = r[0], dt = (t - lastT) / 1000.0;
            if (d < 3) return;
            if (dt > 0 && d / dt > maxSpeed()) return;
            dist += d;
            checkSplits();
        }
        lastLat = l.getLatitude(); lastLng = l.getLongitude(); lastT = t; lastGood = t;
        if (path.size() < 20000) path.add(new double[]{ lastLat, lastLng });
        emit(true);
    }

    // Wajib ada untuk Android 10 ke bawah (di sana method-method ini abstrak)
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }

    // ------------------------- Langkah -------------------------
    @Override
    public void onSensorChanged(SensorEvent e) {
        double v = e.values[0];
        if (lastStep >= 0 && running) {
            double d = v - lastStep;
            if (d > 0 && d < 500) {
                steps += (long) d;
                if (!"Sepeda".equals(type) && System.currentTimeMillis() - lastGood > 15000) {
                    dist += d * stride / 100.0;
                    est = true;
                    checkSplits();
                }
            }
        }
        lastStep = v;
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void checkSplits() {
        while (dist >= kmNext) {
            int tot = (int) (elapsedMs() / 1000);
            int prev = 0;
            for (int s : splits) prev += s;
            int sec = tot - prev;
            splits.add(sec);
            kmNext += 1000;
            milestone(splits.size(), sec, tot);
            Listener L = listener;
            if (L != null) try { L.onSplit(splits.size(), sec); } catch (Exception ex) { }
        }
    }

    // ------------------------- ke JS -------------------------
    private void emit(boolean newPt) {
        Listener L = listener;
        if (L == null) return;
        JSObject d = state(false);
        d.put("newPt", newPt);
        if (newPt) { d.put("lat", lastLat); d.put("lng", lastLng); }
        try { L.onTrack(d); } catch (Exception e) { }
    }

    public static JSObject state(boolean withPath) {
        JSObject d = new JSObject();
        d.put("active", active);
        d.put("running", running);
        d.put("type", type);
        d.put("dist", dist);
        d.put("steps", steps);
        d.put("elapsed", active ? elapsedMs() : 0);
        d.put("acc", acc);
        d.put("fix", gotFix);
        d.put("est", est);
        d.put("goodAgo", lastGood == 0 ? -1 : System.currentTimeMillis() - lastGood);
        JSArray sp = new JSArray();
        for (int s : splits) sp.put(s);
        d.put("splits", sp);
        if (withPath) {
            JSArray p = new JSArray();
            for (double[] x : path) {
                JSArray q = new JSArray();
                try { q.put(x[0]); q.put(x[1]); } catch (Exception e) { }
                p.put(q);
            }
            d.put("path", p);
        }
        return d;
    }

    // ------------------------- Notifikasi -------------------------
    static String kmStr(double m) { return String.format(Locale.US, "%.2f", m / 1000.0).replace('.', ','); }

    static String paceStr(double secPerKm) {
        if (Double.isNaN(secPerKm) || Double.isInfinite(secPerKm) || secPerKm <= 0 || secPerKm > 3600) return "--'--\"";
        int m = (int) (secPerKm / 60), s = (int) Math.round(secPerKm % 60);
        if (s == 60) s = 59;
        return m + "'" + (s < 10 ? "0" : "") + s + "\"";
    }

    static String speedStr(double meters, double sec) {
        if (sec <= 0) return "0";
        return String.format(Locale.US, "%.1f", (meters / 1000.0) / (sec / 3600.0)).replace('.', ',');
    }

    static String durStr(int s) {
        int hh = s / 3600, mm = (s % 3600) / 60, ss = s % 60;
        return hh > 0 ? String.format(Locale.US, "%d:%02d:%02d", hh, mm, ss) : String.format(Locale.US, "%02d:%02d", mm, ss);
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel t = new NotificationChannel(CH_TRACK, "Rekaman aktif", NotificationManager.IMPORTANCE_LOW);
        t.setShowBadge(false);
        t.setSound(null, null);
        t.setDescription("Jarak, waktu, dan pace saat merekam");
        nm.createNotificationChannel(t);

        NotificationChannel k = new NotificationChannel(CH_KM, "Kilometer & battle", NotificationManager.IMPORTANCE_HIGH);
        k.setDescription("Pop-up tiap kilometer");
        Uri snd = Uri.parse("android.resource://" + getPackageName() + "/raw/sf_chime");
        AudioAttributes aa = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        k.setSound(snd, aa);
        k.enableVibration(true);
        k.setVibrationPattern(new long[]{ 0, 60, 80, 60 });
        nm.createNotificationChannel(k);
    }

    private PendingIntent svcPI(String action, int code) {
        Intent i = new Intent(this, TrackingService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent appPI(String extra, int code) {
        Intent i = new Intent(this, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (extra != null) i.putExtra("sf_action", extra);
        return PendingIntent.getActivity(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void fill(RemoteViews rv, boolean big) {
        long el = elapsedMs();
        double sec = el / 1000.0;
        boolean bike = "Sepeda".equals(type);
        rv.setTextViewText(R.id.sf_label, (running ? "● MEREKAM · " : "❚❚ DIJEDA · ") + type.toUpperCase(Locale.ROOT));
        rv.setTextViewText(R.id.sf_km, kmStr(dist));
        rv.setChronometer(R.id.sf_time, SystemClock.elapsedRealtime() - el, null, running);
        String pace = bike ? speedStr(dist, sec) + " km/j" : paceStr(dist > 30 ? sec / (dist / 1000.0) : Double.NaN) + " /km";
        rv.setTextViewText(R.id.sf_pace, pace);
        if (big) {
            rv.setTextViewText(R.id.sf_steps, bike ? String.valueOf((int) dist / 1000) + " km" : String.format(Locale.GERMANY, "%,d", steps));
            rv.setTextViewText(R.id.sf_steps_lbl, bike ? "KM PENUH" : "LANGKAH");
            rv.setProgressBar(R.id.sf_prog, 1000, (int) (dist % 1000), false);
            rv.setTextViewText(R.id.sf_next, "Km " + ((int) (dist / 1000) + 1) + " · sisa " + (1000 - (int) (dist % 1000)) + " m");
            rv.setTextViewText(R.id.sf_btn_pause, running ? "Jeda" : "Lanjut");
            rv.setOnClickPendingIntent(R.id.sf_btn_pause, svcPI(running ? A_PAUSE : A_RESUME, 11));
            rv.setOnClickPendingIntent(R.id.sf_btn_finish, appPI("finish", 12));
        }
    }

    private Notification build() {
        RemoteViews small = new RemoteViews(getPackageName(), R.layout.sf_notif_small);
        RemoteViews big = new RemoteViews(getPackageName(), R.layout.sf_notif_big);
        fill(small, false);
        fill(big, true);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_TRACK)
            .setSmallIcon(R.drawable.sf_ic_notif)
            .setContentTitle("Strafunk · " + kmStr(dist) + " km")
            .setContentText(type + " · " + durStr((int) (elapsedMs() / 1000)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setColor(0xFFC5F82A)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setContentIntent(appPI(null, 10))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    private void notifyUpdate() {
        if (!active) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NID, build());
        } catch (Exception e) { }
    }

    private void milestone(int km, int sec, int tot) {
        boolean bike = "Sepeda".equals(type);
        String detail = (bike ? speedStr(1000, sec) + " km/j" : paceStr(sec) + " /km") + " · total " + durStr(tot);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CH_KM)
            .setSmallIcon(R.drawable.sf_ic_notif)
            .setColor(0xFFC5F82A)
            .setContentTitle("Km " + km + " beres ⚡")
            .setContentText(detail)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setAutoCancel(true)
            .setTimeoutAfter(6000)
            .setContentIntent(appPI(null, 13));
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NID + 100, b.build());
        } catch (Exception e) { }
    }
}
