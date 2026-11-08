package dev.pntome.app.ui.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import dev.pntome.app.R

/**
 * Runtime [Manifest.permission.POST_NOTIFICATIONS] request flow for the sync progress notification
 * (task 11.1, Req 9.3), with a rationale and graceful degradation.
 *
 * ## Why this exists
 * The sync runs in a foreground service, and a foreground service must post an ongoing
 * notification. On API 33+ that notification is only *visible* if the user has granted
 * `POST_NOTIFICATIONS`. Critically, the service and therefore the sync **work regardless** of the
 * grant: when the permission is denied the platform simply suppresses the notification, but the
 * foreground service still starts and the transfer still completes. So the only thing lost on a
 * denial is the user's ability to watch progress in the shade — the sync itself is never blocked.
 * This is the graceful-degrade contract of Req 9.3.
 *
 * ## The flow
 * [rememberNotificationPermissionRequester] returns a [NotificationPermissionRequester] whose
 * [NotificationPermissionRequester.request] the Sync screen calls when the user taps "Sync now":
 *
 * 1. **Not needed** — below API 33 the permission does not exist (it is granted at install), or it
 *    is already granted: `onComplete` runs immediately so sync starts with no prompt.
 * 2. **Rationale** — if the system indicates we should explain why (the user previously dismissed
 *    the request), we first show a short rationale dialog describing that notifications are only
 *    used to show sync progress and that sync works either way. "Continue" then triggers the system
 *    prompt; "Not now" skips the prompt but still proceeds to sync (graceful degrade).
 * 3. **System prompt** — otherwise we launch the OS permission dialog directly.
 *
 * In every branch `onComplete` is eventually invoked exactly once, so the caller can start the sync
 * without caring about the permission result. Nothing here blocks sync on a denial (Req 9.3).
 *
 * ## Usage
 * ```
 * val notifications = rememberNotificationPermissionRequester()
 * // in the Sync button handler:
 * notifications.request { viewModel.onSyncClicked() }
 * // and render the rationale dialog host somewhere in the composition:
 * notifications.RationaleHost()
 * ```
 */
class NotificationPermissionRequester internal constructor(
    private val isSupported: Boolean,
    private val isGranted: () -> Boolean,
    private val shouldShowRationale: () -> Boolean,
    private val launchSystemPrompt: () -> Unit,
    private val showRationaleDialog: (onContinue: () -> Unit, onDismiss: () -> Unit) -> Unit,
    private val setPendingCompletion: (() -> Unit) -> Unit,
    private val runCompletion: () -> Unit,
) {
    /**
     * Ensure the notification permission has been asked for (once), then run [onComplete].
     *
     * [onComplete] is the "start the sync" action. It is invoked immediately when no prompt is
     * needed (unsupported platform or already granted), after the system dialog resolves (granted
     * or denied — either way, Req 9.3), or after the rationale dialog is handled. The sync is never
     * gated on the grant.
     */
    fun request(onComplete: () -> Unit) {
        // Not applicable below API 33 or when already granted → start immediately.
        if (!isSupported || isGranted()) {
            onComplete()
            return
        }

        // Defer the completion until the request resolves, so we start the sync exactly once.
        setPendingCompletion(onComplete)

        if (shouldShowRationale()) {
            // Explain first (Req 9.3), then prompt. Dismiss = proceed without the notification.
            showRationaleDialog(
                /* onContinue = */ { launchSystemPrompt() },
                /* onDismiss = */ { runCompletion() },
            )
        } else {
            launchSystemPrompt()
        }
    }

    /**
     * Hosts the rationale [AlertDialog]. Place once in the composition that uses this requester;
     * it renders nothing until a rationale is pending.
     */
    @Composable
    fun RationaleHost() {
        rationaleHostContent?.invoke()
    }

    internal var rationaleHostContent: (@Composable () -> Unit)? = null
}

/**
 * Build and remember a [NotificationPermissionRequester] wired to the Activity Result API and the
 * current activity's permission state. Safe to call from any composable; it reads the hosting
 * [Activity] via [LocalContext] for [Activity.shouldShowRequestPermissionRationale].
 */
@Composable
fun rememberNotificationPermissionRequester(): NotificationPermissionRequester {
    val context = LocalContext.current
    val isSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    // The deferred "start sync" action captured by request(); run exactly once per request.
    var pendingCompletion by remember { mutableStateOf<(() -> Unit)?>(null) }
    // Whether the rationale dialog is currently showing, and what to do on continue/dismiss.
    var rationaleActions by remember {
        mutableStateOf<Pair<() -> Unit, () -> Unit>?>(null)
    }

    fun runPending() {
        val completion = pendingCompletion
        pendingCompletion = null
        completion?.invoke()
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // Req 9.3: whether the user granted or denied, continue to sync. A denial only hides the
        // progress notification; it must not stop the sync.
        runPending()
    }

    val requester = remember(context, isSupported) {
        NotificationPermissionRequester(
            isSupported = isSupported,
            isGranted = {
                !isSupported || ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
            },
            shouldShowRationale = {
                context.findActivity()?.shouldShowRequestPermissionRationale(
                    Manifest.permission.POST_NOTIFICATIONS,
                ) ?: false
            },
            launchSystemPrompt = {
                if (isSupported) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // Should not happen (guarded in request()), but never strand the completion.
                    runPending()
                }
            },
            showRationaleDialog = { onContinue, onDismiss ->
                rationaleActions = onContinue to onDismiss
            },
            setPendingCompletion = { pendingCompletion = it },
            runCompletion = { runPending() },
        )
    }

    // Wire the rationale dialog content into the requester so RationaleHost() can render it.
    requester.rationaleHostContent = {
        rationaleActions?.let { (onContinue, onDismiss) ->
            NotificationRationaleDialog(
                onContinue = {
                    rationaleActions = null
                    onContinue()
                },
                onDismiss = {
                    rationaleActions = null
                    onDismiss()
                },
            )
        }
    }

    return requester
}

/**
 * Short rationale shown before (re)prompting for notifications (Req 9.3): explains that the
 * permission is used only to show sync progress and that sync works without it. "Continue" leads to
 * the system prompt; "Not now" dismisses and proceeds to sync anyway.
 */
@Composable
private fun NotificationRationaleDialog(
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notif_perm_rationale_title)) },
        text = { Text(stringResource(R.string.notif_perm_rationale_body)) },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(stringResource(R.string.notif_perm_rationale_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.notif_perm_rationale_dismiss))
            }
        },
    )
}

/** Unwrap the [Activity] from a (possibly wrapped) [Context], or null if none is found. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
