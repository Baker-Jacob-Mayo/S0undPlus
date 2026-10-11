package com.s0undplus.tv

import android.graphics.Bitmap
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Saves what is on screen as a small JPEG where the Gradle connected-test task collects "additional test output"
 * from, so CI can show it. Never fails a test.
 */
object Screenshots {
    fun capture(name: String) {
        try {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val full = instrumentation.uiAutomation.takeScreenshot() ?: return
            val small = Bitmap.createScaledBitmap(full, 640, 360, true)
            val dir = File(
                InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                    ?: instrumentation.targetContext.getExternalFilesDir(null)?.path.orEmpty(),
            ).apply { mkdirs() }
            File(dir, "$name.jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 70, it) }
        } catch (t: Throwable) {
            Log.w("Screenshots", "Could not capture $name", t)
        }
    }
}
