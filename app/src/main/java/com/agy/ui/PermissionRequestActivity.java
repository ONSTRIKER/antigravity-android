package com.agy.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight translucent Activity that requests missing runtime permissions
 * (Contacts, Calendar, Location, SMS, Call Log) on behalf of background MCP/gateway calls
 * and finishes immediately once the user responds.
 */
public final class PermissionRequestActivity extends Activity {

    public static final String EXTRA_PERMISSIONS = "com.agy.EXTRA_PERMISSIONS";
    private static final int REQ_ASSISTANT_PERMS = 9001;

    public static void launchForMissing(Context context, String[] permissions) {
        if (context == null || permissions == null || permissions.length == 0) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String p : permissions) {
            if (p != null && context.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        Intent intent = new Intent(context.getApplicationContext(), PermissionRequestActivity.class);
        intent.putExtra(EXTRA_PERMISSIONS, missing.toArray(new String[0]));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        context.getApplicationContext().startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            finish();
            return;
        }
        String[] perms = getIntent() != null ? getIntent().getStringArrayExtra(EXTRA_PERMISSIONS) : null;
        if (perms == null || perms.length == 0) {
            finish();
            return;
        }
        List<String> toAsk = new ArrayList<>();
        for (String p : perms) {
            if (p != null && checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                toAsk.add(p);
            }
        }
        if (toAsk.isEmpty()) {
            finish();
            return;
        }
        requestPermissions(toAsk.toArray(new String[0]), REQ_ASSISTANT_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        finish();
    }
}
