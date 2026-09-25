package com.andreagrossetti.training.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andreagrossetti.training.ui.TrainingTheme

/** Shown by Health Connect when the user asks why the app wants its permissions (required by Health Connect). */
class HealthRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrainingTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.safeDrawingPadding().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("Dati salute", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "Allenamento scrive in Health Connect i tuoi allenamenti, le corse con distanza e percorso GPS, " +
                                "l'HRV del mattino e il battito a riposo misurati col sensore, così li vedi anche in Samsung Health " +
                                "e nelle altre app che scegli tu.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            "L'app non legge niente da Health Connect e non manda i tuoi dati da nessuna parte: li vedono solo " +
                                "le app a cui dai accesso tu. Puoi revocare i permessi quando vuoi dalle impostazioni di Health Connect.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = { finish() }) { Text("OK") }
                    }
                }
            }
        }
    }
}
