package com.example.audiotx;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.*;

public class MainActivity extends Activity {
    static final int REQ_PERM = 1, REQ_CAP = 2;
    EditText ip, ms, pre;
    Spinner sr, bits, chn;
    Button btn;
    TextView st;
    Handler hd = new Handler(Looper.getMainLooper());

    Runnable poll = new Runnable() {
        public void run() {
            st.setText(StreamService.status);
            btn.setText(StreamService.running ? "Detener" : "Iniciar");
            hd.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(40, 60, 40, 40);

        ip = new EditText(this); ip.setHint("IP del PC"); ip.setText("192.168.1.");
        sr = new Spinner(this);
        sr.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"11025", "22050", "44100"}));
        sr.setSelection(1);      // 22050 Hz por defecto
        bits = new Spinner(this);
        bits.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"8", "16"}));
        bits.setSelection(0);    // 8 bits por defecto
        chn = new Spinner(this);
        chn.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Mono", "Estereo"}));
        chn.setSelection(1);
        ms = new EditText(this); ms.setText("40"); ms.setInputType(2);
        pre = new EditText(this); pre.setText("300"); pre.setInputType(2);
        btn = new Button(this); btn.setText("Iniciar");
        st = new TextView(this); st.setText("Detenido");

        l.addView(label("IP del PC")); l.addView(ip);
        l.addView(label("Frecuencia (Hz)")); l.addView(sr);
        l.addView(label("Bits")); l.addView(bits);
        l.addView(label("Canales")); l.addView(chn);
        l.addView(label("Chunk (ms)")); l.addView(ms);
        l.addView(label("Prebuffer en el PC (ms)")); l.addView(pre);
        l.addView(btn); l.addView(st);

        ScrollView sv = new ScrollView(this);
        sv.addView(l);
        setContentView(sv);

        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { toggle(); }
        });
    }

    @Override protected void onResume() { super.onResume(); hd.post(poll); }
    @Override protected void onPause() { super.onPause(); hd.removeCallbacks(poll); }

    TextView label(String s) { TextView t = new TextView(this); t.setText(s); return t; }

    int num(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); }
        catch (Exception x) { return def; }
    }

    void toggle() {
        if (StreamService.running) {
            stopService(new Intent(this, StreamService.class));
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            String[] p = Build.VERSION.SDK_INT >= 33
                    ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
                    : new String[]{Manifest.permission.RECORD_AUDIO};
            requestPermissions(p, REQ_PERM);
            return;
        }
        MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(m.createScreenCaptureIntent(), REQ_CAP);
    }

    @Override
    public void onRequestPermissionsResult(int rq, String[] p, int[] r) {
        st.setText("Permisos listos. Toca Iniciar de nuevo.");
    }

    @Override
    protected void onActivityResult(int rq, int res, Intent data) {
        if (rq == REQ_CAP && res == RESULT_OK) {
            Intent i = new Intent(this, StreamService.class);
            i.putExtra("host", ip.getText().toString().trim());
            i.putExtra("rate", Integer.parseInt((String) sr.getSelectedItem()));
            i.putExtra("bits", Integer.parseInt((String) bits.getSelectedItem()));
            i.putExtra("ch", chn.getSelectedItemPosition() == 0 ? 1 : 2);
            i.putExtra("chunk", Math.max(10, num(ms, 40)));
            i.putExtra("pre", Math.max(10, num(pre, 300)));
            i.putExtra("code", res);
            i.putExtra("data", data);
            startForegroundService(i);
        }
    }
}
