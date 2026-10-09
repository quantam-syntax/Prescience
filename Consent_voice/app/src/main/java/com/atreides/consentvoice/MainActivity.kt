package com.atreides.consentvoice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.atreides.voiceconsent.SessionState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ConsentVoiceApp() }
    }
}

@androidx.compose.runtime.Composable
private fun ConsentVoiceApp() {
    var state by remember { mutableStateOf(SessionState.Idle) }
    MaterialTheme {
        Scaffold { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Offline Voice Consent scaffold", style = MaterialTheme.typography.headlineSmall)
                Text("Status: ${state.label}")
                Text("Model inference, BLE, microphone capture, and redaction are not connected in this scaffold.")
                Button(onClick = { state = SessionState.EnrollmentRequired }) {
                    Text("Start voice enrollment")
                }
                Button(onClick = { state = SessionState.AwaitingPeerApproval }) {
                    Text("Start consent session")
                }
            }
        }
    }
}
