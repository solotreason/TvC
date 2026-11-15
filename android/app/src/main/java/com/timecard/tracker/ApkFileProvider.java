package com.timecard.tracker;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Zero-dependency ContentProvider that exposes the downloaded APK update file
 * to Android's system PackageInstaller via a secure content:// URI.
 */
public class ApkFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.timecard.tracker.apkprovider";
    public static final String APK_UPDATE_FILENAME = "Timecard-Tracker-update.apk";
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";

    public static Uri getApkContentUri() {
        return Uri.parse("content://" + AUTHORITY + "/" + APK_UPDATE_FILENAME);
    }

    public static File getApkUpdateFile(android.content.Context context) {
        return new File(context.getFilesDir(), APK_UPDATE_FILENAME);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (getContext() == null) return null;
        File file = getApkUpdateFile(getContext());
        if (!file.exists()) return null;

        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                row[i] = APK_UPDATE_FILENAME;
            } else if (OpenableColumns.SIZE.equals(cols[i])) {
                row[i] = file.length();
            } else {
                row[i] = null;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return APK_MIME_TYPE;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (getContext() == null) {
            throw new FileNotFoundException("Context unavailable");
        }
        File file = getApkUpdateFile(getContext());
        if (!file.exists()) {
            throw new FileNotFoundException("APK update file not found: " + file.getAbsolutePath());
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
