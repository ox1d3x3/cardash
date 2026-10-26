package xyz.ox1de.cardash;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

/**
 * Results of a self-update session.
 *  PENDING_USER_ACTION: show Android's "Update this app?" screen
 *  SUCCESS:            nothing to do (this process is replaced; PackageReplaced brings home back)
 *  anything else:      tell the user why
 */
public class InstallReceiver extends BroadcastReceiver {

    /** The running activity's updater, if any, so it can update its UI. */
    static volatile Updater active;

    @Override
    public void onReceive(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.startActivity(confirm); } catch (Exception e) { report(c, "Couldn't open the installer: " + e.getMessage()); }
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) return;
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        if (status == PackageInstaller.STATUS_FAILURE_ABORTED) msg = "Update cancelled";
        else if (status == PackageInstaller.STATUS_FAILURE_CONFLICT) msg = "Update refused: signed with a different key";
        else if (status == PackageInstaller.STATUS_FAILURE_STORAGE) msg = "Not enough storage for the update";
        else if (msg == null || msg.isEmpty()) msg = "Update failed (" + status + ")";
        report(c, msg);
    }

    static void report(Context c, String msg) {
        Updater u = active;
        if (u != null) u.installFinished(false, msg);
        Toast.makeText(c, msg, Toast.LENGTH_LONG).show();
    }
}
