package com.amrakshay.movieserve.phone;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The one screen: pick a folder, optionally add a web link, start the server, read the address out
 * to whoever is holding the TV remote.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_TREE = 1;
    private static final int REQUEST_NOTIFICATIONS = 2;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView folderLabel;
    private TextView countLabel;
    private TextView addressLabel;
    private TextView stateLabel;
    private EditText urlField;
    private Button toggle;

    /** The service tells us when it starts, stops or fails to bind. */
    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String error = intent.getStringExtra(ServerService.EXTRA_ERROR);
            if (error != null) {
                Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
            }
            render();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        folderLabel = findViewById(R.id.folder);
        countLabel = findViewById(R.id.count);
        addressLabel = findViewById(R.id.address);
        stateLabel = findViewById(R.id.state);
        urlField = findViewById(R.id.url);
        toggle = findViewById(R.id.toggle);

        findViewById(R.id.battery).setOnClickListener(v -> askToIgnoreBatteryOptimisation());
        findViewById(R.id.pickFolder).setOnClickListener(v -> pickFolder());
        findViewById(R.id.saveUrl).setOnClickListener(v -> saveUrl());
        toggle.setOnClickListener(v -> toggleServer());

        urlField.setText(Prefs.videoUrl(this));
        askForNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(ServerService.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        render();
    }

    @Override
    protected void onPause() {
        unregisterReceiver(stateReceiver);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    // ---- actions -----------------------------------------------------------------------------

    private void pickFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_TREE);
        } catch (Exception e) {
            Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_TREE || resultCode != RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        Uri tree = data.getData();
        // Without taking the permission persistably, the folder is readable until the app restarts
        // and then silently is not.
        getContentResolver().takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Prefs.setTree(this, tree);
        if (ServerService.isRunning()) {
            ServerService.start(this);      // picks up the new folder without a restart
        }
        render();
    }

    private void saveUrl() {
        String typed = urlField.getText().toString();
        if (!typed.trim().isEmpty() && !Prefs.isPlayableUrl(Prefs.normalise(typed))) {
            Toast.makeText(this, R.string.bad_url, Toast.LENGTH_LONG).show();
            return;
        }
        Prefs.setVideoUrl(this, typed);
        urlField.setText(Prefs.videoUrl(this));
        if (ServerService.isRunning()) {
            ServerService.start(this);
        }
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
        render();
    }

    private void toggleServer() {
        if (ServerService.isRunning()) {
            ServerService.stop(this);
        } else {
            if (Prefs.tree(this) == null && !Prefs.isPlayableUrl(Prefs.videoUrl(this))) {
                Toast.makeText(this, R.string.nothing_to_serve, Toast.LENGTH_LONG).show();
                return;
            }
            ServerService.start(this);
        }
        // The service broadcasts when it has actually changed state; this is just so the button
        // does not look stuck in the meantime.
        ui.postDelayed(this::render, 400);
    }

    /**
     * Whether Android will leave the server alone while the screen is off.
     *
     * <p>Measured on a OnePlus 7 without the exemption: under deep Doze a request took up to eight
     * seconds, which is long enough to stall playback. The foreground service and wake lock keep
     * the server alive either way; this is about how quickly it answers.
     */
    private boolean isExemptFromDoze() {
        PowerManager power = getSystemService(PowerManager.class);
        return power != null && power.isIgnoringBatteryOptimizations(getPackageName());
    }

    /**
     * Ask Android to exempt this app. The owner can refuse, and everything still works, so this is
     * offered rather than demanded, and the button disappears once it is granted.
     */
    private void askToIgnoreBatteryOptimisation() {
        try {
            // The settings list, not the direct "grant me this" dialog: the latter is restricted
            // and gets apps removed from Play, and this is clearer about what is being changed.
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            Toast.makeText(this, R.string.battery_why, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, R.string.battery_why, Toast.LENGTH_LONG).show();
        }
    }

    private void askForNotificationPermission() {
        // From Android 13 a foreground service may run without its notification being shown, which
        // would leave the owner no way to see or stop the server.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
    }

    // ---- rendering ---------------------------------------------------------------------------

    private void render() {
        Uri tree = Prefs.tree(this);
        folderLabel.setText(tree == null ? getString(R.string.no_folder) : readableFolder(tree));

        boolean running = ServerService.isRunning();
        toggle.setText(running ? R.string.stop_server : R.string.start_server);
        findViewById(R.id.battery).setVisibility(isExemptFromDoze() ? View.GONE : View.VISIBLE);

        String url = Net.serverUrl();
        if (running) {
            stateLabel.setText(R.string.serving);
            addressLabel.setText(url == null ? getString(R.string.no_network) : url);
            addressLabel.setVisibility(View.VISIBLE);
        } else {
            stateLabel.setText(R.string.stopped);
            addressLabel.setVisibility(View.GONE);
        }

        countLabel.setText(R.string.counting);
        io.execute(() -> {
            Library library = new Library(this, Prefs.tree(this),
                    Prefs.isPlayableUrl(Prefs.videoUrl(this)) ? Prefs.videoUrl(this) : null);
            List<Library.Entry> entries = library.entries();
            int videos = 0;
            for (Library.Entry entry : entries) {
                if (entry.content != null) {
                    videos++;
                }
            }
            boolean hasLink = entries.size() > videos;
            final String text = getString(R.string.count_format, videos)
                    + (hasLink ? getString(R.string.plus_link) : "");
            ui.post(() -> {
                if (!isFinishing()) {
                    countLabel.setText(text);
                }
            });
        });
    }

    /** The tree URI is unreadable; show the last part of its document id, which is the folder. */
    private String readableFolder(Uri tree) {
        String id = Uri.decode(tree.toString());
        int colon = id.lastIndexOf(':');
        String tail = colon >= 0 && colon + 1 < id.length() ? id.substring(colon + 1) : id;
        return tail.isEmpty() ? getString(R.string.whole_storage) : tail;
    }
}
