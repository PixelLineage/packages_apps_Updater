/*
 * SPDX-FileCopyrightText: The PixelLineage Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.updater;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.lineageos.updater.download.DownloadClient;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CertifiedBundleUpdater {

    private static final String TAG = "CertifiedBundleUpdater";

    private static final String BUNDLE_URL =
            "https://raw.githubusercontent.com/PixelLineage/res/refs/heads/main/certified_bundle.bin";
    private static final String BUNDLE_DIR = "/data/certified";
    private static final String BUNDLE_NAME = "certified_bundle.bin";
    private static final String NOTIFICATION_CHANNEL = "certified_bundle_notification_channel";
    private static final int NOTIFICATION_ID = 17;
    private static final byte[] MAGIC = new byte[] {
            'P', 'L', 'C', 'E', 'R', 'T', '0', '1',
    };

    private static final String SETTING_FINGERPRINT = "certified_build_fingerprint";
    private static final String SETTING_SECURITY_PATCH = "certified_build_security_patch";
    private static final String SETTING_INITIAL_SDK = "certified_build_initial_sdk";
    private static final AtomicBoolean sDownloadRunning = new AtomicBoolean();

    private CertifiedBundleUpdater() {
    }

    public static void maybeUpdate(Context context, String json) {
        final int remoteVersion;
        try {
            remoteVersion = getRemoteVersion(json);
        } catch (JSONException e) {
            Log.e(TAG, "Could not read certified metadata", e);
            return;
        }

        if (remoteVersion <= 0) {
            return;
        }

        File bundle = getBundleFile();
        if (getLocalVersion(bundle) == remoteVersion) {
            //noinspection ResultOfMethodCallIgnored
            bundle.setReadable(true, false);
            applySettings(context, bundle);
            return;
        }

        if (!sDownloadRunning.compareAndSet(false, true)) {
            return;
        }

        downloadBundle(context.getApplicationContext(), remoteVersion);
    }

    private static File getBundleFile() {
        return new File(BUNDLE_DIR, BUNDLE_NAME);
    }

    private static int getRemoteVersion(String json) throws JSONException {
        String trimmed = json == null ? "" : json.trim();
        if (trimmed.startsWith("[")) {
            return getCertifiedVersion(new JSONArray(trimmed));
        }

        JSONObject root = new JSONObject(trimmed);
        int version = getCertifiedVersion(root);
        if (version > 0) {
            return version;
        }

        return getCertifiedVersion(root.optJSONArray("response"));
    }

    private static int getCertifiedVersion(JSONArray response) {
        if (response == null) {
            return -1;
        }
        for (int i = 0; i < response.length(); i++) {
            JSONObject update = response.optJSONObject(i);
            if (update == null) {
                continue;
            }
            int version = getCertifiedVersion(update);
            if (version > 0) {
                return version;
            }
        }
        return -1;
    }

    private static int getCertifiedVersion(JSONObject object) {
        Object certified = object.opt("certified");
        return certified instanceof Number ? ((Number) certified).intValue() : -1;
    }

    private static int getLocalVersion(File bundle) {
        try {
            byte[] data = Files.readAllBytes(bundle.toPath());
            BundleReader reader = new BundleReader(data);
            return reader.readVersion();
        } catch (IOException | IllegalArgumentException e) {
            return -1;
        }
    }

    private static void downloadBundle(Context context, int expectedVersion) {
        File destination = getBundleFile();
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            Log.e(TAG, "Could not create certified bundle directory");
            sDownloadRunning.set(false);
            return;
        }

        File tmp = new File(parent, BUNDLE_NAME + "." + UUID.randomUUID() + ".tmp");
        DownloadClient.DownloadCallback callback = new DownloadClient.DownloadCallback() {
            @Override
            public void onFailure(boolean cancelled) {
                sDownloadRunning.set(false);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }

            @Override
            public void onResponse(DownloadClient.Headers headers) {
            }

            @Override
            public void onSuccess() {
                if (getLocalVersion(tmp) != expectedVersion) {
                    Log.e(TAG, "Downloaded certified bundle has unexpected version");
                    sDownloadRunning.set(false);
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    return;
                }
                try {
                    moveReplacing(tmp, destination);
                    //noinspection ResultOfMethodCallIgnored
                    destination.setReadable(true, false);
                    if (applySettings(context, destination)) {
                        showUpdatedNotification(context);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Could not install certified bundle", e);
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                } finally {
                    sDownloadRunning.set(false);
                }
            }
        };

        try {
            DownloadClient client = new DownloadClient.Builder()
                    .setUrl(BUNDLE_URL)
                    .setDestination(tmp)
                    .setDownloadCallback(callback)
                    .build();
            client.start();
        } catch (IOException e) {
            Log.e(TAG, "Could not start certified bundle download", e);
            sDownloadRunning.set(false);
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static void moveReplacing(File source, File destination) throws IOException {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean applySettings(Context context, File bundle) {
        try {
            BundleReader reader = new BundleReader(Files.readAllBytes(bundle.toPath()));
            reader.readVersion();
            Settings.Global.putString(context.getContentResolver(), SETTING_FINGERPRINT,
                    reader.readString());
            Settings.Global.putString(context.getContentResolver(), SETTING_SECURITY_PATCH,
                    reader.readString());
            Settings.Global.putString(context.getContentResolver(), SETTING_INITIAL_SDK,
                    reader.readString());
            return true;
        } catch (IOException | IllegalArgumentException e) {
            Log.e(TAG, "Could not apply certified props", e);
            return false;
        }
    }

    private static void showUpdatedNotification(Context context) {
        NotificationManager notificationManager = context.getSystemService(
                NotificationManager.class);
        NotificationChannel notificationChannel = new NotificationChannel(
                NOTIFICATION_CHANNEL,
                context.getString(R.string.certified_bundle_channel_title),
                NotificationManager.IMPORTANCE_LOW);
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(context,
                NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_system_update)
                .setContentTitle(context.getString(R.string.certified_bundle_updated_title))
                .setAutoCancel(true)
                .setSilent(true);

        notificationManager.createNotificationChannel(notificationChannel);
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
    }

    private static final class BundleReader {
        private final byte[] mData;
        private int mOffset;

        BundleReader(byte[] data) {
            mData = data;
            if (mData.length < MAGIC.length + Integer.BYTES) {
                throw new IllegalArgumentException("bundle too small");
            }
            for (int i = 0; i < MAGIC.length; i++) {
                if (mData[i] != MAGIC[i]) {
                    throw new IllegalArgumentException("bad bundle magic");
                }
            }
            mOffset = MAGIC.length;
        }

        int readVersion() {
            return readInt();
        }

        String readString() {
            return new String(readField(), StandardCharsets.UTF_8);
        }

        private byte[] readField() {
            int length = readInt();
            if (length < 0 || length > mData.length - mOffset) {
                throw new IllegalArgumentException("bad bundle field");
            }
            byte[] field = new byte[length];
            System.arraycopy(mData, mOffset, field, 0, length);
            mOffset += length;
            return field;
        }

        private int readInt() {
            if (mOffset + Integer.BYTES > mData.length) {
                throw new IllegalArgumentException("short bundle integer");
            }
            int value = (mData[mOffset] & 0xff)
                    | ((mData[mOffset + 1] & 0xff) << 8)
                    | ((mData[mOffset + 2] & 0xff) << 16)
                    | ((mData[mOffset + 3] & 0xff) << 24);
            mOffset += Integer.BYTES;
            return value;
        }
    }
}
