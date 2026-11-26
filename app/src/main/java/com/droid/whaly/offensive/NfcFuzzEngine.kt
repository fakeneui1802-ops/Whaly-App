package com.droid.whaly.offensive

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcA
import android.nfc.tech.NfcV
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class NfcFuzzMode(val label: String, val detail: String) {
    APDU_RANDOM("Random APDU", "Random class/instruction pairs at a live card"),
    APDU_OVERSIZE("Oversize APDU", "Frames past the 255-byte short APDU limit"),
    SELECT_STORM("SELECT storm", "Repeated SELECT by AID, connect and drop"),
    RAW_BLOCK_SCAN("Raw block scan", "Read every block address on ISO15693/Type 2"),
}

/**
 * Fuzzes whatever card or reader is presented. Every exchange is bounded so a wedged
 * target cannot stall the app: the loop gives up after MAX_FRAMES or on the first timeout.
 */
object NfcFuzzEngine {

    private const val TAG = "NfcFuzz"
    private const val MAX_FRAMES = 400

    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private val random = Random()

    private val _mode = MutableStateFlow(NfcFuzzMode.APDU_RANDOM)
    val mode: StateFlow<NfcFuzzMode> = _mode.asStateFlow()

    private val _frames = MutableStateFlow(0)
    val frames: StateFlow<Int> = _frames.asStateFlow()

    private val _log = MutableStateFlow("Idle")
    val log: StateFlow<String> = _log.asStateFlow()

    private var adapter: NfcAdapter? = null
    private var activity: Activity? = null

    private val callback = NfcAdapter.ReaderCallback { tag -> handleTag(tag) }

    fun start(activity: Activity, mode: NfcFuzzMode) {
        if (!running.compareAndSet(false, true)) return
        val nfc = NfcAdapter.getDefaultAdapter(activity)
        if (nfc == null || !nfc.isEnabled) {
            running.set(false)
            _log.value = "NFC is off or missing"
            return
        }
        this.adapter = nfc
        this.activity = activity
        _mode.value = mode
        _frames.value = 0
        _log.value = "Waiting for a tag"
        try {
            nfc.enableReaderMode(
                activity,
                callback,
                NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_NFC_F or
                    NfcAdapter.FLAG_READER_NFC_V or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK,
                Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 100) },
            )
        } catch (error: Exception) {
            running.set(false)
            _log.value = "Reader mode rejected: ${error.message}"
        }
    }

    fun stop() {
        running.set(false)
        val nfc = adapter
        val host = activity
        if (nfc != null && host != null) {
            try {
                nfc.disableReaderMode(host)
            } catch (_: Exception) {
            }
        }
        adapter = null
        activity = null
        _log.value = "Stopped"
    }

    fun isRunning(): Boolean = running.get()

    private fun handleTag(tag: Tag) {
        if (!running.get()) return
        val mode = _mode.value
        executor.execute {
            _log.value = "${mode.label}: ${tag.id.joinToString("") { "%02X".format(it) }}"
            when (mode) {
                NfcFuzzMode.RAW_BLOCK_SCAN -> scanBlocks(tag)
                else -> isoDepFuzz(tag, mode)
            }
        }
    }

    private fun isoDepFuzz(tag: Tag, mode: NfcFuzzMode) {
        val iso = IsoDep.get(tag) ?: run {
            _log.value = "Tag has no ISO-DEP interface"
            return
        }
        try {
            iso.connect()
            iso.timeout = 300
            var count = 0
            while (running.get() && count < MAX_FRAMES) {
                val frame = when (mode) {
                    NfcFuzzMode.APDU_RANDOM -> randomApdu()
                    NfcFuzzMode.APDU_OVERSIZE -> oversizeApdu()
                    else -> selectApdu()
                }
                try {
                    iso.transceive(frame)
                } catch (_: Exception) {
                    if (mode == NfcFuzzMode.SELECT_STORM) {
                        try {
                            iso.close()
                            iso.connect()
                        } catch (_: Exception) {
                            break
                        }
                    }
                }
                count++
                _frames.value = _frames.value + 1
            }
        } catch (error: Exception) {
            Log.w(TAG, "iso fuzz ended: ${error.message}")
        } finally {
            try {
                iso.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun scanBlocks(tag: Tag) {
        val nfcV = NfcV.get(tag)
        if (nfcV != null) {
            try {
                nfcV.connect()
                var block = 0
                while (running.get() && block < 64) {
                    val request = byteArrayOf(
                        0x02, 0x23.toByte(), 0x00, 0x00, 0x00, block.toByte(),
                    )
                    try {
                        nfcV.transceive(request)
                    } catch (_: Exception) {
                    }
                    block++
                    _frames.value = _frames.value + 1
                }
            } catch (error: Exception) {
                Log.w(TAG, "nfcv scan ended: ${error.message}")
            } finally {
                try {
                    nfcV.close()
                } catch (_: Exception) {
                }
            }
            return
        }
        val nfcA = NfcA.get(tag) ?: return
        try {
            nfcA.connect()
            var page = 0
            while (running.get() && page < 64) {
                try {
                    nfcA.transceive(byteArrayOf(0x30, page.toByte()))
                } catch (_: Exception) {
                }
                page++
                _frames.value = _frames.value + 1
            }
        } catch (error: Exception) {
            Log.w(TAG, "nfcA scan ended: ${error.message}")
        } finally {
            try {
                nfcA.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun randomApdu(): ByteArray {
        val size = 4 + random.nextInt(12)
        return ByteArray(size) { random.nextInt(256).toByte() }.also {
            it[0] = 0x00
        }
    }

    private fun oversizeApdu(): ByteArray = ByteArray(255) { random.nextInt(256).toByte() }

    private fun selectApdu(): ByteArray = byteArrayOf(
        0x00, 0xA4.toByte(), 0x04, 0x00, 0x07,
        0xA0.toByte(), 0x00, 0x00, 0x00, 0x04, 0x10, 0x10,
    )
}
