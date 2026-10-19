package dev.gsound.a.data.sync

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

/**
 * Proto DataStore [Serializer] for [SyncSummaryProto] (design §5.6, §5.7).
 *
 * The default value is the empty message (`last_sync_at == 0`), representing "no sync has
 * completed yet"; the store reconciles this to a null domain summary on launch (Req 8.2).
 */
object SyncSummarySerializer : Serializer<SyncSummaryProto> {

    override val defaultValue: SyncSummaryProto = SyncSummaryProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): SyncSummaryProto =
        try {
            SyncSummaryProto.parseFrom(input)
        } catch (e: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read SyncSummary proto.", e)
        }

    override suspend fun writeTo(t: SyncSummaryProto, output: OutputStream) =
        t.writeTo(output)
}
