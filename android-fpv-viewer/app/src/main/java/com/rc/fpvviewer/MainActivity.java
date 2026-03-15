package com.rc.fpvviewer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.DhcpInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;

import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS = "fpv_viewer_prefs";
    private static final String KEY_URL = "stream_url";
    private static final String KEY_FILL_MODE = "fill_mode";
    private static final String KEY_CACHE_MS = "cache_ms";

    private static final String DEFAULT_URL = "udp://@:5600";
    private static final String PI_CONTROL_URL = "http://10.42.0.1:8080/api/stream-config";

    private SurfaceView surfaceView;
    private View controls;
    private TextView statusText;
    private TextView metricsText;
    private EditText urlInput;
    private Button startButton;
    private Button stopButton;
    private Button settingsButton;
    private Switch fillModeSwitch;

    private ScrollView settingsPanel;
    private SeekBar fpsSeek;
    private SeekBar bitrateSeek;
    private SeekBar intraSeek;
    private SeekBar cacheSeek;
    private Switch mpegtsSwitch;
    private TextView fpsValue;
    private TextView bitrateValue;
    private TextView intraValue;
    private TextView cacheValue;
    private Button applyPiSettingsButton;
    private Button updateButton;

    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;

    private String currentPlaybackUrl;
    private String currentState = "idle";
    private boolean userStopped = true;
    private boolean reconnectPending = false;
    private boolean restartingPlayback = false;

    private long lastStatsWallMs = 0;
    private long lastDisplayedPictures = -1;
    private double fpsEstimate = 0.0;
    private long pingMs = -1;

    private int receiverCacheMs = 10;

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

        bindViews();

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String savedUrl = prefs.getString(KEY_URL, DEFAULT_URL);
        urlInput.setText(savedUrl);
        receiverCacheMs = prefs.getInt(KEY_CACHE_MS, 300);
        fillModeSwitch.setChecked(prefs.getBoolean(KEY_FILL_MODE, true));

        statusText.setText(R.string.instruction_listen);
        setUiState(false);

        initButtons();
        initSettingsPanel();

        libVLC = new LibVLC(this, buildVlcOptions(receiverCacheMs));
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

        surfaceView.post(new Runnable() {
            @Override
            public void run() {
                applyVideoLayoutMode();
            }
        });

        enterImmersiveMode();
        uiHandler.post(metricsUpdater);
        uiHandler.post(pingUpdater);
        scheduleControlsHide();
    }

    private void bindViews() {
        surfaceView = findViewById(R.id.video_surface);
        controls = findViewById(R.id.controls);
        statusText = findViewById(R.id.status_text);
        metricsText = findViewById(R.id.metrics_text);
        urlInput = findViewById(R.id.url_input);
        startButton = findViewById(R.id.start_button);
        stopButton = findViewById(R.id.stop_button);
        settingsButton = findViewById(R.id.settings_button);
        fillModeSwitch = findViewById(R.id.fill_mode_switch);

        settingsPanel = findViewById(R.id.settings_panel);
        fpsSeek = findViewById(R.id.fps_seek);
        bitrateSeek = findViewById(R.id.bitrate_seek);
        intraSeek = findViewById(R.id.intra_seek);
        cacheSeek = findViewById(R.id.cache_seek);
        mpegtsSwitch = findViewById(R.id.mpegts_switch);
        fpsValue = findViewById(R.id.fps_value);
        bitrateValue = findViewById(R.id.bitrate_value);
        intraValue = findViewById(R.id.intra_value);
        cacheValue = findViewById(R.id.cache_value);
        applyPiSettingsButton = findViewById(R.id.apply_pi_button);
        updateButton = findViewById(R.id.update_button);
    }

    private void initButtons() {
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

        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean show = settingsPanel.getVisibility() != View.VISIBLE;
                settingsPanel.setVisibility(show ? View.VISIBLE : View.GONE);
                settingsButton.setText(show ? R.string.button_hide_settings : R.string.button_settings);
                if (show) {
                    scheduleControlsHide();
                }
            }
        });

        fillModeSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_FILL_MODE, fillModeSwitch.isChecked()).apply();
                applyVideoLayoutMode();
            }
        });

        updateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startUpdate();
            }
        });
    }

    private void initSettingsPanel() {
        // FPS 24..120
        fpsSeek.setMax(120 - 24);
        fpsSeek.setProgress(60 - 24);
        // Bitrate 2..12 Mbps
        bitrateSeek.setMax(12 - 2);
        bitrateSeek.setProgress(6 - 2);
        // Intra 1..30
        intraSeek.setMax(30 - 1);
        intraSeek.setProgress(10 - 1);
        // Receiver cache 80..500ms
        cacheSeek.setMax(200);
        cacheSeek.setProgress(Math.max(0, Math.min(200, receiverCacheMs)));
        // MPEG-TS default true
        mpegtsSwitch.setChecked(true);

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateSettingLabels();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                updateSettingLabels();
            }
        };

        fpsSeek.setOnSeekBarChangeListener(listener);
        bitrateSeek.setOnSeekBarChangeListener(listener);
        intraSeek.setOnSeekBarChangeListener(listener);
        cacheSeek.setOnSeekBarChangeListener(listener);

        applyPiSettingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applySettingsFromPanel();
            }
        });

        updateSettingLabels();
    }

    private ArrayList<String> buildVlcOptions(int cacheMs) {
        ArrayList<String> options = new ArrayList<String>();
        options.add("--clock-jitter=0");
        options.add("--clock-synchro=0");
        options.add("--drop-late-frames");
        options.add("--skip-frames");
        return options;
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
                applyVideoLayoutMode();
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
            media.addOption(":network-caching=" + receiverCacheMs);
            media.addOption(":live-caching=" + receiverCacheMs);
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
            Object media = mediaPlayer.getMedia();
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

    private void startUpdate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(this, R.string.toast_update_failed, Toast.LENGTH_SHORT).show();
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {
                }
                return;
            }
        }

        updateButton.setEnabled(false);
        Toast.makeText(this, R.string.toast_update_checking, Toast.LENGTH_SHORT).show();

        netExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final String apkUrl = fetchLatestApkUrl();
                if (apkUrl == null) {
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            updateButton.setEnabled(true);
                            Toast.makeText(MainActivity.this, R.string.toast_update_no_release, Toast.LENGTH_LONG).show();
                        }
                    });
                    return;
                }

                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, R.string.toast_update_downloading, Toast.LENGTH_SHORT).show();
                    }
                });

                final File apkFile = downloadApk(apkUrl);
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        updateButton.setEnabled(true);
                        if (apkFile != null && apkFile.exists()) {
                            installApk(apkFile);
                            Toast.makeText(MainActivity.this, R.string.toast_update_ready, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(MainActivity.this, R.string.toast_update_failed, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
    }

    private String fetchLatestApkUrl() {
        HttpURLConnection conn = null;
        try {
            String repo = BuildConfig.GITHUB_REPO;
            if (repo == null || repo.isEmpty()) {
                repo = "rc/rc";
            }
            URL url = new URL("https://api.github.com/repos/" + repo.trim() + "/releases/latest");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            JSONObject json = new JSONObject(sb.toString());
            if (!json.has("assets")) {
                return null;
            }
            org.json.JSONArray assets = json.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.optString("name", "");
                if (name.endsWith(".apk")) {
                    return asset.optString("browser_download_url", null);
                }
            }
            return null;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private File downloadApk(String apkUrl) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(apkUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }

            File cacheDir = getCacheDir();
            File apkFile = new File(cacheDir, "update.apk");

            InputStream in = conn.getInputStream();
            FileOutputStream out = new FileOutputStream(apkFile);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
            out.close();
            in.close();

            return apkFile;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void installApk(File apkFile) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    private void applySettingsFromPanel() {
        receiverCacheMs = cacheSeek.getProgress();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_CACHE_MS, receiverCacheMs).apply();

        if (!userStopped && currentPlaybackUrl != null) {
            playUrl(currentPlaybackUrl);
        }

        final int fps = 24 + fpsSeek.getProgress();
        final int bitrateMbps = 2 + bitrateSeek.getProgress();
        final int intra = 1 + intraSeek.getProgress();
        final String format = mpegtsSwitch.isChecked() ? "mpegts" : "raw";

        applyPiSettingsButton.setEnabled(false);
        applyPiSettingsButton.setText(R.string.button_applying);

        netExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final boolean ok = sendPiConfig(fps, bitrateMbps * 1000000, intra, format);
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        applyPiSettingsButton.setEnabled(true);
                        applyPiSettingsButton.setText(R.string.button_apply_pi);
                        if (ok) {
                            Toast.makeText(MainActivity.this, getString(R.string.toast_pi_applied_detail, fps, bitrateMbps, intra), Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(MainActivity.this, R.string.toast_pi_apply_failed, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
    }

    private boolean sendPiConfig(int fps, int bitrate, int intra, String format) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(PI_CONTROL_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(1200);
            conn.setReadTimeout(3500);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");

            JSONObject payload = new JSONObject();
            payload.put("STREAM_FPS", fps);
            payload.put("STREAM_BITRATE", bitrate);
            payload.put("STREAM_INTRA", intra);
            payload.put("STREAM_FORMAT", format);

            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(conn.getOutputStream()));
            writer.write(payload.toString());
            writer.flush();
            writer.close();

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return false;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String line;
            StringBuilder sb = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            String response = sb.toString();
            return response.contains("\"ok\":true") || response.contains("\"status\":\"ok\"");
        } catch (Exception ignored) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void updateSettingLabels() {
        int fps = 24 + fpsSeek.getProgress();
        int bitrate = 2 + bitrateSeek.getProgress();
        int intra = 1 + intraSeek.getProgress();
        int cache = cacheSeek.getProgress();

        fpsValue.setText(getString(R.string.setting_fps_value, fps));
        bitrateValue.setText(getString(R.string.setting_bitrate_value, bitrate));
        intraValue.setText(getString(R.string.setting_intra_value, intra));
        cacheValue.setText(getString(R.string.setting_cache_value, cache));
    }

    private void setUiState(boolean playingRequested) {
        startButton.setEnabled(!playingRequested);
        stopButton.setEnabled(playingRequested);
    }

    private void scheduleControlsHide() {
        uiHandler.removeCallbacks(hideControls);
        controls.setVisibility(View.VISIBLE);
        uiHandler.postDelayed(hideControls, 4500);
    }

    private void applyVideoLayoutMode() {
        if (mediaPlayer == null) {
            return;
        }
        int w = surfaceView.getWidth() > 0 ? surfaceView.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int h = surfaceView.getHeight() > 0 ? surfaceView.getHeight() : getResources().getDisplayMetrics().heightPixels;
        if (w <= 0 || h <= 0) {
            return;
        }
        // Fill = force display aspect to screen so any stream resolution scales to full screen. Fit = natural aspect (may have bars).
        if (fillModeSwitch.isChecked()) {
            mediaPlayer.setScale(0f);
            mediaPlayer.setAspectRatio(w + ":" + h);
        } else {
            mediaPlayer.setAspectRatio(null);
            mediaPlayer.setScale(0f);
        }
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
        applyVideoLayoutMode();
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
