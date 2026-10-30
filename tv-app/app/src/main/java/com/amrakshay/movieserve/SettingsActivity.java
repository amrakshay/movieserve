package com.amrakshay.movieserve;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Where the laptop's address is typed in.
 *
 * <p>Getting out of a text field with a remote control is the awkward part. On a TV the on-screen
 * keyboard covers the screen, and pressing Down inside the field re-opens it rather than moving to
 * the button below. So the keyboard's own Done key saves and closes, which is the natural way out,
 * and the field also names the Save button as its explicit focus target for anyone who gets past
 * the keyboard first.
 */
public class SettingsActivity extends Activity {

    private EditText address;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        address = findViewById(R.id.address);
        address.setText(Prefs.server(this));
        address.setSelection(address.getText().length());   // caret at the end, not over the text
        ((TextView) findViewById(R.id.hint)).setText(getString(R.string.address_hint));

        address.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                save();
                return true;
            }
            return false;
        });

        findViewById(R.id.save).setOnClickListener(v -> save());
        findViewById(R.id.choosePlayer).setOnClickListener(v -> choosePlayer());
        findViewById(R.id.toggleAlwaysAsk).setOnClickListener(v -> {
            Prefs.setAlwaysAsk(this, !Prefs.alwaysAsk(this));
            showPlayerState();
        });
        showPlayerState();
    }

    /**
     * Which player films open in, and whether to ask each time.
     *
     * <p>Shown as plain text under each button rather than a checkbox: a TV remote has no obvious
     * way to toggle a checkbox, and the current state has to be readable from across a room.
     */
    private void showPlayerState() {
        String chosen = Prefs.player(this);
        String label = getString(R.string.default_player_none);
        for (Players.Player player : installedPlayers()) {
            if (player.packageName.equals(chosen)) {
                label = player.label;
                break;
            }
        }
        ((TextView) findViewById(R.id.currentPlayer)).setText(label);
        ((TextView) findViewById(R.id.alwaysAskState)).setText(
                Prefs.alwaysAsk(this) ? R.string.always_ask_on : R.string.always_ask_off);
    }

    private void choosePlayer() {
        List<Players.Player> players = installedPlayers();
        if (players.isEmpty()) {
            Toast.makeText(this, R.string.no_player, Toast.LENGTH_LONG).show();
            return;
        }
        if (players.size() == 1) {
            // Still record it, so the one player is used without a prompt.
            Prefs.setPlayer(this, players.get(0).packageName);
            showPlayerState();
            Toast.makeText(this, getString(R.string.only_one_player, players.get(0).label),
                    Toast.LENGTH_LONG).show();
            return;
        }
        CharSequence[] labels = new CharSequence[players.size()];
        for (int i = 0; i < players.size(); i++) {
            labels[i] = players.get(i).label;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.default_player)
                .setItems(labels, (dialog, which) -> {
                    Prefs.setPlayer(this, players.get(which).packageName);
                    showPlayerState();
                })
                .show();
    }

    /**
     * The players on this TV.
     *
     * <p>Probed with a URL of the shape this app actually opens. The address does not have to
     * resolve: only the scheme and the video type decide who claims the intent.
     */
    private List<Players.Player> installedPlayers() {
        String sample = Prefs.server(this);
        if (sample.isEmpty()) {
            sample = "http://127.0.0.1:18080";
        }
        return Players.available(this, sample + "/stream/sample.mkv");
    }

    private void save() {
        Prefs.setServer(this, address.getText().toString());
        hideKeyboard();
        finish();   // MainActivity reloads in onResume
    }

    /** Without this the keyboard can outlive the screen and sit over the list behind it. */
    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(address.getWindowToken(), 0);
        }
    }
}
