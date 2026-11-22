package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Boot, a clock or time-zone change and the daily alarm: re-check the season (and re-arm the alarm). */
public class SeasonReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                SeasonReconciler.reconcile(context);
            } finally {
                pending.finish();
            }
        }, "season-reconcile").start();
    }
}
