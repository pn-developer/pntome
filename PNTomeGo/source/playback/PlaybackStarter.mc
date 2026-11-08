import Toybox.Application;
import Toybox.Lang;
import Toybox.Media;

//! PlaybackStarter — the single way playback-config starts the native player (spec
//! gsound-audiobooks AB-4.5, AB-6, design §4.4).
//!
//! Media.startPlayback(args) relaunches the app in playback mode and hands `args` to
//! PNTomeGoApp.getContentDelegate(args). Args shapes:
//!   - { "folder", "startIndex" }             — music track / book from start / chosen chapter
//!   - { "folder", "mode" => MODE_RESUME }    — book: continue from the bookmark
//! The same dict is saved as the last selection (`pb:last`) so a player-initiated start
//! (getContentDelegate(null)) continues the same way (AB-6.2).
module PlaybackStarter {

    //! args "mode" value: resume a book from its bookmark.
    const MODE_RESUME = "resume";

    //! Start playing `folder` at `startIndex` (0 = first track / chapter). Never throws.
    function startAt(folder as String, startIndex as Number) as Void {
        start({ "folder" => folder, "startIndex" => startIndex });
    }

    //! Resume the book `folder` from its bookmark. Never throws.
    function resume(folder as String) as Void {
        start({ "folder" => folder, "mode" => MODE_RESUME });
    }

    //! Persist the selection, then hand over to the native player.
    function start(args as Dictionary) as Void {
        try {
            try {
                Repo.savePlaybackArgs(args);
            } catch (sex) {
                DebugLog.log("PB", "save selection FAILED " + DebugLog.describe(sex));
            }
            DebugLog.log("PB", "startPlayback folder=" + args.get("folder")
                + " idx=" + args.get("startIndex") + " mode=" + args.get("mode"));
            Media.startPlayback(args as Application.PersistableType);
        } catch (ex) {
            DebugLog.log("PB", "startPlayback FAILED " + DebugLog.describe(ex));
        }
    }
}
