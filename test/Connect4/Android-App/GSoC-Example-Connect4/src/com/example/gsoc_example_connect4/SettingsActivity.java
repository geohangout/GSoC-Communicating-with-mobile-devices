package com.example.gsoc_example_connect4;

import android.os.Bundle;
import android.preference.PreferenceActivity;
import com.example.gsoc_example_connect4.R;

public class SettingsActivity extends PreferenceActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.preferences);
    }
}
