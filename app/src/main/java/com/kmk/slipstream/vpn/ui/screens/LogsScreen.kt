package com.kmk.slipstream.vpn.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kmk.slipstream.vpn.VpnUiState
import com.kmk.slipstream.vpn.util.ClipboardUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    context: Context,
    onMenu: () -> Unit,
    onClearLogs: () -> Unit,
    vpnState: VpnUiState,
    statusReason: String?,
    logs: List<String>
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs") },
                navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Default.Menu, null) } },
                actions = {
                    IconButton(onClick = {
                        val text = logs.joinToString("\n")
                        ClipboardUtils.copyText(context, "vpn_logs", text)
                    }) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Logs")
                    }
                    IconButton(onClick = onClearLogs) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear Logs")
                    }
                }
            )
        }
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).padding(12.dp)) {

            Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(10.dp)) {
                    Text("State: $vpnState", style = MaterialTheme.typography.titleSmall)
                    statusReason?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            val listState = rememberLazyListState()
            
            // Auto-scroll al final cuando llegan nuevos logs
            LaunchedEffect(logs.size) {
                if (logs.isNotEmpty()) {
                    listState.animateScrollToItem(logs.size - 1)
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                    state = listState
                ) {
                    items(logs.takeLast(400)) { rawLine ->
                        val cleanedLine = rawLine.replace(Regex("\u001B\\[[;\\d]*m"), "")
                        val upper = cleanedLine.uppercase()
                        val color = when {
                            upper.contains("ERROR") || upper.contains("FAILED") || upper.contains("EXITED") -> Color(0xFFF44336) // Rojo
                            upper.contains("WARN") || upper.contains("TIMEOUT") || upper.contains("RECONNECTING") -> Color(0xFFFFC107) // Ámbar
                            upper.contains("CONNECTED") || upper.contains("SUCCESS") || upper.contains("ACCEPTED") -> Color(0xFF4CAF50) // Verde
                            upper.contains("INFO") || upper.contains("START") || upper.contains("REQUESTED") -> Color(0xFF2196F3) // Azul
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Text(cleanedLine, style = MaterialTheme.typography.bodySmall, color = color)
                    }
                }
            }
        }
    }
}
