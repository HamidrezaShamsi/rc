package com.rc.fpvviewer;

import android.app.Activity;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final String PREFS = "fpv_viewer_prefs";
    private static final String KEY_URL = "stream_url";
    private static final String DEFAULT_URL = "udp://@239.255.42.99:5600";

    private SurfaceView surfaceView;
    private View controls;
    private EditText urlInput;
    private Button connectButton;

    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideControls = new Runnable() {
        @Override
        public void run() {
            controls.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        surfaceView = findViewById(R.id.video_surface);
        controls = findViewById(R.id.controls);
        urlInput = findViewById(R.id.url_input);
        connectButton = findViewById(R.id.connect_button);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        urlInput.setText(prefs.getString(KEY_URL, DEFAULT_URL));

        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = urlInput.getText().toString().trim();
                if (url.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Enter stream URL", Toast.LENGTH_SHORT).show();
                    return;
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, url).apply();
                startPlayback(url);
                scheduleControlsHide();
            }
        });

        ArrayList<String> options = new ArrayList<String>();
        options.add("--network-caching=0");
        options.add("--live-caching=0");
        options.add("--clock-jitter=0");
        options.add("--clock-synchro=0");
        options.add("--drop-late-frames");
        options.add("--skip-frames");
        options.add("--file-caching=0");
        options.add("--disc-caching=0");
        libVLC = new LibVLC(this, options);
        mediaPlayer = new MediaPlayer(libVLC);

        IVLCVout vout = mediaPlayer.getVLCVout();
        vout.setVideoView(surfaceView);
        vout.attachViews();

        startPlayback(urlInput.getText().toString().trim());
        enterImmersiveMode();
        scheduleControlsHide();
    }

    private void startPlayback(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        try {
            mediaPlayer.stop();
        } catch (Exception ignored) {
            // First playback may have nothing to stop.
        }
        try {
            Media media = new Media(libVLC, Uri.parse(url));
            media.setHWDecoderEnabled(true, false);
            media.addOption(":network-caching=0");
            media.addOption(":live-caching=0");
            media.addOption(":clock-jitter=0");
            media.addOption(":clock-synchro=0");
            media.addOption(":drop-late-frames");
            media.addOption(":skip-frames");
            media.addOption(":fullscreen");
            mediaPlayer.setMedia(media);
            media.release();
            mediaPlayer.play();
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open stream URL", Toast.LENGTH_SHORT).show();
        }
    }

    private void scheduleControlsHide() {
        uiHandler.removeCallbacks(hideControls);
        controls.setVisibility(View.VISIBLE);
        uiHandler.postDelayed(hideControls, 2500);
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
                // Ignore transient stop/pause errors.
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacks(hideControls);
        if (mediaPlayer != null) {
            IVLCVout vout = mediaPlayer.getVLCVout();
            vout.detachViews();
            try {
                mediaPlayer.stop();
            } catch (Exception ignored) {
                // Ignore when already stopped.
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
