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
 * This id is **finalized**. It MUST stay in lockstep with the `id` attribute of the
 * `<iq:application>` element in GsoundG's `manifest.xml`. Do not change one without the other.
 */
object GsoundAppId {

    /**
     * GsoundG Connect IQ app UUID (hex, no dashes — the format Connect IQ uses in
     * `manifest.xml`). Must match the watch app's manifest id byte-for-byte. See [GsoundAppId].
     */
    const val VALUE: String = "0236dc0a39fd45f09a109f48fc8c7be2"
}
