package dev.gsound.a.net.garmin

/**
 * The Connect IQ application UUID that identifies the **GsoundG** watch app on a Garmin device.
 *
 * This value is a **shared protocol constant**: it MUST be byte-for-byte identical to the
 * `id` attribute of the `<iq:application>` element in GsoundG's `manifest.xml`. The Connect IQ
 * Mobile SDK uses this id to:
 * - look up whether the GsoundG app is installed on a resolved device
 *   (`ConnectIQ.getApplicationInfo(appId, device, ...)`, Req 4.1), and
 * - address control messages to that specific app (`IQApp(appId)`).
 *
 * If this id does not match the watch manifest, the SDK will report the app as **not
 * installed** even when it is, surfacing as [ConnectionState.Error.Reason.APP_NOT_INSTALLED].
 *
 * TODO(gsound): replace [VALUE] with the real GsoundG app UUID once the watch app's
 *   `manifest.xml` id is finalized. The current value is a **placeholder** and will not match
 *   any real installed app. Keep this constant and the watch manifest in lockstep.
 */
object GsoundAppId {

    /**
     * Placeholder GsoundG Connect IQ app UUID (hex, no dashes — the format Connect IQ uses in
     * `manifest.xml`). Must match the watch app's manifest id. See the TODO on [GsoundAppId].
     */
    const val VALUE: String = "00000000000000000000000000000000"
}
