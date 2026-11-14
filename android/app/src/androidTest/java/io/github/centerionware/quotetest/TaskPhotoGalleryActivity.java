package io.github.centerionware.quotetest;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;

import java.io.File;
import java.io.FileOutputStream;

/** Deterministic ACTION_OPEN_DOCUMENT handler serving a synthetic gallery image. */
public final class TaskPhotoGalleryActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bitmap first = null;
        Bitmap second = null;
        try {
            File firstFile = new File(getCacheDir(), "task-photo-fixture-one.jpg");
            File secondFile = new File(getCacheDir(), "task-photo-fixture-two.jpg");
            first = Bitmap.createBitmap(5, 4, Bitmap.Config.ARGB_8888);
            second = Bitmap.createBitmap(5, 4, Bitmap.Config.ARGB_8888);
            first.eraseColor(Color.rgb(176, 96, 32));
            second.eraseColor(Color.rgb(48, 112, 184));
            write(first, firstFile);
            write(second, secondFile);
            String authority = "content://" + getPackageName() + ".task-photo-fixtures/";
            Uri firstUri = Uri.parse(authority + firstFile.getName());
            Uri secondUri = Uri.parse(authority + secondFile.getName());
            ClipData selected = ClipData.newUri(getContentResolver(), "Clockrove task photos", firstUri);
            selected.addItem(new ClipData.Item(secondUri));
            Intent result = new Intent().setDataAndType(firstUri, "image/jpeg");
            result.setClipData(selected);
            result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            setResult(RESULT_OK, result);
        } catch (Exception exception) {
            setResult(RESULT_CANCELED);
        } finally {
            if (first != null) first.recycle();
            if (second != null) second.recycle();
            finish();
        }
    }

    private static void write(Bitmap bitmap, File file) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                throw new IllegalStateException("Could not create synthetic gallery image");
            }
        }
    }
}
