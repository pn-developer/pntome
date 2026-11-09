package dev.pntome.app.service

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * Applies the audiobook marks that [QueueBuilder] puts on queued chapters, inside the service so
 * they work even when the UI is gone:
 *
 * - a chapter flagged [QueueBuilder.EXTRA_RESUME_MS] is sought to that position the first time it
 *   becomes current (once per [QueueBuilder.EXTRA_RESUME_NONCE], so re-queueing seeks again);
 * - chapters flagged [QueueBuilder.EXTRA_DONE] are skipped when playback advances onto them
 *   automatically. Picking one explicitly (tap, previous/next) still plays it.
 */
class QueueResumeListener(private val player: Player) : Player.Listener {
    private val applied = HashSet<String>()
    private var skipping = false

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val extras = mediaItem?.mediaMetadata?.extras
        if (extras == null) {
            skipping = false
            return
        }
        val auto = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
        if (extras.getBoolean(QueueBuilder.EXTRA_DONE) && (auto || skipping) &&
            player.hasNextMediaItem()
        ) {
            skipping = true
            player.seekToNextMediaItem()
            return
        }
        skipping = false
        val resumeMs = extras.getLong(QueueBuilder.EXTRA_RESUME_MS)
        val nonce = extras.getString(QueueBuilder.EXTRA_RESUME_NONCE)
        if (resumeMs > 0 && nonce != null && applied.add(nonce)) {
            player.seekTo(resumeMs)
        }
    }
}
