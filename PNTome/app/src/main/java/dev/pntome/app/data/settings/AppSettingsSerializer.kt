package dev.pntome.app.data.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.google.protobuf.InvalidProtocolBufferException
import java.io.InputStream
import java.io.OutputStream

/**
 * Proto DataStore [Serializer] for [AppSettingsProto] (design §5.5, §5.7).
 *
 * The default value seeds mp3-only scanning (all optional-format flags false) and the
 * default server port 8080, so a fresh install restores sane settings on launch (Req 8.2).
 */
object AppSettingsSerializer : Serializer<AppSettingsProto> {

    /** Default port used when no explicit port has been chosen (design §5.5). */
    const val DEFAULT_PORT: Int = 8080

    override val defaultValue: AppSettingsProto =
        AppSettingsProto.newBuilder()
            .setServerPort(DEFAULT_PORT)
            .build()

    override suspend fun readFrom(input: InputStream): AppSettingsProto =
        try {
            AppSettingsProto.parseFrom(input)
        } catch (e: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read AppSettings proto.", e)
        }

    override suspend fun writeTo(t: AppSettingsProto, output: OutputStream) =
        t.writeTo(output)
}
