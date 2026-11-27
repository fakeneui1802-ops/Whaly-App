package com.droid.whaly.ghost

import android.bluetooth.BluetoothAdapter
import android.content.Context
import java.security.SecureRandom

/**
 * Read-only identity vault.
 *
 * Nothing in this app writes [BluetoothAdapter.setName]. The name the owner gave the phone
 * stays exactly as it is, in every mode, before and after every feature. The vault exists
 * only so the Ghost screen can show what the real name is and so a random alias can be
 * generated without ever touching the adapter.
 *
 * Advertising everywhere runs with `setIncludeDeviceName(false)`: no 0x09 record is
 * generated, so the name cannot appear in a scan, a popup or a log.
 */
object GhostIdentity {

    private const val PREFS = "whaly_ghost"
    private const val KEY_REAL_NAME = "real_adapter_name"
    private const val KEY_ALIAS = "device_alias"

    private val random = SecureRandom()

    private var bound: Context? = null

    /** Called once from Application.onCreate so features without a Context can use the alias. */
    fun bind(context: Context) {
        if (bound == null) bound = context.applicationContext
        remember(bound!!)
    }

    fun rememberBound(): String? = bound?.let { remember(it) }

    fun realNameBound(): String? = bound?.let { realName(it) }

    private val adjectives = listOf(
        "Quiet", "Hollow", "Amber", "Silent", "Broken", "Northern", "Copper", "Pale",
        "Restless", "Distant", "Iron", "Salt", "Grey", "Frozen", "Lucky", "Rusty",
    )
    private val nouns = listOf(
        "Lantern", "Harbor", "Signal", "Anchor", "Cipher", "Meadow", "Raven", "Compass",
        "Kettle", "Prism", "Willow", "Falcon", "Ember", "Tundra", "Cobalt", "Sparrow",
    )

    /**
     * A stable random alias for this install. Used only where a name is a local label —
     * HID SDP registration and the spoofing field — so the phone model is never a default.
     * The adapter name is not involved.
     */
    fun deviceAlias(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_ALIAS, null)
        if (stored != null) return stored
        val generated = randomName()
        prefs.edit().putString(KEY_ALIAS, generated).apply()
        return generated
    }

    fun deviceAliasBound(): String = bound?.let { deviceAlias(it) } ?: randomName()

    /** A name that says nothing about the phone or its owner. */
    fun randomName(): String =
        "${adjectives[random.nextInt(adjectives.size)]} " +
            "${nouns[random.nextInt(nouns.size)]} " +
            "%02X".format(random.nextInt(256))

    fun randomHex(bytes: Int): ByteArray = ByteArray(bytes).also { random.nextBytes(it) }

    /**
     * Reads the adapter name once and stores it. This is the only adapter access in the
     * object, and it is a read. A later call never replaces the stored value.
     */
    fun remember(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_REAL_NAME, null)
        if (stored != null) return stored
        val current = runCatching {
            BluetoothAdapter.getDefaultAdapter()?.name
        }.getOrNull() ?: return null
        prefs.edit().putString(KEY_REAL_NAME, current).apply()
        return current
    }

    fun realName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REAL_NAME, null)
}
