package io.github.centerionware.quotetest;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.util.Objects;

/** Test camera for exercising the real ACTION_IMAGE_CAPTURE and URI grant path. */
public final class TaskPhotoCameraActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bitmap bitmap = null;
        try {
            Uri output;
            if (Build.VERSION.SDK_INT >= 33) {
                output = getIntent().getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri.class);
            } else {
                output = getIntent().getParcelableExtra(MediaStore.EXTRA_OUTPUT);
            }
            if (output == null) throw new IllegalStateException("Camera output URI is missing");

            bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.rgb(32, 128, 96));
            try (OutputStream stream = Objects.requireNonNull(
                    getContentResolver().openOutputStream(output, "w"))) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)) {
                    throw new IllegalStateException("Could not create synthetic camera image");
                }
            }
            setResult(RESULT_OK, new Intent());
        } catch (Exception exception) {
            setResult(RESULT_CANCELED);
        } finally {
            if (bitmap != null) bitmap.recycle();
            finish();
        }
    }
}
