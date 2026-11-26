package com.droid.whaly.offline

import android.content.Context
import com.droid.whaly.nfc.EmulatedNfcTag
import com.droid.whaly.nfc.NfcTagEmulationStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Prank NFC cards. The phone presents itself as a Type-4 tag carrying a URL, so tapping it
 * on another phone opens whatever is in the card. No network is involved on the sending
 * side and no root is needed — HCE is a public API.
 */
object PrankCards {

    data class Card(val name: String, val url: String, val note: String)

    private val _active = MutableStateFlow<Card?>(null)
    val active: StateFlow<Card?> = _active.asStateFlow()

    val cards: List<Card> = listOf(
        Card(
            "Never Gonna Give You Up",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "Opens the video. The classic.",
        ),
        Card(
            "Free iPhone 17",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "A prize page that is not a prize page.",
        ),
        Card(
            "Wi-Fi Login",
            "http://192.168.1.1/",
            "Opens the router admin page on the victim's own browser.",
        ),
        Card(
            "Virus Alert",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "Whatever the room believes, it is a link.",
        ),
        Card(
            "Whaly",
            "https://fakeneui1802-ops.github.io/Whaly-App/",
            "Opens the project site.",
        ),
        Card(
            "Blank",
            "https://example.com",
            "Neutral card for testing that HCE is running.",
        ),
    )

    fun select(context: Context, card: Card) {
        val tag = EmulatedNfcTag(id = -1L, name = card.name, url = card.url)
        NfcTagEmulationStore.setActiveTag(context, tag)
        _active.value = card
    }

    fun clear(context: Context) {
        NfcTagEmulationStore.setActiveTag(context, null)
        _active.value = null
    }

    fun isArmed(): Boolean = _active.value != null
}
