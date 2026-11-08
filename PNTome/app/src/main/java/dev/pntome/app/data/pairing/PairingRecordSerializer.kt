package dev.gsound.a.data.pairing

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

/**
 * Proto DataStore [Serializer] for [PairingRecordProto] (design §5.4, §5.7).
 *
 * The default value is the empty message, which represents the "not paired" state so Unpair
 * can reset the store to [defaultValue] (Req 4.7). The persisted bytes contain the shared
 * secret; they are never logged (Req 8.4).
 */
object PairingRecordSerializer : Serializer<PairingRecordProto> {

    override val defaultValue: PairingRecordProto = PairingRecordProto.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): PairingRecordProto =
        try {
            PairingRecordProto.parseFrom(input)
        } catch (e: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read PairingRecord proto.", e)
        }

    override suspend fun writeTo(t: PairingRecordProto, output: OutputStream) =
        t.writeTo(output)
}
