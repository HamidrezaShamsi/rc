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
import android.widget.TextView;
import android.widget.Toast;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;

import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final String PREFS = "fpv_viewer_prefs";
    private static final String KEY_URL = "stream_url";
    /** Listen on port 5600 for UDP stream the Pi sends to this device. */
    private static final String DEFAULT_URL = "udp://@:5600";

    private SurfaceView surfaceView;
    private View controls;
    private TextView statusText;
    private EditText urlInput;
    private Button startButton;

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
        statusText = findViewById(R.id.status_text);
        urlInput = findViewById(R.id.url_input);
        startButton = findViewById(R.id.connect_button);

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = prefs.getString(KEY_URL, DEFAULT_URL);
        urlInput.setText(saved);
        if (saved.equals(DEFAULT_URL)) {
            statusText.setText(R.string.instruction_listen);
        }

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = urlInput.getText().toString().trim();
                if (url.isEmpty()) {
                    url = DEFAULT_URL;
                    urlInput.setText(url);
                }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_URL, url).apply();
                statusText.setText(R.string.status_listening);
                startPlayback(url);
                scheduleControlsHide();
            }
        });

        // Small cache (100 ms) so VLC doesn't treat first UDP gap as end-of-stream (which causes one-frame-then-stop).
        ArrayList<String> options = new ArrayList<String>();
        options.add("--network-caching=100");
        options.add("--live-caching=100");
        options.add("--clock-jitter=0");
        options.add("--clock-synchro=0");
        options.add("--drop-late-frames");
        options.add("--skip-frames");
        options.add("--file-caching=0");
        options.add("--disc-caching=0");
        options.add("--demux=h264");
        libVLC = new LibVLC(this, options);
        mediaPlayer = new MediaPlayer(libVLC);

        mediaPlayer.setEventListener(new MediaPlayer.EventListener() {
            @Override
            public void onEvent(MediaPlayer.Event event) {
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        switch (event.type) {
                            case MediaPlayer.Event.Playing:
                                statusText.setText(R.string.status_playing);
                                break;
                            case MediaPlayer.Event.Stopped:
                                statusText.setText(R.string.status_stopped);
                                break;
                            case MediaPlayer.Event.EndReached:
                                // Live UDP has no real end; VLC often fires this after one frame. Resume playback.
                                if (mediaPlayer != null) {
                                    try {
                                        mediaPlayer.play();
                                    } catch (Exception ignored) {
                                        statusText.setText(R.string.status_stopped);
                                    }
                                } else {
                                    statusText.setText(R.string.status_stopped);
                                }
                                break;
                            case MediaPlayer.Event.EncounteredError:
                                statusText.setText(R.string.status_error);
                                Toast.makeText(MainActivity.this, R.string.toast_no_stream, Toast.LENGTH_LONG).show();
                                break;
                            default:
                                break;
                        }
                    }
                });
            }
        });

        IVLCVout vout = mediaPlayer.getVLCVout();
        vout.setVideoView(surfaceView);
        vout.attachViews();

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
        }
        try {
            Uri uri = Uri.parse(url);
            Media media = new Media(libVLC, uri);
            // Prefer software decode for raw H.264 UDP; HW decode often shows black on some devices.
            media.setHWDecoderEnabled(false, false);
            media.addOption(":network-caching=100");
            media.addOption(":live-caching=100");
            media.addOption(":clock-jitter=0");
            media.addOption(":clock-synchro=0");
            media.addOption(":drop-late-frames");
            media.addOption(":skip-frames");
            media.addOption(":demux=h264");
            media.addOption(":fullscreen");
            mediaPlayer.setMedia(media);
            media.release();
            mediaPlayer.play();
        } catch (Exception e) {
            statusText.setText(R.string.status_error);
            Toast.makeText(this, R.string.toast_cannot_open, Toast.LENGTH_LONG).show();
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
