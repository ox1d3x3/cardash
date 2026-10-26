package xyz.ox1de.cardash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a self-update, bring the dashboard straight back (it is the home screen). */
public class PackageReplaced extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) return;
        try {
            Intent home = new Intent(c, MainActivity.class);
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            c.startActivity(home);
        } catch (Exception ignored) {
            // Android may block background starts; pressing Home brings it back anyway
        }
    }
}
