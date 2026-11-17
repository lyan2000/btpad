package com.tauslop.btpad;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity
        implements ServiceConnection, HidService.Listener {
    /** Runtime permissions for this API level (none on 30: Bluetooth is install-time). */
    private static final String[] PERMISSIONS = permissions();
    private static final int REQ_PERMISSIONS = 1, REQ_DISCOVERABLE = 2, REQ_ENABLE = 3;
    private static final int DISCOVERABLE_SECONDS = 120;
    /** The "can't type that" hint shows once per process. */
    private static boolean warnedUnmappable;

    private HidService hid;
    private Sender sender;
    private Keyboard keyboard;
    private boolean autoConnect, resumed, cancelPairing, connected, volumeKeys;

    private View dot, disconnect, hint, padLabel, unsupported;
    private TouchpadView pad;
    private KeyStripView strip;
    private TypeField typeField;
    private TextView hostName, status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        dot = findViewById(R.id.dot);
        hostName = findViewById(R.id.host_name);
        status = findViewById(R.id.status);
        disconnect = findViewById(R.id.disconnect);
        hint = findViewById(R.id.hint);
        pad = findViewById(R.id.pad);
        padLabel = findViewById(R.id.pad_label);
        unsupported = findViewById(R.id.unsupported);

        // Opening the app is the user's intent to connect to the last host (once per launch).
        autoConnect = savedInstanceState == null;
        sender = new Sender((id, report) -> {
            if (hid != null && resumed) hid.send(id, report);
        }, getResources().getDisplayMetrics().density * 160 / 25.4f);
        sender.naturalScroll = HidService.naturalScroll(this);
        pad.setOutput(sender);

        strip = findViewById(R.id.strip);
        typeField = findViewById(R.id.type);
        keyboard = new Keyboard(sender::send);
        keyboard.changed = strip::invalidate;
        strip.setKeyboard(keyboard, typeField::clear);
        typeField.setKeyboard(keyboard, this::warnUnmappable);
        volumeKeys = HidService.volumeKeys(this);
        View chip = findViewById(R.id.chip);
        chip.setOnClickListener(v -> pickHost());
        chip.setOnLongClickListener(v -> {
            showSettings();
            return true;
        });
        findViewById(R.id.settings).setOnClickListener(v -> showSettings());
        disconnect.setOnClickListener(v -> {
            if (hid != null) hid.disconnect();
        });
        if (!hasPermissions()) requestPermissions(PERMISSIONS, REQ_PERMISSIONS);
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, HidService.class), this, BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopInput(); // releases held buttons while still allowed to send
        resumed = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (hid != null) {
            hid.setVisible(false); // starts the 30 s background countdown
            hid.setListener(null);
            hid = null;
        }
        unbindService(this);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        hid = ((HidService.Local) binder).service();
        hid.setListener(this);
        hid.setVisible(true);
        if (cancelPairing) {
            cancelPairing = false;
            if (hid.state() == HidService.PAIRING) hid.disconnect();
        }
        maybeAutoConnect();
        refresh();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        hid = null;
    }

    @Override
    public void onHidChanged() {
        refresh();
    }

    private void maybeAutoConnect() {
        if (!autoConnect || hid == null || !hasPermissions()) return;
        autoConnect = false;
        BluetoothDevice last = lastHost();
        if (last != null && hid.state() == HidService.IDLE && hid.bluetoothOn()) {
            hid.connect(last);
        }
    }

    private void refresh() {
        int st = hid == null ? HidService.IDLE : hid.state();
        boolean active = st == HidService.PAIRING || st == HidService.CONNECTING
                || st == HidService.CONNECTED;

        BluetoothDevice host = hid != null && hid.host() != null ? hid.host() : lastHost();
        hostName.setText(host != null ? HidService.name(host) : getString(R.string.no_host));
        status.setText(statusText(st));
        dot.setBackgroundTintList(ColorStateList.valueOf(getColor(
                st == HidService.CONNECTED ? R.color.accent : R.color.text_secondary)));
        dot.setAlpha(active ? 1f : 0.4f);
        disconnect.setEnabled(active);
        disconnect.setAlpha(active ? 1f : 0.4f);

        boolean caps = st == HidService.CONNECTED && (hid.leds() & Hid.LED_CAPS_LOCK) != 0;
        if (caps != keyboard.caps) {
            keyboard.caps = caps;
            strip.invalidate();
        }

        // A link change ends the gesture: nothing stays held across it.
        if ((st == HidService.CONNECTED) != connected) {
            connected = !connected;
            stopInput();
        }

        boolean ok = st != HidService.UNSUPPORTED;
        unsupported.setVisibility(ok ? View.GONE : View.VISIBLE);
        pad.setVisibility(ok ? View.VISIBLE : View.GONE);
        padLabel.setVisibility(ok ? View.VISIBLE : View.GONE);
        strip.setVisibility(ok ? View.VISIBLE : View.GONE);
        typeField.setVisibility(ok ? View.VISIBLE : View.GONE);
        hint.setVisibility(ok && st != HidService.CONNECTED && !hasBondedComputer()
                ? View.VISIBLE : View.GONE);

        // Screen stays on only while connected and visible (the flag only acts when visible).
        if (st == HidService.CONNECTED) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /** End the current gesture and drop pending input, releasing any held button or key. */
    private void stopInput() {
        pad.cancel();
        keyboard.reset();
        strip.cancel();
        typeField.clear();
        sender.reset();
    }

    private void warnUnmappable() {
        if (warnedUnmappable) return;
        warnedUnmappable = true;
        Toast.makeText(this, R.string.unmappable, Toast.LENGTH_SHORT).show();
    }

    // ---- Hardware keys ----

    /**
     * While connected: a hardware keyboard is forwarded key for key (whatever has focus), and
     * the volume buttons send volume keys if that setting is on.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (connected && forwardKey(e)) return true;
        return super.dispatchKeyEvent(e);
    }

    private boolean forwardKey(KeyEvent e) {
        int action = e.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false;
        boolean down = action == KeyEvent.ACTION_DOWN;
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            if (!volumeKeys) return false;
            int usage = Keyboard.CONSUMER | (code == KeyEvent.KEYCODE_VOLUME_UP ? Keys.VOLUME_UP
                    : code == KeyEvent.KEYCODE_VOLUME_DOWN ? Keys.VOLUME_DOWN : Keys.MUTE);
            if (!down) {
                keyboard.release(usage);
            } else if (e.getRepeatCount() == 0) {
                keyboard.press(usage);
            }
            return true;
        }
        // Only real keyboards: not the IME (virtual) or the phone's own buttons.
        InputDevice dev = e.getDevice();
        if (dev == null || dev.isVirtual()
                || dev.getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
            return false;
        }
        int usage = Keys.forKeyCode(code);
        if (usage == 0) return false;
        if (!down || e.getRepeatCount() == 0) keyboard.hardware(usage, down); // host repeats
        if (!Keys.isModifier(usage)) typeField.clear();
        return true;
    }

    private String statusText(int st) {
        switch (st) {
            case HidService.CONNECTED: return getString(R.string.status_connected);
            case HidService.CONNECTING: return getString(R.string.status_connecting);
            case HidService.PAIRING: return getString(R.string.status_pairing);
            case HidService.UNSUPPORTED: return "";
        }
        if (!hasPermissions()) return getString(R.string.status_permission);
        if (hid != null && !hid.bluetoothOn()) return getString(R.string.status_bt_off);
        if (hid != null && hid.message() != 0) return getString(hid.message());
        return getString(R.string.status_idle);
    }

    // ---- Settings ----

    /** The settings dialog: one tile per setting, each applied and saved as it changes. */
    private void showSettings() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        list.setPadding(pad, pad / 2, pad, 0);

        addSwitch(list, R.string.natural_scroll, R.string.natural_scroll_summary,
                HidService.naturalScroll(this), on -> {
                    HidService.setNaturalScroll(this, on);
                    sender.naturalScroll = on;
                });
        addSwitch(list, R.string.volume_keys, R.string.volume_keys_summary, volumeKeys, on -> {
            HidService.setVolumeKeys(this, on);
            volumeKeys = on;
        });

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings)
                .setView(scroll)
                .setPositiveButton(R.string.done, null)
                .show();
    }

    private interface OnToggle {
        void set(boolean on);
    }

    /** An on/off setting: tapping anywhere on its tile flips the switch. */
    private void addSwitch(LinearLayout list, int title, int summary, boolean on, OnToggle out) {
        View row = getLayoutInflater().inflate(R.layout.setting_switch, list, false);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        ((TextView) row.findViewById(R.id.summary)).setText(summary);
        Switch toggle = row.findViewById(R.id.toggle);
        toggle.setChecked(on);
        row.setOnClickListener(v -> {
            toggle.toggle();
            out.set(toggle.isChecked());
        });
        list.addView(row);
    }

    // ---- Host picker ----

    @SuppressLint("MissingPermission") // checked by hasPermissions()
    private void pickHost() {
        if (hid == null || hid.state() == HidService.UNSUPPORTED) return;
        if (!hasPermissions()) {
            requestPermissions(PERMISSIONS, REQ_PERMISSIONS);
            return;
        }
        if (!hid.bluetoothOn()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE);
            return;
        }
        List<BluetoothDevice> hosts = bondedHosts();
        String[] labels = new String[hosts.size() + 1];
        for (int i = 0; i < hosts.size(); i++) labels[i] = HidService.name(hosts.get(i));
        labels[hosts.size()] = getString(R.string.pair_new);
        new AlertDialog.Builder(this)
                .setTitle(R.string.choose_host)
                .setItems(labels, (d, which) -> {
                    if (hid == null) return;
                    if (which < hosts.size()) {
                        hid.connect(hosts.get(which));
                    } else {
                        pairNew();
                    }
                })
                .show();
    }

    private void pairNew() {
        hid.pair();
        startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                        .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_SECONDS),
                REQ_DISCOVERABLE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_DISCOVERABLE && resultCode == RESULT_CANCELED) {
            // The service may not be re-bound yet; handle it on connect.
            if (hid != null) {
                if (hid.state() == HidService.PAIRING) hid.disconnect();
            } else {
                cancelPairing = true;
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        maybeAutoConnect();
        refresh();
    }

    // ---- Bonded devices ----

    private static String[] permissions() {
        List<String> p = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
            p.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            p.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return p.toArray(new String[0]);
    }

    private boolean hasPermissions() {
        for (String p : PERMISSIONS) {
            // Notifications are optional: the service runs without its notification shown.
            if (!p.equals(Manifest.permission.POST_NOTIFICATIONS)
                    && checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /** Bonded devices, last host first, then computers, then everything else. */
    private List<BluetoothDevice> bondedHosts() {
        List<BluetoothDevice> out = new ArrayList<>();
        if (!hasPermissions()) return out;
        BluetoothAdapter adapter = getSystemService(BluetoothManager.class).getAdapter();
        if (adapter == null) return out;
        try {
            out.addAll(adapter.getBondedDevices());
        } catch (SecurityException e) {
            return out;
        }
        String last = HidService.lastHost(this);
        out.sort((a, b) -> rank(a, last) - rank(b, last));
        return out;
    }

    private static int rank(BluetoothDevice d, String last) {
        if (d.getAddress().equals(last)) return 0;
        return isComputer(d) ? 1 : 2;
    }

    private static boolean isComputer(BluetoothDevice d) {
        try {
            BluetoothClass c = d.getBluetoothClass();
            return c != null && c.getMajorDeviceClass() == BluetoothClass.Device.Major.COMPUTER;
        } catch (SecurityException e) {
            return false;
        }
    }

    private BluetoothDevice lastHost() {
        String last = HidService.lastHost(this);
        if (last == null) return null;
        for (BluetoothDevice d : bondedHosts()) {
            if (d.getAddress().equals(last)) return d;
        }
        return null; // unpaired since
    }

    private boolean hasBondedComputer() {
        if (HidService.lastHost(this) != null && lastHost() != null) return true;
        for (BluetoothDevice d : bondedHosts()) {
            if (isComputer(d)) return true;
        }
        return false;
    }
}
