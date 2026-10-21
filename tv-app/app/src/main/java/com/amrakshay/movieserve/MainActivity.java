package com.amrakshay.movieserve;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The library list.
 *
 * <p>The app does no playback of its own: picking a film hands its URL to VLC, which already knows
 * how to stream over HTTP and seek within it. That keeps this app small and means every format VLC
 * supports works here for free.
 */
public class MainActivity extends Activity implements MovieAdapter.OnMovieSelected {

    /** VLC for Android. Set explicitly so a film never opens in something unexpected. */
    private static final String VLC_PACKAGE = "org.videolan.vlc";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private RecyclerView list;
    private TextView status;
    private Button statusAction;
    private MovieAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        list = findViewById(R.id.list);
        status = findViewById(R.id.status);
        statusAction = findViewById(R.id.statusAction);

        adapter = new MovieAdapter(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.settings).setOnClickListener(v -> openSettings());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reload every time the screen comes back: the address may have just been changed, or a
        // film copied onto the laptop while the TV sat on this screen.
        load();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    // ---- loading -------------------------------------------------------------------------------

    private void load() {
        String server = Prefs.server(this);
        if (server.isEmpty()) {
            showStatus(getString(R.string.no_server), getString(R.string.set_address), this::openSettings);
            return;
        }
        showStatus(getString(R.string.loading, server), null, null);
        io.execute(() -> {
            try {
                List<Library.Item> items = Library.parse(Api.movies(server));
                List<Library.Row> rows = Library.rows(items);
                ui.post(() -> {
                    if (isFinishing()) return;
                    if (rows.isEmpty()) {
                        showStatus(getString(R.string.empty_library), getString(R.string.retry), this::load);
                    } else {
                        showList(rows);
                    }
                });
            } catch (Exception e) {
                // Anything at all -- laptop asleep, wrong address, something else on that port --
                // is the same story to the viewer: it could not be reached, here is why.
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                ui.post(() -> {
                    if (isFinishing()) return;
                    showStatus(getString(R.string.cannot_reach, server, reason),
                            getString(R.string.retry), this::load);
                });
            }
        });
    }

    private void showList(List<Library.Row> rows) {
        adapter.submit(rows);
        list.setVisibility(View.VISIBLE);
        status.setVisibility(View.GONE);
        statusAction.setVisibility(View.GONE);
        list.requestFocus();
    }

    private void showStatus(String message, String actionLabel, Runnable action) {
        list.setVisibility(View.GONE);
        status.setVisibility(View.VISIBLE);
        status.setText(message);
        if (actionLabel == null) {
            statusAction.setVisibility(View.GONE);
        } else {
            statusAction.setVisibility(View.VISIBLE);
            statusAction.setText(actionLabel);
            statusAction.setOnClickListener(v -> action.run());
            statusAction.requestFocus();
        }
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    // ---- playback ------------------------------------------------------------------------------

    @Override
    public void onMovieSelected(Library.Item item) {
        String url = item.playbackUrl(Prefs.server(this));
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setPackage(VLC_PACKAGE);
        intent.setDataAndType(Uri.parse(url), "video/*");
        // VLC shows this instead of the URL while it buffers.
        intent.putExtra("title", item.name);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            showStatus(getString(R.string.no_vlc), getString(R.string.retry), this::load);
        }
    }
}
