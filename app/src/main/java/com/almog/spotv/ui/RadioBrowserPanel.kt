package com.almog.spotv.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.almog.spotv.recognition.RadioBrowserClient
import com.almog.spotv.recognition.RadioStation
import kotlinx.coroutines.launch

@Composable
fun RadioBrowserPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { RadioBrowserClient() }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Search by station name, genre, or country.") }
    val stations = remember { mutableStateListOf<RadioStation>() }

    Column(modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
        Text("Radio Browser", color = Color.White, fontSize = 18.sp)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Station, genre, or country") },
        )
        Button(
            onClick = {
                if (query.isBlank()) {
                    message = "Enter a station, genre, or country."
                } else {
                    loading = true
                    message = "Searching..."
                    scope.launch {
                        val found = try { client.searchStations(query, 12) } catch (_: Exception) { emptyList() }
                        stations.clear()
                        stations.addAll(found)
                        loading = false
                        message = if (found.isEmpty()) "No stations found. Try another search." else "${found.size} stations found. Select one to open it."
                    }
                }
            },
            enabled = !loading,
        ) { Text(if (loading) "Searching..." else "Search") }

        Text(message, color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp))
        stations.forEach { station ->
            Column(
                Modifier.fillMaxWidth().clickable {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(station.streamUrl), "audio/*")
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(context, "No compatible radio player is installed.", Toast.LENGTH_LONG).show()
                    }
                }.padding(vertical = 8.dp)
            ) {
                Text(station.name.ifBlank { "Unnamed station" }, color = Color.White, fontSize = 15.sp)
                val details = listOf(station.country, station.codec, if (station.bitrate > 0) "${station.bitrate} kbps" else "")
                    .filter { it.isNotBlank() }.joinToString(" · ")
                if (details.isNotBlank()) Text(details, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
            }
        }
    }
}
