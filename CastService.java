package com.telephonichoudini.s3cast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

public class CastService extends Service {
    public static final String ACTION_STOP = "com.telephonichoudini.s3cast.STOP";
    public static final String EXTRA_RESULT_CODE = "projection_result_code";
    public static final String EXTRA_RESULT_DATA = "projection_result_data";

    private static final String TAG = "S3CAST";
    private static final String HOST = "192.168.4.1";
    private static final int PORT = 7070;
    private static final int TOUCH_PORT = 7071;
    private static final int W = 512;
    private static final int H = 300;
    private static final int FRAME_BYTES = W * H * 2;
    private static final long FRAME_INTERVAL_MS = 200;

    private volatile boolean running;
    private volatile OutputStream out;
    private Socket socket;
    private DatagramSocket touchSocket;
    private final Object writeLock = new Object();
    private final byte[] rgb565 = new byte[FRAME_BYTES];
    private int sequence;
    private long lastFrameAt;

    private HandlerThread captureThread;
    private Handler captureHandler;
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopEverything();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (running) return START_NOT_STICKY;
        if (intent == null) return START_NOT_STICKY;

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= 33) {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        if (resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(71, buildNotification("Casting 512×300 → S3 7B"));
        running = true;
        startSocketLoop();
        startTouchListener();
        startProjection(resultCode, resultData);
        return START_NOT_STICKY;
    }

    private void startProjection(int resultCode, Intent resultData) {
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mgr.getMediaProjection(resultCode, resultData);
        if (projection == null) {
            stopEverything();
            stopSelf();
            return;
        }

        captureThread = new HandlerThread("S3CastCapture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                stopEverything();
                stopSelf();
            }
        }, captureHandler);

        reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);

        int density = getResources().getDisplayMetrics().densityDpi;
        virtualDisplay = projection.createVirtualDisplay(
                "S3 CAST 7B",
                W, H, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.getSurface(), null, captureHandler);
    }

    private void onImageAvailable(ImageReader r) {
        Image image = r.acquireLatestImage();
        if (image == null) return;
        try {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastFrameAt < FRAME_INTERVAL_MS) return;
            lastFrameAt = now;

            OutputStream current = out;
            if (current == null) return;

            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            if (pixelStride < 4) return;

            int di = 0;
            for (int y = 0; y < H; y++) {
                int row = y * rowStride;
                for (int x = 0; x < W; x++) {
                    int i = row + x * pixelStride;
                    int rr = buf.get(i) & 0xFF;
                    int gg = buf.get(i + 1) & 0xFF;
                    int bb = buf.get(i + 2) & 0xFF;
                    int c = ((rr & 0xF8) << 8) | ((gg & 0xFC) << 3) | (bb >> 3);
                    rgb565[di++] = (byte) (c & 0xFF);
                    rgb565[di++] = (byte) ((c >> 8) & 0xFF);
                }
            }
            sendFrame(current);
        } catch (Throwable t) {
            Log.w(TAG, "capture/send failed", t);
            closeSocket();
        } finally {
            image.close();
        }
    }

    private void sendFrame(OutputStream current) throws Exception {
        byte[] h = new byte[16];
        h[0] = 'S'; h[1] = '3'; h[2] = 'F'; h[3] = '1';
        putLe16(h, 4, W);
        putLe16(h, 6, H);
        putLe32(h, 8, FRAME_BYTES);
        putLe32(h, 12, sequence++);
        synchronized (writeLock) {
            current.write(h);
            current.write(rgb565);
            current.flush();
        }
    }

    private void startSocketLoop() {
        new Thread(() -> {
            while (running) {
                if (out == null) {
                    try {
                        Socket s = new Socket();
                        s.setTcpNoDelay(true);
                        s.setSendBufferSize(512 * 1024);
                        s.connect(new InetSocketAddress(HOST, PORT), 900);
                        socket = s;
                        out = new BufferedOutputStream(s.getOutputStream(), 512 * 1024);
                        updateNotification("Connected to S3-CAST • streaming");
                        Log.i(TAG, "connected to receiver");
                    } catch (Exception e) {
                        closeSocket();
                        updateNotification("Waiting for S3-CAST Wi-Fi…");
                        sleep(1000);
                    }
                } else {
                    sleep(400);
                }
            }
        }, "S3CastSocket").start();
    }

    private void startTouchListener() {
        new Thread(() -> {
            try {
                touchSocket = new DatagramSocket(TOUCH_PORT);
                touchSocket.setSoTimeout(1000);
                byte[] buf = new byte[32];
                while (running) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);
                        touchSocket.receive(packet);
                        if (packet.getLength() < 12) continue;
                        int o = packet.getOffset();
                        byte[] p = packet.getData();
                        if (p[o] != 'T' || p[o + 1] != 'C' || p[o + 2] != 'H' || p[o + 3] != '1') continue;
                        int x = le16(p, o + 4);
                        int y = le16(p, o + 6);
                        int state = p[o + 8] & 0xFF;
                        TouchAccessibilityService.remoteTouch(x, y, state);
                    } catch (java.net.SocketTimeoutException ignored) {
                    }
                }
            } catch (Exception e) {
                if (running) Log.w(TAG, "touch UDP stopped", e);
            }
        }, "S3CastTouchUDP").start();
    }

    private void stopEverything() {
        if (!running && projection == null) return;
        running = false;
        closeSocket();
        if (touchSocket != null) {
            touchSocket.close();
            touchSocket = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
        MediaProjection oldProjection = projection;
        projection = null;
        if (oldProjection != null) {
            try { oldProjection.stop(); } catch (Exception ignored) {}
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    private void closeSocket() {
        out = null;
        if (socket != null) {
            try { socket.close(); } catch (Exception ignored) {}
            socket = null;
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel("s3cast", "S3 CAST", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, "s3cast")
                : new Notification.Builder(this);
        return b.setContentTitle("S3 CAST // DEX BRIDGE")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(71, buildNotification(text));
    }

    private static int le16(byte[] a, int o) {
        return (a[o] & 0xFF) | ((a[o + 1] & 0xFF) << 8);
    }
    private static void putLe16(byte[] a, int o, int v) {
        a[o] = (byte) v; a[o + 1] = (byte) (v >> 8);
    }
    private static void putLe32(byte[] a, int o, int v) {
        a[o] = (byte) v; a[o + 1] = (byte) (v >> 8); a[o + 2] = (byte) (v >> 16); a[o + 3] = (byte) (v >> 24);
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { stopEverything(); super.onDestroy(); }
}
