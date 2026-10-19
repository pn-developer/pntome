package dev.gsound.a

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point.
 *
 * Annotated with [HiltAndroidApp] to generate the application-level Hilt component that
 * serves as the parent for all other Hilt components in the app.
 */
@HiltAndroidApp
class GsoundApplication : Application()
