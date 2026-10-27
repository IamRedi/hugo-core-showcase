package io.redi.robot.platform.context.sources

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/** Debug-only foreground host for the bounded H044 Android device verification. */
class H044ContextSourceVerificationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                gravity = Gravity.CENTER
                text = "H044 context verification\nNo location content is displayed or retained"
            },
        )
    }
}
