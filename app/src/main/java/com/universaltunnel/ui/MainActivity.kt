package com.universaltunnel.ui

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.universaltunnel.core.ServerConfig
import com.universaltunnel.core.TunnelState
import kotlinx.coroutines.launch

/**
 * Main Activity with Jetpack Compose UI
 */
class MainActivity : ComponentActivity() {
    
    private val viewModel: MainViewModel by viewModels()
    
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            // Permission granted, proceed with connection
            viewModel.quickConnect()
        } else {
            Toast.makeText(this, "VPN permission required", Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            UniversalTunnelTheme {
                MainScreen(
                    viewModel = viewModel,
                    onRequestVpnPermission = ::requestVpnPermission
                )
            }
        }
    }
    
    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            // Already prepared
            viewModel.quickConnect()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestVpnPermission: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Collect states
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val currentServer by viewModel.currentServer.collectAsStateWithLifecycle()
    val currentSpeed by viewModel.currentSpeed.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val autoFailover by viewModel.autoFailover.collectAsStateWithLifecycle()
    
    var showServerList by remember { mutableStateOf(false) }
    var showAddServer by remember { mutableStateOf(false) }
    var showSubscriptions by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Universal Tunnel") },
                actions = {
                    // Mode toggle
                    IconButton(onClick = { 
                        // Toggle mode (only when disconnected)
                        if (connectionState == TunnelState.DISCONNECTED) {
                            // viewModel.toggleMode()
                        }
                    }) {
                        Icon(
                            imageVector = if (mode == ConnectionMode.VPN) 
                                Icons.Default.VpnKey else Icons.Default.NetworkWifi,
                            contentDescription = "Mode"
                        )
                    }
                    
                    // Settings
                    IconButton(onClick = { /* Open settings */ }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            if (connectionState == TunnelState.DISCONNECTED) {
                FloatingActionButton(
                    onClick = { showAddServer = true },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Server")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Connection Status Card
            ConnectionCard(
                state = connectionState,
                server = currentServer,
                uploadSpeed = currentSpeed.first,
                downloadSpeed = currentSpeed.second,
                totalUpload = stats.formatBytes(stats.uploadTotal),
                totalDownload = stats.formatBytes(stats.downloadTotal),
                onConnect = {
                    if (mode == ConnectionMode.VPN) {
                        onRequestVpnPermission()
                    } else {
                        viewModel.quickConnect()
                    }
                },
                onDisconnect = { viewModel.disconnect() },
                onChangeServer = { showServerList = true }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Auto-failover toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Auto Failover")
                Switch(
                    checked = autoFailover,
                    onCheckedChange = { viewModel.setAutoFailover(it) }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Server list preview
            Text(
                text = "Available Servers (${servers.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Start)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(servers.take(5)) { server ->
                    ServerItem(
                        server = server,
                        isSelected = server.tag == currentServer?.tag,
                        onClick = {
                            if (connectionState == TunnelState.CONNECTED) {
                                viewModel.switchServer(server)
                            } else {
                                viewModel.connect(server, mode)
                            }
                        }
                    )
                }
                
                if (servers.size > 5) {
                    item {
                        TextButton(
                            onClick = { showServerList = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("View all ${servers.size} servers")
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Bottom actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ActionButton(
                    icon = Icons.Default.Refresh,
                    label = "Update",
                    onClick = { viewModel.updateAllSubscriptions() }
                )
                ActionButton(
                    icon = Icons.Default.CloudDownload,
                    label = "Import",
                    onClick = { showSubscriptions = true }
                )
                ActionButton(
                    icon = Icons.Default.Speed,
                    label = "Test All",
                    onClick = { /* Test all servers */ }
                )
            }
        }
    }
    
    // Dialogs
    if (showServerList) {
        ServerListDialog(
            servers = servers,
            currentServer = currentServer,
            onSelect = { server ->
                if (connectionState == TunnelState.CONNECTED) {
                    viewModel.switchServer(server)
                } else {
                    viewModel.connect(server, mode)
                }
                showServerList = false
            },
            onDismiss = { showServerList = false }
        )
    }
    
    if (showAddServer) {
        AddServerDialog(
            onAdd = { server ->
                viewModel.addServer(server)
                showAddServer = false
            },
            onDismiss = { showAddServer = false }
        )
    }
    
    if (showSubscriptions) {
        SubscriptionDialog(
            subscriptions = emptyList(), // TODO: collect from ViewModel
            onImport = { url, name ->
                viewModel.importSubscription(url, name)
            },
            onDismiss = { showSubscriptions = false }
        )
    }
}

/**
 * Main connection status card
 */
@Composable
fun ConnectionCard(
    state: TunnelState,
    server: ServerConfig?,
    uploadSpeed: String,
    downloadSpeed: String,
    totalUpload: String,
    totalDownload: String,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onChangeServer: () -> Unit
) {
    val isConnected = state == TunnelState.CONNECTED
    val isConnecting = state == TunnelState.CONNECTING
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (state) {
                TunnelState.CONNECTED -> Color(0xFF4CAF50).copy(alpha = 0.1f)
                TunnelState.CONNECTING -> Color(0xFFFF9800).copy(alpha = 0.1f)
                TunnelState.ERROR -> Color(0xFFE91E63).copy(alpha = 0.1f)
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status indicator
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(
                        when (state) {
                            TunnelState.CONNECTED -> Color(0xFF4CAF50)
                            TunnelState.CONNECTING -> Color(0xFFFF9800)
                            TunnelState.ERROR -> Color(0xFFE91E63)
                            else -> Color.Gray
                        }
                    )
                    .clickable {
                        if (isConnected) onDisconnect() else if (!isConnecting) onConnect()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (state) {
                        TunnelState.CONNECTED -> Icons.Default.Power
                        TunnelState.CONNECTING -> Icons.Default.Sync
                        TunnelState.DISCONNECTING -> Icons.Default.SyncDisabled
                        TunnelState.ERROR -> Icons.Default.Error
                        else -> Icons.Default.PowerOff
                    },
                    contentDescription = "Power",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Status text
            Text(
                text = when (state) {
                    TunnelState.CONNECTED -> "Connected"
                    TunnelState.CONNECTING -> "Connecting..."
                    TunnelState.DISCONNECTING -> "Disconnecting..."
                    TunnelState.ERROR -> "Error"
                    else -> "Disconnected"
                },
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            
            // Server info
            if (server != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = server.name.ifEmpty { "${server.protocol}://${server.address}:${server.port}" },
                    style = MaterialTheme.typography.bodyMedium
                )
                
                TextButton(onClick = onChangeServer) {
                    Text("Change Server")
                }
            }
            
            // Speed display (only when connected)
            if (isConnected) {
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SpeedIndicator(
                        icon = Icons.Default.ArrowUpward,
                        label = "Upload",
                        speed = uploadSpeed,
                        total = totalUpload
                    )
                    SpeedIndicator(
                        icon = Icons.Default.ArrowDownward,
                        label = "Download",
                        speed = downloadSpeed,
                        total = totalDownload
                    )
                }
            }
        }
    }
}

@Composable
fun SpeedIndicator(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    speed: String,
    total: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
        Text(speed, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(total, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}

@Composable
fun ServerItem(
    server: ServerConfig,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name.ifEmpty { server.tag },
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${server.protocol}://${server.address}:${server.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            
            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = label)
        }
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

// Placeholder dialogs - will be implemented in next files
@Composable
fun ServerListDialog(
    servers: List<ServerConfig>,
    currentServer: ServerConfig?,
    onSelect: (ServerConfig) -> Unit,
    onDismiss: () -> Unit
) {
    // Implementation in next file
}

@Composable
fun AddServerDialog(
    onAdd: (ServerConfig) -> Unit,
    onDismiss: () -> Unit
) {
    // Implementation in next file
}

@Composable
fun SubscriptionDialog(
    subscriptions: List<Any>,
    onImport: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    // Implementation in next file
}

@Composable
fun UniversalTunnelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
