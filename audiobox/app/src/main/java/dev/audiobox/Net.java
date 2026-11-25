package dev.audiobox;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

/** Is the box online? (RADIO-SPEC R3) */
final class Net {
    private Net() {}

    static boolean online(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo n = cm == null ? null : cm.getActiveNetworkInfo();
        return n != null && n.isConnected();
    }
}
