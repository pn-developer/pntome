package dev.pntome.app.service

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import dev.pntome.app.R

/**
 * ±30 s custom actions for audiobook queues (gsound-player design §3, task 6.4, PL-2.4).
 *
 * The increments themselves come from [BookSeekPlayer]; this object only defines the commands,
 * decides when they are shown and executes them.
 */
internal object BookCustomLayout {
    const val ACTION_SKIP_BACK_30 = "dev.pntome.app.action.skip_back_30"
    const val ACTION_SKIP_FWD_30 = "dev.pntome.app.action.skip_fwd_30"

    val skipBack = SessionCommand(ACTION_SKIP_BACK_30, Bundle.EMPTY)
    val skipForward = SessionCommand(ACTION_SKIP_FWD_30, Bundle.EMPTY)

    /** Custom commands to grant to controllers on connect (on top of the defaults). */
    val commands: List<SessionCommand> = listOf(skipBack, skipForward)

    fun isBook(item: MediaItem?): Boolean =
        item?.mediaMetadata?.extras?.getBoolean(QueueBuilder.EXTRA_AUDIOBOOK) == true

    /** Two buttons for a book item, none otherwise. */
    fun layoutFor(item: MediaItem?): ImmutableList<CommandButton> =
        if (!isBook(item)) {
            ImmutableList.of()
        } else {
            ImmutableList.of(
                CommandButton.Builder()
                    .setDisplayName("Back 30 s")
                    .setIconResId(R.drawable.ic_skip_back_30)
                    .setSessionCommand(skipBack)
                    .build(),
                CommandButton.Builder()
                    .setDisplayName("Forward 30 s")
                    .setIconResId(R.drawable.ic_skip_fwd_30)
                    .setSessionCommand(skipForward)
                    .build(),
            )
        }

    /**
     * Runs a custom command on [player] (which must be the 30 s-aware [BookSeekPlayer]).
     * Returns null when [command] is not one of ours.
     */
    fun execute(command: SessionCommand, player: Player): SessionResult? = when (command.customAction) {
        ACTION_SKIP_BACK_30 -> { player.seekBack(); SessionResult(SessionResult.RESULT_SUCCESS) }
        ACTION_SKIP_FWD_30 -> { player.seekForward(); SessionResult(SessionResult.RESULT_SUCCESS) }
        else -> null
    }
}

/**
 * Pushes the layout for the current item to [setLayout] whenever it may change. Call [apply]
 * once after attaching to cover the already-loaded item.
 */
internal class BookLayoutUpdater(
    private val player: Player,
    private val setLayout: (ImmutableList<CommandButton>) -> Unit,
) : Player.Listener {
    private var lastWasBook: Boolean? = null

    fun apply() {
        val book = BookCustomLayout.isBook(player.currentMediaItem)
        if (book == lastWasBook) return
        lastWasBook = book
        setLayout(BookCustomLayout.layoutFor(player.currentMediaItem))
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = apply()
    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = apply()
}
