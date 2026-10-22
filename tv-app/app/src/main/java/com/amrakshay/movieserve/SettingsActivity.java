package com.amrakshay.movieserve;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

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
