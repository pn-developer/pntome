package dev.gsound.a.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.gsound.a.data.pairing.PairingStore
import dev.gsound.a.net.garmin.ConnectionState
import dev.gsound.a.net.garmin.ControlChannel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Connectivity indicator shown on the bottom-navigation "Pairing" tab.
 *
 * @property paired whether a watch is currently paired. When `false`, no dot is shown.
 * @property online whether the paired watch is reachable right now (control channel Connected).
 *   Only meaningful when [paired] is `true`; drives the green (online) / red (offline) dot.
 */
data class PairingNavStatus(
    val paired: Boolean = false,
    val online: Boolean = false,
)

/**
 * Supplies the bottom-nav "Pairing" tab with a live paired/online signal so it can render a
 * small green/red status dot — the same connectivity cue shown on the Pairing screen, available
 * from anywhere in the app.
 *
 * Both collaborators are app-wide `@Singleton`s, so this observes the exact same
 * [PairingStore.pairing] record and [ControlChannel.connectionState] the pairing/sync view models
 * do; no duplicate connection is created. `Connected` maps to online, any other state to offline.
 */
@HiltViewModel
class NavBarStatusViewModel @Inject constructor(
    pairingStore: PairingStore,
    controlChannel: ControlChannel,
) : ViewModel() {

    val pairingStatus: StateFlow<PairingNavStatus> =
        combine(
            pairingStore.pairing,
            controlChannel.connectionState,
        ) { pairing, connection ->
            PairingNavStatus(
                paired = pairing != null,
                online = connection is ConnectionState.Connected,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PairingNavStatus(),
        )
}
