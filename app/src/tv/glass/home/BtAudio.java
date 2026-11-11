package tv.glass.home;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import java.util.ArrayList;
import java.util.List;

// Headphones and speakers: the paired audio devices, which one is connected, and one press to connect or
// disconnect, which Fire TV's own settings make a long walk. Android 9 has no public call for this; Bluetooth's
// A2DP service has connect() and disconnect(), which apps have long used. If Fire OS ever refuses them, the
// page says so and offers Fire TV's Bluetooth settings instead.
final class BtAudio {
    private BtAudio() {}

    interface Ready { void ready(BluetoothA2dp a2dp); }

    static BluetoothA2dp a2dp;

    static void withA2dp(Context c, final Ready r) {
        if (a2dp != null) { r.ready(a2dp); return; }
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null || !ad.isEnabled()) { r.ready(null); return; }
        ad.getProfileProxy(c.getApplicationContext(), new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                a2dp = (BluetoothA2dp) proxy;
                r.ready(a2dp);
            }
            @Override public void onServiceDisconnected(int profile) { a2dp = null; }
        }, BluetoothProfile.A2DP);
    }

    // Paired devices that play sound (headphones, speakers, car kits); not remotes, keyboards or game pads.
    static List<BluetoothDevice> devices() {
        List<BluetoothDevice> out = new ArrayList<>();
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null) return out;
        for (BluetoothDevice d : ad.getBondedDevices()) {
            BluetoothClass k = d.getBluetoothClass();
            boolean audio = k != null && (k.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO
                    || k.hasService(BluetoothClass.Service.AUDIO) || k.hasService(BluetoothClass.Service.RENDER));
            if (audio && d.getType() != BluetoothDevice.DEVICE_TYPE_LE) out.add(d);
        }
        return out;
    }

    static boolean connected(BluetoothA2dp p, BluetoothDevice d) {
        return p != null && p.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED;
    }

    // true if Android accepted the request (the connection itself takes a few seconds).
    static boolean set(BluetoothA2dp p, BluetoothDevice d, boolean on) {
        if (p == null) return false;
        try {
            if (on) for (BluetoothDevice other : p.getConnectedDevices()) if (!other.equals(d)) call(p, "disconnect", other);
            return call(p, on ? "connect" : "disconnect", d);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean call(BluetoothA2dp p, String name, BluetoothDevice d) throws Exception {
        Object r = BluetoothA2dp.class.getMethod(name, BluetoothDevice.class).invoke(p, d);
        return !(r instanceof Boolean) || (Boolean) r;
    }

    static String name(BluetoothDevice d) {
        String n = d.getName();
        return n == null || n.isEmpty() ? d.getAddress() : n;
    }
}
