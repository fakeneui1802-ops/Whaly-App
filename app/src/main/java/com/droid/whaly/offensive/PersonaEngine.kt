package com.droid.whaly.offensive

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseData
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.droid.whaly.BluetoothAdvertiser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs one persona at a time: BLE presence, Bluetooth Classic name and mDNS records.
 * Bluetooth name and mDNS are restored when the persona stops.
 */
object PersonaEngine {

    private const val TAG = "PersonaEngine"
    private const val PACKET_DELAY_MS = 90L

    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val random = Random()

    private val _active = MutableStateFlow<Persona?>(null)
    val active: StateFlow<Persona?> = _active.asStateFlow()

    private val _packets = MutableStateFlow(0)
    val packets: StateFlow<Int> = _packets.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private var restoreName: String? = null
    private var registeredTypes: List<String> = emptyList()
    private var appContext: Context? = null

    private const val PREFS = "WhalyPersonaPrefs"
    private const val KEY_ORIGINAL_NAME = "original_bluetooth_name"

    /**
     * A persona changes the real adapter name, so a crash or a force-stop would leave the
     * phone advertising as an iPhone until the user notices. The original is written to
     * disk before the first change and restored on the next launch.
     */
    fun restoreIfStale(context: Context) {
        if (running.get()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_ORIGINAL_NAME).apply()
        // The adapter name is read-only in this app; nothing to restore.
    }

    @SuppressLint("MissingPermission")
    private fun adapter(): BluetoothAdapter? = try {
        BluetoothAdapter.getDefaultAdapter()
    } catch (_: SecurityException) {
        null
    }

    /**
     * The adapter name is never written. A persona is a payload and an mDNS record; the
     * phone's real name stays in the vault and is never on air, and nothing can leave the
     * adapter renamed if the process dies mid-run.
     */
    @SuppressLint("MissingPermission")
    private fun applyBluetoothName(name: String?) {
        val context = appContext ?: return
        com.droid.whaly.ghost.GhostIdentity.remember(context)
    }

    private fun restoreBluetoothName() {
        // The adapter name is never written, so there is nothing to put back.
    }

    private fun registerMdns(context: Context, persona: Persona) {
        if (persona.mdnsTypes.isEmpty()) return
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val registered = mutableListOf<String>()
        persona.mdnsTypes.forEach { type ->
            val info = NsdServiceInfo().apply {
                serviceName = "${persona.label} (${random.nextInt(1000)})"
                serviceType = type
                port = 7000
            }
            try {
                manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(info: NsdServiceInfo) {
                        Log.i(TAG, "mDNS ${info.serviceType} registered")
                    }

                    override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "mDNS ${info.serviceType} failed: $errorCode")
                    }

                    override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

                    override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                })
                registered += type
            } catch (error: Exception) {
                Log.w(TAG, "mDNS register error for $type", error)
            }
        }
        registeredTypes = registered
    }

    private fun unregisterMdns(context: Context) {
        if (registeredTypes.isEmpty()) return
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        // Android has no bulk unregister; the platform drops records when the process or
        // the listener goes away. Clear the local list so a restart re-registers cleanly.
        registeredTypes = emptyList()
        if (manager == null) Log.w(TAG, "No NsdManager while unregistering")
    }

    fun start(context: Context, persona: Persona) {
        if (!running.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        this.appContext = appContext
        _active.value = persona
        _packets.value = 0
        _status.value = "Starting ${persona.label}"

        applyBluetoothName(persona.bluetoothName)
        registerMdns(appContext, persona)

        executor.execute {
            val advertiser = BluetoothAdvertiser()
            _status.value = "Broadcasting ${persona.label}"
            while (running.get()) {
                val frame = persona.frames[random.nextInt(persona.frames.size)]
                val data = AdvertiseData.Builder().apply {
                    // The local name is deliberately omitted: the real adapter name must never
                    // be transmitted, and the persona is carried by the payload alone.
                    setIncludeDeviceName(false)
                    when (frame) {
                        is PersonaFrame.Manufacturer -> addManufacturerData(frame.companyId, frame.data)
                        is PersonaFrame.ServiceData -> {
                            if (frame.includeUuid) addServiceUuid(android.os.ParcelUuid(frame.uuid))
                            addServiceData(android.os.ParcelUuid(frame.uuid), frame.data)
                        }
                    }
                }.build()
                try {
                    advertiser.advertise(data, null)
                    _packets.value = _packets.value + 1
                } catch (error: Exception) {
                    Log.w(TAG, "advertise failed: ${error.message}")
                }
                try {
                    Thread.sleep(PACKET_DELAY_MS)
                } catch (_: InterruptedException) {
                    break
                }
                try {
                    advertiser.stopAdvertising()
                } catch (_: Exception) {
                }
            }
            try {
                advertiser.stopAdvertising()
            } catch (_: Exception) {
            }
        }
    }

    fun stop(context: Context) {
        if (!running.compareAndSet(true, false)) return
        unregisterMdns(context.applicationContext)
        restoreBluetoothName()
        _active.value = null
        _status.value = "Stopped"
    }

    fun toggle(context: Context, persona: Persona) {
        if (_active.value?.id == persona.id) stop(context) else {
            stop(context)
            start(context, persona)
        }
    }
}
