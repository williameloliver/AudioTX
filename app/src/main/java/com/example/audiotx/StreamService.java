package com.example.audiotx;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class StreamService extends Service {
    static volatile boolean running = false;
    static volatile String status = "Detenido";
    volatile boolean stop = false;
    volatile Socket sock;
    MediaProjection mp;
    PowerManager.WakeLock wl;
    WifiManager.WifiLock wfl;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent in, int flags, int id) {
        if (in == null || running) return START_NOT_STICKY;

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("tx", "Transmision", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "tx")
                .setContentTitle("AudioTx")
                .setContentText("Transmitiendo audio al PC")
                .setSmallIcon(android.R.drawable.ic_media_play).build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);

        final String host = in.getStringExtra("host");
        final int rate = in.getIntExtra("rate", 22050);
        final int nb = in.getIntExtra("bits", 8);
        final int ch = in.getIntExtra("ch", 2);
        final int chunkMs = in.getIntExtra("chunk", 40);
        final int preMs = in.getIntExtra("pre", 300);

        MediaProjectionManager m = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mp = m.getMediaProjection(in.getIntExtra("code", 0), (Intent) in.getParcelableExtra("data"));
        mp.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() { stop = true; }
        }, null);

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "audiotx:cpu");
        wl.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        wfl = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "audiotx:wifi");
        wfl.acquire();

        running = true;
        stop = false;
        new Thread(new Runnable() {
            public void run() { stream(host, rate, nb, ch, chunkMs, preMs); }
        }).start();
        return START_NOT_STICKY;
    }

    void stream(String host, int rate, int nb, int ch, int chunkMs, int preMs) {
        AudioRecord ar = null;
        try {
            status = "Conectando...";
            sock = new Socket();
            sock.connect(new InetSocketAddress(host, 5000), 3000);
            sock.setTcpNoDelay(true);
            OutputStream o = sock.getOutputStream();

            ByteBuffer h = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN);
            h.put("AUD1".getBytes());
            h.putInt(rate);
            h.put((byte) nb);
            h.put((byte) ch);
            h.putShort((short) chunkMs);
            h.putShort((short) preMs);
            o.write(h.array());

            AudioPlaybackCaptureConfiguration cfg = new AudioPlaybackCaptureConfiguration.Builder(mp)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();

            int mask = ch == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
            int frames = rate * chunkMs / 1000;
            int samples = frames * ch;
            int bufBytes = Math.max(AudioRecord.getMinBufferSize(rate, mask,
                    AudioFormat.ENCODING_PCM_16BIT), samples * 2 * 4);
            ar = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(cfg)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(mask).build())
                    .setBufferSizeInBytes(bufBytes)
                    .build();
            ar.startRecording();
            status = "Transmitiendo " + rate + " Hz, " + nb + " bits, " + (ch == 1 ? "mono" : "estereo");

            short[] buf = new short[samples];
            byte[] out = new byte[nb == 16 ? samples * 2 : samples];
            while (!stop) {
                int n = 0;
                while (n < samples) {
                    int r = ar.read(buf, n, samples - n);
                    if (r <= 0) throw new IOException("Error de lectura: " + r);
                    n += r;
                }
                if (nb == 16) {
                    for (int i = 0; i < samples; i++) {
                        out[2 * i] = (byte) buf[i];
                        out[2 * i + 1] = (byte) (buf[i] >> 8);
                    }
                } else {
                    for (int i = 0; i < samples; i++) out[i] = (byte) ((buf[i] >> 8) + 128);
                }
                o.write(out);
            }
            status = "Detenido";
        } catch (Exception e) {
            if (!stop) status = "Error: " + e.getMessage();
        } finally {
            try { if (ar != null) { ar.stop(); ar.release(); } } catch (Exception e) {}
            running = false;
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        stop = true;
        try { if (sock != null) sock.close(); } catch (Exception e) {}
        try { if (mp != null) mp.stop(); } catch (Exception e) {}
        if (wl != null && wl.isHeld()) wl.release();
        if (wfl != null && wfl.isHeld()) wfl.release();
        running = false;
        super.onDestroy();
    }
}
