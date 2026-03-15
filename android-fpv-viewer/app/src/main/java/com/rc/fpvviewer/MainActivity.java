package com.rc.fpvviewer;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.DhcpInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS = "fpv_viewer_prefs";
    private static final String KEY_URL = "stream_url";
    private static final String DEFAULT_URL = "udp://@:5600";

    private SurfaceView surfaceView;
    private View controls;
    private TextView statusText;
    private TextView metricsText;
    private EditText urlInput;
    private Button startButton;
    private Button stopButton;

    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private String currentPlaybackUrl;
    private String currentState = "Idle";
    private boolean userStopped = true;
    private boolean reconnectPending = false;
    private boolean restartingPlayback = false;

    private long lastStatsWallMs = 0;
    private long lastDisplayedPictures = -1;
    private double fpsEstimate = 0.0;
    private long pingMs = -1;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService netExecutor = Executors.newSingleThreadExecutor();

    private final Runnable hideControls = new Runnable() {
        @Override
        public void run() {
            controls.setVisibility(View.GONE);
        }
    };

    private final Runnable metricsUpdater = new Runnable() {
        @Override
        public void run() {
            updateMetrics();
            uiHandler.postDelayed(this, 1000);
        }
    };

    private final Runnable pingUpdater = new Runnable() {
        @Override
        public void run() {
            final String host = resolvePingHost();
            if (host != null) {
                netExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        pingMs = measureReachabilityMs(host, 600);
                    }
                });
            }
            uiHandler.postDelayed(this, 3000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        surfaceView = findViewById(R.id.video_surface);
        controls = findViewById(R.id.controls);
        statusText = findViewById(R.id.status_text);
        metricsText = findViewById(R.id.metrics_text);
        urlInput = findViewById(R.id.url_input);
        startButton = findViewById(R.id.start_button);
        stopButton = findViewById(R.id.stop_button);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = prefs.getString(KEY_URL, DEFAULT_URL);
        urlInput.setText(saved);
        statusText.setText(R.string.instruction_listen);
        setUiState(false);

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = urlInput.getText().toString().trim();
                if (url.isEmpty()) {
                    url = DEFAULT_URL;
                    urlInput.setText(url);
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, url).apply();
                startPlayback(url);
                scheduleControlsHide();
            }
        });

        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopPlayback(true);
                scheduleControlsHide();
            }
        });

        ArrayList<String> options = new ArrayList<String>();
        options.add("--network-caching=300");
        options.add("--live-caching=300");
        options.add("--clock-jitter=0");
        options.add("--clock-synchro=0");
        options.add("--drop-late-frames");
        options.add("--skip-frames");
        libVLC = new LibVLC(this, options);
        mediaPlayer = new MediaPlayer(libVLC);

        mediaPlayer.setEventListener(new MediaPlayer.EventListener() {
            @Override
            public void onEvent(final MediaPlayer.Event event) {
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        handlePlayerEvent(event);
                    }
                });
            }
        });

        IVLCVout vout = mediaPlayer.getVLCVout();
        vout.setVideoView(surfaceView);
        vout.attachViews();

        enterImmersiveMode();
        uiHandler.post(metricsUpdater);
        uiHandler.post(pingUpdater);
        scheduleControlsHide();
    }

    private void handlePlayerEvent(MediaPlayer.Event event) {
        switch (event.type) {
            case MediaPlayer.Event.Opening:
                currentState = getString(R.string.state_opening);
                break;
            case MediaPlayer.Event.Buffering:
                currentState = getString(R.string.state_buffering);
                break;
            case MediaPlayer.Event.Playing:
                currentState = getString(R.string.state_playing);
                reconnectPending = false;
                setUiState(true);
                statusText.setText(R.string.status_playing);
                break;
            case MediaPlayer.Event.Stopped:
                currentState = getString(R.string.state_stopped);
                if (!userStopped && !restartingPlayback) {
                    scheduleReconnect();
                }
                break;
            case MediaPlayer.Event.EndReached:
                currentState = getString(R.string.state_reconnecting);
                if (!userStopped) {
                    scheduleReconnect();
                }
                break;
            case MediaPlayer.Event.EncounteredError:
                currentState = getString(R.string.state_error);
                statusText.setText(R.string.status_error);
                if (!userStopped) {
                    Toast.makeText(this, R.string.toast_no_stream, Toast.LENGTH_SHORT).show();
                    scheduleReconnect();
                }
                break;
            default:
                break;
        }
        updateMetrics();
    }

    private void startPlayback(String url) {
        currentPlaybackUrl = url;
        userStopped = false;
        reconnectPending = false;
        setUiState(true);
        statusText.setText(R.string.status_connecting);
        currentState = getString(R.string.state_connecting);
        playUrl(url);
    }

    private void playUrl(String url) {
        if (url == null || url.isEmpty()) {
            statusText.setText(R.string.status_error);
            return;
        }
        try {
            restartingPlayback = true;
            mediaPlayer.stop();
        } catch (Exception ignored) {
        }
        try {
            Media media = new Media(libVLC, Uri.parse(url));
            media.setHWDecoderEnabled(true, false);
            media.addOption(":network-caching=300");
            media.addOption(":live-caching=300");
            media.addOption(":clock-jitter=0");
            media.addOption(":clock-synchro=0");
            media.addOption(":drop-late-frames");
            media.addOption(":skip-frames");
            media.addOption(":fullscreen");
            mediaPlayer.setMedia(media);
            media.release();
            mediaPlayer.play();
            restartingPlayback = false;
        } catch (Exception e) {
            restartingPlayback = false;
            statusText.setText(R.string.status_error);
            Toast.makeText(this, R.string.toast_cannot_open, Toast.LENGTH_LONG).show();
        }
    }

    private void stopPlayback(boolean explicitUserStop) {
        userStopped = explicitUserStop;
        reconnectPending = false;
        try {
            mediaPlayer.stop();
        } catch (Exception ignored) {
        }
        currentState = getString(R.string.state_stopped);
        statusText.setText(R.string.status_stopped);
        setUiState(false);
        updateMetrics();
    }

    private void scheduleReconnect() {
        if (reconnectPending || userStopped || currentPlaybackUrl == null || currentPlaybackUrl.isEmpty()) {
            return;
        }
        reconnectPending = true;
        statusText.setText(R.string.status_reconnecting);
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (userStopped || currentPlaybackUrl == null) {
                    reconnectPending = false;
                    return;
                }
                playUrl(currentPlaybackUrl);
            }
        }, 250);
    }

    private void updateMetrics() {
        long now = System.currentTimeMillis();
        long displayed = getDisplayedPictures();
        long lost = getLostPictures();

        if (displayed >= 0 && lastDisplayedPictures >= 0 && lastStatsWallMs > 0) {
            long dPics = displayed - lastDisplayedPictures;
            long dMs = now - lastStatsWallMs;
            if (dPics >= 0 && dMs > 0) {
                fpsEstimate = (1000.0 * dPics) / dMs;
            }
        }

        if (displayed >= 0) {
            lastDisplayedPictures = displayed;
            lastStatsWallMs = now;
        }

        String ping = pingMs >= 0 ? (pingMs + " ms") : "--";
        String fps = fpsEstimate > 0 ? String.format(Locale.US, "%.1f", fpsEstimate) : "--";
        String loss = lost >= 0 ? String.valueOf(lost) : "--";

        String info = getString(R.string.metrics_template, currentState, fps, ping, loss);
        metricsText.setText(info);
    }

    private long getDisplayedPictures() {
        Object stats = getMediaStats();
        return readStatsField(stats, "displayedPictures", "i_displayed_pictures");
    }

    private long getLostPictures() {
        Object stats = getMediaStats();
        return readStatsField(stats, "lostPictures", "i_lost_pictures");
    }

    private Object getMediaStats() {
        try {
            Media media = mediaPlayer.getMedia();
            if (media == null) {
                return null;
            }
            Method method = media.getClass().getMethod("getStats");
            return method.invoke(media);
        } catch (Exception ignored) {
            return null;
        }
    }

    private long readStatsField(Object stats, String... names) {
        if (stats == null) {
            return -1;
        }
        for (String name : names) {
            try {
                Field field = stats.getClass().getField(name);
                Object value = field.get(stats);
                if (value instanceof Number) {
                    return ((Number) value).longValue();
                }
            } catch (Exception ignored) {
            }
        }
        return -1;
    }

    private String resolvePingHost() {
        if (currentPlaybackUrl != null && currentPlaybackUrl.startsWith("udp://") && !currentPlaybackUrl.startsWith("udp://@")) {
            try {
                Uri uri = Uri.parse(currentPlaybackUrl);
                String host = uri.getHost();
                if (host != null && !host.isEmpty()) {
                    return host;
                }
            } catch (Exception ignored) {
            }
        }

        try {
            WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) {
                return null;
            }
            DhcpInfo dhcpInfo = wifi.getDhcpInfo();
            if (dhcpInfo == null || dhcpInfo.gateway == 0) {
                return null;
            }
            return Formatter.formatIpAddress(dhcpInfo.gateway);
        } catch (Exception ignored) {
            return null;
        }
    }

    private long measureReachabilityMs(String host, int timeoutMs) {
        long start = System.currentTimeMillis();
        try {
            InetAddress address = InetAddress.getByName(host);
            boolean ok = address.isReachable(timeoutMs);
            if (!ok) {
                return -1;
            }
            return System.currentTimeMillis() - start;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private void setUiState(boolean playingRequested) {
        startButton.setEnabled(!playingRequested);
        stopButton.setEnabled(playingRequested);
    }

    private void scheduleControlsHide() {
        uiHandler.removeCallbacks(hideControls);
        controls.setVisibility(View.VISIBLE);
        uiHandler.postDelayed(hideControls, 3500);
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            controls.setVisibility(View.VISIBLE);
            scheduleControlsHide();
            enterImmersiveMode();
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mediaPlayer != null) {
            try {
                mediaPlayer.pause();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacks(hideControls);
        uiHandler.removeCallbacks(metricsUpdater);
        uiHandler.removeCallbacks(pingUpdater);
        netExecutor.shutdownNow();
        if (mediaPlayer != null) {
            IVLCVout vout = mediaPlayer.getVLCVout();
            vout.detachViews();
            try {
                mediaPlayer.stop();
            } catch (Exception ignored) {
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (libVLC != null) {
            libVLC.release();
            libVLC = null;
        }
    }
}
