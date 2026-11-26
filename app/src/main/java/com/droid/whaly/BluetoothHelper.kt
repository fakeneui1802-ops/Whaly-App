package com.droid.whaly

import android.bluetooth.BluetoothAdapter

object BluetoothHelper {
    val bluetoothAdapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
}

