package za.web.visser.ancientcam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Launches PlayerActivity when the tablet finishes booting, so the camera comes back
 * on its own after a power blip with no human touch.
 *
 * Launching an Activity straight from BOOT_COMPLETED works on API 28 and below; our
 * target is API 21, so no foreground service is needed. Precondition (Android's
 * "stopped state" rule): the app must be opened manually once after install before
 * BOOT_COMPLETED is ever delivered to it.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "ancient-cam/Boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Log.d(TAG, "BOOT_COMPLETED -> launching PlayerActivity");

        Intent i = new Intent(context, PlayerActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(i);
    }
}
