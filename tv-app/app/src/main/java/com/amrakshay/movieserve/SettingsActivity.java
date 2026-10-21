package com.amrakshay.movieserve;

import android.app.Activity;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;

/** Where the laptop's address is typed in. */
public class SettingsActivity extends Activity {

    private EditText address;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        address = findViewById(R.id.address);
        address.setText(Prefs.server(this));
        ((TextView) findViewById(R.id.hint)).setText(getString(R.string.address_hint));

        findViewById(R.id.save).setOnClickListener(v -> {
            Prefs.setServer(this, address.getText().toString());
            finish();   // MainActivity reloads in onResume
        });
    }
}
