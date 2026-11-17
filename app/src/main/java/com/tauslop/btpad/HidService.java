package com.tauslop.btpad;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppQosSettings;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * Owns all Bluetooth state. The activity binds while visible; a session (pairing,
 * connecting or connected) additionally starts the service in the foreground. When a
 * session ends for any reason the app is unregistered and the service stops itself.
 * Nothing here ever retries or reconnects on its own.
 *
 * <p>Bluetooth calls need BLUETOOTH_CONNECT on API 31+, which the activity obtains before it can
 * start a session ({@link #hasPermission} guards the entry points).
 */
@SuppressLint("MissingPermission")
public final class HidService extends Service {
    static final String ACTION_DISCONNECT = "com.tauslop.btpad.DISCONNECT";

    static final int IDLE = 0, PAIRING = 1, CONNECTING = 2, CONNECTED = 3, UNSUPPORTED = 4;

    interface Listener {
        void onHidChanged();
    }

    final class Local extends Binder {
        HidService service() {
            return HidService.this;
        }
    }

    private static final String PREFS = "btpad";
    private static final String KEY_HOST = "host";
    private static final String KEY_IDLE_MS = "idle_ms";
    private static final String KEY_NATURAL_SCROLL = "natural_scroll";
    private static final String KEY_VOLUME_KEYS = "volume_keys";
    private static final String CHANNEL = "link";
    private static final int NOTIFICATION_ID = 1;
    private static final long PROXY_TIMEOUT_MS = 5000;

    private static final BluetoothHidDeviceAppSdpSettings SDP = new BluetoothHidDeviceAppSdpSettings(
            Hid.SDP_NAME, Hid.SDP_DESCRIPTION, Hid.SDP_PROVIDER,
            BluetoothHidDevice.SUBCLASS1_COMBO, Hid.DESCRIPTOR);
    private static final BluetoothHidDeviceAppQosSettings QOS = new BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT, 800, 9, 0, 11250,
            BluetoothHidDeviceAppQosSettings.MAX);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tick;
    private final Runnable proxyTimeout = this::onProxyTimeout;

    private BluetoothAdapter adapter;
    private BluetoothHidDevice proxy;
    private ConnectionPolicy policy;
    private Listener listener;

    private int state = IDLE;
    private boolean unsupported, registered, registering, receiverOn, switching;
    private BluetoothDevice target, host, switchTo;
    private long switchTimeout;
    private int message; // string res explaining the last session end, or 0
    private int leds;

    // ---- Lifecycle ----

    @Override
    public void onCreate() {
        super.onCreate();
        policy = new ConnectionPolicy(SystemClock::elapsedRealtime, idleMs(this));
        adapter = getSystemService(BluetoothManager.class).getAdapter();
        if (adapter == null || !adapter.getProfileProxy(this, profileListener,
                BluetoothProfile.HID_DEVICE)) {
            unsupported = true;
        } else if (adapter.isEnabled()) {
            handler.postDelayed(proxyTimeout, PROXY_TIMEOUT_MS);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new Local();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            disconnect();
            if (!policy.isActive()) stopSelf();
            return START_NOT_STICKY;
        }
        // Started by startSession() via startForegroundService(): must go foreground.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else { // type comes from the manifest; not enforced before 34
                startForeground(NOTIFICATION_ID, notification());
            }
        } catch (RuntimeException e) {
            endSession(R.string.msg_failed);
        }
        if (!policy.isActive()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        endSession(0);
        handler.removeCallbacksAndMessages(null);
        if (proxy != null) adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy);
        proxy = null;
        super.onDestroy();
    }

    /** Some OEM builds strip the HID device profile: the proxy never connects. */
    private void onProxyTimeout() {
        if (proxy != null) return;
        unsupported = true;
        endSession(0);
        changed();
    }

    // ---- API for the activity (main thread) ----

    void setListener(Listener l) {
        listener = l;
    }

    int state() {
        return unsupported ? UNSUPPORTED : state;
    }

    /** Connected host, or the host being connected to. */
    BluetoothDevice host() {
        return host != null ? host : target;
    }

    int message() {
        return message;
    }

    boolean bluetoothOn() {
        return adapter != null && adapter.isEnabled();
    }

    /** Host keyboard LEDs (Hid.LED_*), from its last output report. */
    int leds() {
        return leds;
    }

    /** User intent: connect to a bonded host. */
    void connect(BluetoothDevice device) {
        if (unsupported || device == null || !hasPermission()) return;
        if (device.equals(host())) return; // already connected or connecting to it
        retarget(device, ConnectionPolicy.CONNECT_TIMEOUT_MS);
    }

    /** User intent: register and wait for a new computer to pair and connect. */
    void pair() {
        if (unsupported || !hasPermission()) return;
        retarget(null, ConnectionPolicy.PAIR_TIMEOUT_MS);
    }

    void disconnect() {
        endSession(R.string.msg_disconnected);
    }

    void setVisible(boolean visible) {
        policy.onVisible(visible);
        schedule();
    }

    /** Send one input report. Counts as activity for the idle timeout. */
    void send(int id, byte[] report) {
        if (state != CONNECTED || proxy == null) return;
        policy.onInput();
        boolean ok = proxy.sendReport(host, id, report);
        if (BuildConfig.DEBUG) logReport(id, report, ok);
    }

    /** Debug builds: one line per report, to line up with a host evdev capture. */
    private static void logReport(int id, byte[] r, boolean ok) {
        String s = id == Hid.ID_MOUSE && r.length >= 7
                ? " dx=" + (short) ((r[1] & 0xFF) | r[2] << 8)
                + " dy=" + (short) ((r[3] & 0xFF) | r[4] << 8)
                + " b=" + r[0] + " w=" + r[5] + " p=" + r[6]
                : " len=" + r.length;
        Log.d("BTPadTx", "t=" + SystemClock.elapsedRealtimeNanos() + " id=" + id + s
                + (ok ? "" : " FAILED"));
    }

    private boolean hasPermission() {
        // API 30: BLUETOOTH is an install-time permission (always granted).
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    static String lastHost(Context c) {
        return prefs(c).getString(KEY_HOST, null);
    }

    static long idleMs(Context c) {
        return prefs(c).getLong(KEY_IDLE_MS, ConnectionPolicy.DEFAULT_IDLE_MS);
    }

    /** Scroll direction (see Sender.naturalScroll); default off: wheel style. */
    static boolean naturalScroll(Context c) {
        return prefs(c).getBoolean(KEY_NATURAL_SCROLL, false);
    }

    static void setNaturalScroll(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_NATURAL_SCROLL, on).apply();
    }

    /** Phone volume buttons send consumer volume keys while connected; default off. */
    static boolean volumeKeys(Context c) {
        return prefs(c).getBoolean(KEY_VOLUME_KEYS, false);
    }

    static void setVolumeKeys(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_VOLUME_KEYS, on).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---- Session ----

    /**
     * New target within the current session if there is one, keeping the registration
     * (re-registering right away would race with the pending unregister callback).
     */
    private void retarget(BluetoothDevice device, long timeoutMs) {
        if (!policy.isActive()) {
            startSession(device, timeoutMs);
        } else if (state == CONNECTED) { // continue once the old link is down
            switching = true;
            switchTo = device;
            switchTimeout = timeoutMs;
            policy.onSessionStart(timeoutMs); // the switch itself can time out too
            proxy.disconnect(host);
        } else {
            if (target != null && proxy != null) proxy.disconnect(target); // cancel the page
            begin(device, timeoutMs);
        }
    }

    private void startSession(BluetoothDevice device, long timeoutMs) {
        message = 0;
        policy.setIdleMs(idleMs(this));
        if (!receiverOn) {
            registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
            receiverOn = true;
        }
        startForegroundService(new Intent(this, HidService.class));
        begin(device, timeoutMs);
        if (proxy != null) {
            register();
        } else if (adapter.isEnabled()) {
            handler.postDelayed(proxyTimeout, PROXY_TIMEOUT_MS);
        }
    }

    /** Start (or restart) the connect/pair attempt; device null = wait for pairing. */
    private void begin(BluetoothDevice device, long timeoutMs) {
        target = device;
        host = null;
        state = device == null ? PAIRING : CONNECTING;
        policy.onSessionStart(timeoutMs);
        if (registered) onRegistered();
        schedule();
        changed();
    }

    private void register() {
        if (registered || registering) return;
        registering = true;
        if (!proxy.registerApp(SDP, null, QOS, getMainExecutor(), callback)) {
            registering = false;
            endSession(R.string.msg_failed);
        }
    }

    private void onRegistered() {
        if (state == CONNECTING && !proxy.connect(target)) endSession(R.string.msg_failed);
    }

    private void endSession(int why) {
        switching = false;
        switchTo = null;
        if (!policy.isActive()) return;
        policy.onSessionEnd();
        handler.removeCallbacks(tick);
        handler.removeCallbacks(proxyTimeout);
        if (receiverOn) {
            unregisterReceiver(screenOff);
            receiverOn = false;
        }
        if (proxy != null && (registered || registering)) proxy.unregisterApp(); // drops the link
        registered = registering = false;
        state = IDLE;
        host = null;
        target = null;
        message = why;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        changed();
    }

    private void schedule() {
        handler.removeCallbacks(tick);
        long d = policy.delay();
        if (d >= 0) handler.postDelayed(tick, d);
    }

    private void tick() {
        switch (policy.check()) {
            case ConnectionPolicy.KEEP:
                schedule();
                break;
            case ConnectionPolicy.TIMEOUT:
                endSession(state == PAIRING ? R.string.msg_pair_timeout : R.string.msg_unreachable);
                break;
            case ConnectionPolicy.BACKGROUND:
                endSession(R.string.msg_background);
                break;
            case ConnectionPolicy.IDLE:
                endSession(R.string.msg_idle);
                break;
            case ConnectionPolicy.SCREEN_OFF:
                endSession(R.string.msg_screen_off);
                break;
        }
    }

    private void changed() {
        if (policy.isActive()) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
        }
        if (listener != null) listener.onHidChanged();
    }

    private final BroadcastReceiver screenOff = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            policy.onScreenOff();
            tick();
        }
    };

    // ---- Bluetooth callbacks (main thread) ----

    private final BluetoothProfile.ServiceListener profileListener =
            new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile p) {
                    handler.removeCallbacks(proxyTimeout);
                    proxy = (BluetoothHidDevice) p;
                    unsupported = false;
                    if (policy.isActive()) register();
                    changed();
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    proxy = null;
                    registered = registering = false;
                    endSession(R.string.msg_disconnected);
                }
            };

    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice plugged, boolean reg) {
            if (reg) {
                registering = false;
                registered = true;
                if (policy.isActive()) {
                    onRegistered();
                } else { // session ended while registering
                    proxy.unregisterApp();
                    registered = false;
                }
            } else {
                registered = false;
                // While registering, this is the stale callback of an earlier unregister.
                if (!registering) endSession(R.string.msg_failed);
            }
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice d, int s) {
            if (!policy.isActive()) return;
            if (s == BluetoothProfile.STATE_CONNECTED) {
                host = d;
                target = null;
                state = CONNECTED;
                policy.onConnected();
                prefs(HidService.this).edit().putString(KEY_HOST, d.getAddress()).apply();
                schedule();
                changed();
            } else if (s == BluetoothProfile.STATE_DISCONNECTED
                    && (d.equals(host) || d.equals(target))) {
                if (switching) {
                    switching = false;
                    BluetoothDevice next = switchTo;
                    switchTo = null;
                    begin(next, switchTimeout);
                } else {
                    endSession(state == CONNECTED ? R.string.msg_host_dropped
                            : R.string.msg_unreachable);
                }
            }
        }

        @Override
        public void onGetReport(BluetoothDevice d, byte type, byte id, int bufferSize) {
            byte[] r = null;
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT) {
                r = emptyInput(id);
            } else if (type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && id == Hid.ID_KEYBOARD) {
                r = new byte[]{(byte) leds};
            }
            if (r != null) {
                proxy.replyReport(d, type, id, r);
            } else {
                proxy.reportError(d, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID);
            }
        }

        @Override
        public void onSetReport(BluetoothDevice d, byte type, byte id, byte[] data) {
            boolean ok = type == BluetoothHidDevice.REPORT_TYPE_OUTPUT && id == Hid.ID_KEYBOARD;
            if (ok) setLeds(Hid.oneByte(id, data));
            proxy.reportError(d, ok ? BluetoothHidDevice.ERROR_RSP_SUCCESS
                    : BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID);
            if (ok) changed();
        }

        @Override
        public void onInterruptData(BluetoothDevice d, byte id, byte[] data) {
            if (id == Hid.ID_KEYBOARD) {
                setLeds(Hid.oneByte(id, data));
                changed();
            }
        }
    };

    private void setLeds(int v) {
        if (v >= 0) leds = v;
    }

    private static byte[] emptyInput(int id) {
        switch (id) {
            case Hid.ID_KEYBOARD: return new byte[Hid.KEYBOARD_LEN];
            case Hid.ID_MOUSE: return new byte[Hid.MOUSE_LEN];
            case Hid.ID_CONSUMER: return new byte[Hid.CONSUMER_LEN];
            default: return null;
        }
    }

    // ---- Notification ----

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(ACTION_DISCONNECT, null, this, HidService.class),
                PendingIntent.FLAG_IMMUTABLE);
        String title = state == CONNECTED ? getString(R.string.connected_to, name(host))
                : state == PAIRING ? getString(R.string.status_pairing)
                : getString(R.string.status_connecting);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null,
                        getString(R.string.disconnect), stop).build())
                .build();
    }

    /** Device name; needs BLUETOOTH_CONNECT (granted before any session starts). */
    static String name(BluetoothDevice d) {
        if (d == null) return "";
        try {
            String n = d.getAlias();
            return n != null ? n : d.getAddress();
        } catch (SecurityException e) {
            return d.getAddress();
        }
    }
}
