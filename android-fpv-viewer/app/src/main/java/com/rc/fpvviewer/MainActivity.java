package com.rc.fpvviewer;

import android.app.Activity;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

public class MainActivity extends Activity implements SurfaceHolder.Callback {
    private static final String PREFS = "fpv_viewer_prefs";
    private static final String KEY_URL = "stream_url";
    private static final String DEFAULT_URL = "udp://@:5600";

    private SurfaceView surfaceView;
    private View controls;
    private EditText urlInput;
    private Button connectButton;

    private MediaPlayer mediaPlayer;
    private SurfaceHolder surfaceHolder;
    private boolean surfaceReady = false;
    private String pendingUrl = null;
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
                pendingUrl = url;
                tryStartPlayback();
                scheduleControlsHide();
            }
        });

        surfaceHolder = surfaceView.getHolder();
        surfaceHolder.addCallback(this);
        initializePlayer();

        pendingUrl = urlInput.getText().toString().trim();
        tryStartPlayback();
        enterImmersiveMode();
        scheduleControlsHide();
    }

    private void initializePlayer() {
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                mp.start();
            }
        });
        mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                Toast.makeText(MainActivity.this, "Playback error (" + what + ", " + extra + ")", Toast.LENGTH_SHORT).show();
                return true;
            }
        });
    }

    private void tryStartPlayback() {
        if (!surfaceReady || pendingUrl == null || pendingUrl.isEmpty()) {
            return;
        }
        try {
            mediaPlayer.reset();
            mediaPlayer.setDisplay(surfaceHolder);
            mediaPlayer.setDataSource(pendingUrl);
            mediaPlayer.prepareAsync();
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
            mediaPlayer.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacks(hideControls);
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (IllegalStateException ignored) {
                // Player may not have started yet.
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
        tryStartPlayback();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surfaceReady = true;
        tryStartPlayback();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
    }
}
