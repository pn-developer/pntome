package dev.pntome.app.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The few Material transport icons that are not in the core icon set (the app does not depend on
 * material-icons-extended). Paths are the standard 24dp Material glyphs.
 */
internal object PlayerIcons {
    val SkipPrevious: ImageVector by lazy { glyph("SkipPrevious", "M6 6h2v12H6zm3.5 6l8.5 6V6z") }
    val SkipNext: ImageVector by lazy { glyph("SkipNext", "M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z") }
    val Pause: ImageVector by lazy { glyph("Pause", "M6 19h4V5H6v14zm8-14v14h4V5h-4z") }

    private fun glyph(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black)).build()
}
