package dev.gsound.a.ui.folders

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/**
 * Launches the Storage Access Framework folder picker (`ACTION_OPEN_DOCUMENT_TREE`, Req 1.1)
 * and reports the selected tree URI.
 *
 * @property launch opens the system folder browser. Pass an optional initial URI to hint the
 *   starting location, or `null` for the provider's default.
 */
class AddFolderLauncher internal constructor(
    private val delegate: ActivityResultLauncher<Uri?>,
) {
    /** Open the SAF folder picker, optionally starting at [initialUri]. */
    fun launch(initialUri: Uri? = null) = delegate.launch(initialUri)
}

/**
 * Remember a reusable [AddFolderLauncher] for the SAF folder picker (design §5.1).
 *
 * On a successful selection the non-null tree URI is passed to [onFolderSelected] — callers
 * (task 4.2) forward it to [dev.gsound.a.domain.folders.AddFolderUseCase] to take the
 * persistable permission and persist the folder. When the user cancels, the contract yields a
 * `null` URI and this helper does nothing, leaving the folder list unchanged (Req 1.5).
 *
 * @param onFolderSelected invoked with the selected tree URI; always called on the main thread.
 */
@Composable
fun rememberAddFolderLauncher(
    onFolderSelected: (Uri) -> Unit,
): AddFolderLauncher {
    // Keep the latest callback without re-creating the launcher on every recomposition.
    val currentOnFolderSelected by rememberUpdatedState(onFolderSelected)

    val activityLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        // Null means the user cancelled the picker — leave the list unchanged (Req 1.5).
        if (uri != null) {
            currentOnFolderSelected(uri)
        }
    }

    return remember(activityLauncher) { AddFolderLauncher(activityLauncher) }
}
