// This file is a part of Gurux Device Framework (GPL v2).
// Modified: safe error handling + on-screen crash report.

package gurux.dlms.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

import gurux.dlms.GXDLMSConverter;
import gurux.dlms.manufacturersettings.GXManufacturerCollection;

public class GXSplashScreen extends Activity {

    private static final String CRASH_FILE = "last_crash.txt";
    private TextView loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final File crash = new File(getFilesDir(), CRASH_FILE);
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try (FileOutputStream out = new FileOutputStream(crash)) {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                out.write(sw.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
            if (def != null) {
                def.uncaughtException(t, e);
            }
        });

        if (crash.exists()) {
            String text;
            try (FileInputStream in = new FileInputStream(crash)) {
                byte[] b = new byte[(int) crash.length()];
                int n = in.read(b);
                text = new String(b, 0, Math.max(n, 0), StandardCharsets.UTF_8);
            } catch (Exception ex) {
                text = String.valueOf(ex);
            }
            crash.delete();
            TextView tv = new TextView(this);
            tv.setTextIsSelectable(true);
            tv.setPadding(24, 48, 24, 24);
            tv.setText("CRASH REPORT (open the app again to retry):\n\n" + text);
            ScrollView sv = new ScrollView(this);
            sv.addView(tv);
            setContentView(sv);
            return;
        }

        setContentView(R.layout.activity_splash_screen);
        loading = findViewById(R.id.loading);
        Thread thread = new Thread(() -> {
            if (GXDLMSConverter.isFirstRun(this)) {
                runOnUiThread(() -> loading.setText(R.string.loading_obis_codes));
                GXDLMSConverter c = new GXDLMSConverter();
                try {
                    c.update(this);
                } catch (Exception e) {
                    Log.e("gurux.dlms", "Failed to read OBIS codes from the server.", e);
                }
            }
            try {
                GXManufacturerCollection man = new GXManufacturerCollection();
                runOnUiThread(() -> loading.setText(R.string.loading_manufacturer_settings));
                if (GXManufacturerCollection.isFirstRun(this) ||
                        man.isUpdatesAvailable(this)) {
                    GXManufacturerCollection.updateManufactureSettings(this);
                }
            } catch (Exception e) {
                Log.e("gurux.dlms", "Failed to read manufacturer settings from the server.", e);
            }
            Intent i = new Intent(GXSplashScreen.this, MainActivity.class);
            startActivity(i);
            finish();
        });
        thread.start();
    }
}
