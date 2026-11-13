package io.github.centerionware.quotetest;

import android.app.Activity;
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
        Bitmap bitmap = null;
        try {
            File file = new File(getCacheDir(), "task-photo-fixture.jpg");
            bitmap = Bitmap.createBitmap(5, 4, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.rgb(176, 96, 32));
            try (FileOutputStream output = new FileOutputStream(file)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                    throw new IllegalStateException("Could not create synthetic gallery image");
                }
            }
            Uri uri = Uri.parse(
                    "content://" + getPackageName() + ".task-photo-fixtures/task-photo-fixture.jpg");
            Intent result = new Intent()
                    .setDataAndType(uri, "image/jpeg")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            setResult(RESULT_OK, result);
        } catch (Exception exception) {
            setResult(RESULT_CANCELED);
        } finally {
            if (bitmap != null) bitmap.recycle();
            finish();
        }
    }
}
