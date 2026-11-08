package dev.pntome.app.service

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Task 6.1 (PL-2.1): the Android Auto meta-data resolves to the automotive_app_desc XML. */
@RunWith(AndroidJUnit4::class)
class AutomotiveMetadataTest {

    @Test
    fun carApplicationMetaDataPointsToAutomotiveDescriptor() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = ctx.packageManager.getApplicationInfo(ctx.packageName, PackageManager.GET_META_DATA)
        val resId = info.metaData.getInt("com.google.android.gms.car.application")
        assertNotEquals(0, resId)

        val parser = ctx.resources.getXml(resId)
        var root: String? = null
        var uses: String? = null
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                if (root == null) root = parser.name
                if (parser.name == "uses") uses = parser.getAttributeValue(null, "name")
            }
            event = parser.next()
        }
        assertEquals("automotiveApp", root)
        assertEquals("media", uses)
    }
}
