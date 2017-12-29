package lv.ideaportriga.blinds.controller;

import android.annotation.TargetApi;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.support.v7.app.ActionBar;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.view.MenuItem;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

/**
 * A {@link PreferenceActivity} that presents a set of application settings. On
 * handset devices, settings are presented as a single list. On tablets,
 * settings are split by category, with category headers shown to the left of
 * the list of settings.
 * <p>
 * See <a href="http://developer.android.com/design/patterns/settings.html">
 * Android Design: Settings</a> for design guidelines and the <a
 * href="http://developer.android.com/guide/topics/ui/settings.html">Settings
 * API Guide</a> for more information on developing a Settings UI.
 */
@TargetApi(21)
public class SettingsActivity extends AppCompatPreferenceActivity {
    private static final int REQUEST_ENABLE_BT = 1;
    private static Bluetooth mBluetooth;
    private static Map<String, BluetoothDevice> devicesByAddress = new HashMap<>();

    private final BluetoothCallback mBlinds1Callback = new BluetoothCallback() {
        @Override
        public void onChange(final BluetoothDevice d, final int val) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Preference pref = findPreference(d.getAddress());
                    if(pref != null) {
                        pref.setSummary("" + val + "%%");
                    }
                }
            });
        }

        @Override
        public void onMissing(final BluetoothDevice d) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Preference pref = findPreference(d.getAddress());
                    if(pref != null) {
                        pref.setSummary("Missing");
                    }
                }
            });
        }
    };

    protected void setupBluetooth() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "BLE Not Supported",
                    Toast.LENGTH_SHORT).show();
            finish();
        }
        mBluetooth = new Bluetooth(this, (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE), mBlinds1Callback);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mBluetooth.isDisabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            return;
        } else {
            mBluetooth.activate();
        }
        // update screen with new devices?
        PreferenceScreen screen = getPreferenceScreen() != null ? getPreferenceScreen() :
                getPreferenceManager().createPreferenceScreen(this);
        for(BluetoothDevice d : mBluetooth.getDevices()) {
            if(findPreference(d.getAddress()) == null) { // new preference
                ListPreference blindsXPreference = new ListPreference(this);
                CharSequence[] entries = new CharSequence[]{"100% Open", "75% Open", "50% Open", "25% Open", "Close"};
                CharSequence[] entryValues = new CharSequence[]{"100", "75", "50", "25", "0"};
                blindsXPreference.setEntries(entries);
                blindsXPreference.setEntryValues(entryValues);

                blindsXPreference.setTitle("Blinds " + d.getAddress());
                blindsXPreference.setSummary("");
                blindsXPreference.setDialogTitle("Change " + d.getAddress());
                blindsXPreference.setPersistent(true);
                blindsXPreference.setKey(d.getAddress());
                blindsXPreference.setOnPreferenceChangeListener(sBlinds1ValueListener);
                screen.addPreference(blindsXPreference);
            }
            devicesByAddress.put(d.getAddress(), d);
        }
        setPreferenceScreen(screen);
    }

    @Override
    protected void onPause() {
        mBluetooth.passivate();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mBluetooth.passivate();
        super.onDestroy();
    }

    /**
     * A preference value change listener that updates the preference's summary
     * to reflect its new value.
     */
    private static Preference.OnPreferenceChangeListener sBlinds1ValueListener = new Preference.OnPreferenceChangeListener() {
        @Override
        public boolean onPreferenceChange(Preference preference, Object value) {
            // Update bluetooth
            BluetoothDevice d = devicesByAddress.get(preference.getKey());
            if (value != null && d != null) {
                mBluetooth.write(d, Integer.parseInt(value.toString()));
            }
            return true;
        }
    };

    /**
     * Helper method to determine if the device has an extra-large screen. For
     * example, 10" tablets are extra-large.
     */
    private static boolean isXLargeTablet(Context context) {
        return (context.getResources().getConfiguration().screenLayout
                & Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_XLARGE;
    }

    /**
     * Binds a preference's summary to its value. More specifically, when the
     * preference's value is changed, its summary (line of text below the
     * preference title) is updated to reflect the value. The summary is also
     * immediately updated upon calling this method. The exact display format is
     * dependent on the type of preference.
     *
     * @see #sBlinds1ValueListener
     */
    private static void bindPreferenceSummaryToValue(Preference preference) {
        // Set the listener to watch for value changes.
        preference.setOnPreferenceChangeListener(sBlinds1ValueListener);

        // Trigger the listener immediately with the preference's
        // current value.
        sBlinds1ValueListener.onPreferenceChange(preference,
                PreferenceManager
                        .getDefaultSharedPreferences(preference.getContext())
                        .getString(preference.getKey(), ""));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupBluetooth();
        setupActionBar();
    }

    /**
     * Set up the {@link android.app.ActionBar}, if the API is available.
     */
    private void setupActionBar() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            // Show the Up button in the action bar.
            actionBar.setDisplayHomeAsUpEnabled(false);
        }
    }

}
