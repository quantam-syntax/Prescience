package com.atreides.consentvoice

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies that the packaged iQOO 15 QAIRT HTP model can create its runtime on a real device. */
@RunWith(AndroidJUnit4::class)
class QairtSepformerSmokeTest {
    @Test
    fun initializesCachedHtpModel() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val error = QairtSepformerModel.initialize(context)
        assertNull("QAIRT HTP initialization failed: $error", error)
        QairtSepformer.close()
    }
}
