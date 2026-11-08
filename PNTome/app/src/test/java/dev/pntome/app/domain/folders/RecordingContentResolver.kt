package dev.gsound.a.domain.folders

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri

/**
 * A [ContentResolver] that records the persistable-URI-permission calls made by the folder
 * use cases, so tests can assert the SAF permission lifecycle (Req 1.2, 2.2) without a real
 * document provider.
 *
 * - [takePersistableUriPermission] / [releasePersistableUriPermission] append to [taken] /
 *   [released] and update [heldPermissions] (so a release of an un-held grant mimics the
 *   platform by throwing [SecurityException] — unless [throwOnRelease] forces it).
 * - Everything else delegates to the real Robolectric resolver via [delegate], so
 *   `DocumentFile.fromTreeUri(...).name` lookups degrade gracefully to the URI fallback.
 */
class RecordingContentResolver(
    context: Context,
    private val delegate: ContentResolver,
) : ContentResolver(context) {

    data class PermissionCall(val uri: Uri, val flags: Int)

    val taken = mutableListOf<PermissionCall>()
    val released = mutableListOf<PermissionCall>()
    private val heldPermissions = mutableSetOf<String>()

    /** When true, [releasePersistableUriPermission] throws as if the grant was already gone. */
    var throwOnRelease: Boolean = false

    override fun takePersistableUriPermission(uri: Uri, modeFlags: Int) {
        taken += PermissionCall(uri, modeFlags)
        heldPermissions += uri.toString()
    }

    override fun releasePersistableUriPermission(uri: Uri, modeFlags: Int) {
        if (throwOnRelease) {
            throw SecurityException("No persistable permission grant for $uri")
        }
        released += PermissionCall(uri, modeFlags)
        heldPermissions -= uri.toString()
    }

    /** True if a persistable permission is currently recorded as held for [uri]. */
    fun holds(uri: Uri): Boolean = uri.toString() in heldPermissions
}

/**
 * A [Context] that returns [resolver] from [getContentResolver], so a use case constructed with
 * it exercises our recording resolver while all other context behaviour comes from Robolectric.
 */
class RecordingResolverContext(
    base: Context,
    private val resolver: ContentResolver,
) : ContextWrapper(base) {
    override fun getContentResolver(): ContentResolver = resolver
}
