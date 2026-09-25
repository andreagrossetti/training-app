package com.andreagrossetti.training.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddLocationAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.andreagrossetti.training.data.Place
import com.andreagrossetti.training.location.PlaceLocator
import kotlinx.coroutines.launch

/**
 * Optional place of a diary entry: a button that saves the current GPS position, or the saved
 * place (tap to open it in a maps app) with a button to remove it.
 */
@Composable
fun PlaceButton(
    place: Place?,
    onChange: (Place?) -> Unit,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var locating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun locate() {
        if (!PlaceLocator.isEnabled(context)) {
            error = "Attiva la localizzazione e riprova"
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        locating = true
        error = null
        scope.launch {
            val found = PlaceLocator.locate(context)
            locating = false
            if (found != null) onChange(found) else error = "Posizione non trovata, riprova"
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) locate() else error = "Serve il permesso di localizzazione"
    }

    if (place != null) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val label = Uri.encode(place.label)
                val uri = Uri.parse("geo:${place.lat},${place.lon}?q=${place.lat},${place.lon}($label)")
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            }) {
                Icon(Icons.Rounded.Place, null, tint = color)
                Text(
                    place.label,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            IconButton(onClick = { onChange(null) }) {
                Icon(Icons.Rounded.Close, "Rimuovi luogo", tint = color.copy(alpha = 0.7f))
            }
        }
    } else {
        TextButton(
            onClick = {
                if (PlaceLocator.hasPermission(context)) locate()
                else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            },
            enabled = !locating,
            modifier = modifier,
        ) {
            if (locating) {
                CircularProgressIndicator(Modifier.size(18.dp), color = color, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Rounded.AddLocationAlt, null, tint = color)
            }
            Text(
                when {
                    locating -> "Cerco la posizione…"
                    error != null -> error!!
                    else -> "Salva il luogo"
                },
                color = color,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}
