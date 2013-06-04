package com.fizzbuzz.android.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import com.fizzbuzz.android.dagger.InjectingBroadcastReceiver;

import java.util.HashSet;
import java.util.Set;

/**
 * A BroadcastReceiver which can be used to listen for and process bluetooth discovery intents
 */
public class BluetoothDiscoveryBroadcastReceiver extends InjectingBroadcastReceiver {


    private Set<BluetoothDevice> mDiscoveredUnpairedDevices = new HashSet<BluetoothDevice>();

    public BluetoothDiscoveryBroadcastReceiver() {
    }

    public void register(final Context context) {
        // Register for broadcasts when a device is discovered
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        context.registerReceiver(this, filter);

        // Register for broadcasts when discovery has finished
        filter = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        context.registerReceiver(this, filter);

    }

    public void unregister(final Context context) {
        context.unregisterReceiver(this);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        // When a device is discovered, if it was not already paired, add it to the set
        if (BluetoothDevice.ACTION_FOUND.equals(action)) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                mDiscoveredUnpairedDevices.add(device);
            }
        }
        // if discovery has finished, invoke the handler method and unregister this receiver
        else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
            onDiscoveryFinished(mDiscoveredUnpairedDevices);
            unregister(context);
        }
    }

    protected void onDiscoveryFinished(final Set<BluetoothDevice> discoveredUnpairedDevices) {
    }
}
