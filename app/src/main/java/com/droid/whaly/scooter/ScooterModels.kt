package com.droid.whaly.scooter

data class ScooterDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val batteryPercent: Int? = null,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val statusMessage: String? = null,
    /** Name, service UUID or manufacturer data matched a known scooter family. */
    val likely: Boolean = false,
)
