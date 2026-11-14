package io.github.centerionware.quotetest;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Arrays;

/** Read-only content URI used by the test gallery picker; contains synthetic pixels only. */
public final class TaskPhotoFixtureProvider extends ContentProvider {
    private static final String AUTHORITY =
            "io.github.centerionware.clockrove.test.task-photo-fixtures";
    private static final java.util.List<String> FIXTURE_NAMES = Arrays.asList(
            "task-photo-fixture-one.jpg", "task-photo-fixture-two.jpg");

    @Override
    public boolean onCreate() {
        return true;
    }

    private File fixture(Uri uri) throws FileNotFoundException {
        if (!"content".equals(uri.getScheme())
                || !AUTHORITY.equals(uri.getAuthority())
                || uri.getPathSegments().size() != 1
                || !FIXTURE_NAMES.contains(uri.getLastPathSegment())) {
            throw new FileNotFoundException("Synthetic photo unavailable");
        }
        try {
            Context context = getContext();
            if (context == null) throw new FileNotFoundException("Synthetic photo unavailable");
            File root = context.getCacheDir().getCanonicalFile();
            File file = new File(root, uri.getLastPathSegment()).getCanonicalFile();
            if (!root.equals(file.getParentFile()) || !file.isFile()) {
                throw new FileNotFoundException("Synthetic photo unavailable");
            }
            return file;
        } catch (java.io.IOException exception) {
            throw new FileNotFoundException("Synthetic photo unavailable");
        }
    }

    @Override
    public String getType(Uri uri) {
        try {
            fixture(uri);
        } catch (FileNotFoundException exception) {
            throw new IllegalArgumentException("Synthetic photo unavailable", exception);
        }
        return "image/jpeg";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only synthetic photo");
        return ParcelFileDescriptor.open(fixture(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Synthetic photo is read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Synthetic photo is read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Synthetic photo is read-only");
    }
}
