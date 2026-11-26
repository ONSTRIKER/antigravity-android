package com.agy.util;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;

/**
 * Resolves Storage Access Framework (SAF) tree URIs to real filesystem paths.
 */
public final class DocumentsContractHelper {

    private static final String TAG = "DocumentsContractHelper";

    private DocumentsContractHelper() {
    }

    public static String resolveTreeUriToPath(Context context, Uri treeUri) {
        if (treeUri == null) {
            return null;
        }
        try {
            String documentId = DocumentsContract.getTreeDocumentId(treeUri);
            if (documentId == null) {
                return null;
            }
            String baseStorage = Environment.getExternalStorageDirectory().getAbsolutePath();

            // Handle "raw:/storage/emulated/0/..." URIs returned by Android Downloads provider
            if (documentId.startsWith("raw:")) {
                String rawPath = documentId.substring("raw:".length());
                return rawPath.isEmpty() ? null : rawPath;
            }

            // Format is often "primary:Documents/my-project" or "<volume-uuid>:folder"
            if (documentId.startsWith("primary:")) {
                String subPath = documentId.substring("primary:".length());
                if (subPath.startsWith("/")) {
                    subPath = subPath.substring(1);
                }
                return subPath.isEmpty() ? baseStorage : new File(baseStorage, subPath).getAbsolutePath();
            }

            // Resolve virtual Downloads provider IDs ("msd:...", "msf:...", or numeric IDs)
            if ("com.android.providers.downloads.documents".equals(treeUri.getAuthority())
                    || documentId.startsWith("msd:")
                    || documentId.startsWith("msf:")
                    || documentId.matches("^\\d+$")) {
                String resolved = queryDownloadsDocumentPath(context, treeUri, documentId, baseStorage);
                if (resolved != null) {
                    return resolved;
                }
            }

            String[] parts = documentId.split(":");
            if (parts.length > 1) {
                String volume = parts[0];
                String subPath = parts[1];
                if (subPath.startsWith("/")) {
                    subPath = subPath.substring(1);
                }
                if ("primary".equalsIgnoreCase(volume)) {
                    return subPath.isEmpty() ? baseStorage : new File(baseStorage, subPath).getAbsolutePath();
                }
                File external = new File("/storage/" + volume + "/" + subPath);
                if (external.exists()) {
                    return external.getAbsolutePath();
                }
                return subPath.isEmpty() ? baseStorage : new File(baseStorage, subPath).getAbsolutePath();
            }

            String cleanDocId = documentId.startsWith("/") ? documentId.substring(1) : documentId;
            if (cleanDocId.isEmpty()) {
                return baseStorage;
            }
            File candidate = new File(baseStorage, cleanDocId);
            if (!candidate.exists() && cleanDocId.matches("^\\d+$")) {
                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (downloadsDir != null && downloadsDir.exists()) {
                    return downloadsDir.getAbsolutePath();
                }
            }
            return candidate.getAbsolutePath();
        } catch (Throwable t) {
            Log.w(TAG, "Error resolving tree uri to path: " + treeUri, t);
            return null;
        }
    }

    private static String queryDownloadsDocumentPath(Context context, Uri treeUri, String documentId, String baseStorage) {
        if (context == null) {
            return null;
        }
        android.database.Cursor cursor = null;
        try {
            Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
            cursor = context.getContentResolver().query(
                    docUri,
                    new String[]{"_data", "_display_name"},
                    null,
                    null,
                    null);
            if (cursor != null && cursor.moveToFirst()) {
                int dataIdx = cursor.getColumnIndex("_data");
                if (dataIdx >= 0 && !cursor.isNull(dataIdx)) {
                    String dataPath = cursor.getString(dataIdx);
                    if (dataPath != null && !dataPath.isEmpty()) {
                        return dataPath;
                    }
                }
                int nameIdx = cursor.getColumnIndex("_display_name");
                if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                    String displayName = cursor.getString(nameIdx);
                    if (displayName != null && !displayName.isEmpty()) {
                        File dlCandidate = new File(new File(baseStorage, "Download"), displayName);
                        if (dlCandidate.exists()) {
                            return dlCandidate.getAbsolutePath();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }
}
