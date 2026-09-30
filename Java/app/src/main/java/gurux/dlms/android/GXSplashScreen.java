// This file is a part of Gurux Device Framework (GPL v2).
// Modified: safe error handling, on-screen crash report,
// and fallback download of manufacturer settings (plain GET).

package gurux.dlms.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import gurux.dlms.GXDLMSConverter;
import gurux.dlms.manufacturersettings.GXManufacturerCollection;

public class GXSplashScreen extends Activity {

    private static final String CRASH_FILE = "last_crash.txt";
    private static final String BASE = "https://www.gurux.fi/obis/";
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
            if (!hasManufacturerFiles()) {
                try {
                    downloadManufacturers();
                } catch (Exception e) {
                    Log.e("gurux.dlms", "Fallback download failed.", e);
                }
            }
            Intent i = new Intent(GXSplashScreen.this, MainActivity.class);
            startActivity(i);
            finish();
        });
        thread.start();
    }

    private boolean hasManufacturerFiles() {
        String[] files = getFilesDir().list();
        if (files != null) {
            for (String it : files) {
                if (it.endsWith(".obx")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static byte[] get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    private void downloadManufacturers() throws Exception {
        byte[] list = get(BASE + "files.xml");
        String xml = new String(list, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile(">\\s*([A-Za-z0-9_\\-]+\\.obx)\\s*<").matcher(xml);
        while (m.find()) {
            String name = m.group(1);
            try {
                byte[] data = get(BASE + name);
                try (FileOutputStream w = openFileOutput(name, MODE_PRIVATE)) {
                    w.write(data);
                }
            } catch (Exception e) {
                Log.e("gurux.dlms", "Failed to download " + name, e);
            }
        }
        try (FileOutputStream w = openFileOutput("files.xml", MODE_PRIVATE)) {
            w.write(list);
        }
    }
}
