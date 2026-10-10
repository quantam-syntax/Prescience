package com.atreides.consentvoice.tsvadlab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LabScreen() }
    }
}

@Composable
private fun LabScreen() {
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Prescience Voice Lab", style = MaterialTheme.typography.headlineMedium)
            Text("Experimental frame-level target-speaker detection")
            Text("Model status: no audited Android ONNX model installed.")
            Text("The existing Consent Voice app remains the usable fallback. This lab will only enable capture after a verified on-device target-speech model is added.")
        }
    }
}
