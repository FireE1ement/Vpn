package com.universaltunnel.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.universaltunnel.core.ServerConfig

/**
 * Dialog for selecting server from list
 */
@Composable
fun ServerListDialog(
    servers: List<ServerConfig>,
    currentServer: ServerConfig?,
    onSelect: (ServerConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedProtocol by remember { mutableStateOf<String?>(null) }
    
    val protocols = remember(servers) {
        servers.map { it.protocol }.distinct().sorted()
    }
    
    val filteredServers = remember(servers, searchQuery, selectedProtocol) {
        servers.filter { server ->
            val matchesSearch = searchQuery.isEmpty() || 
                server.name.contains(searchQuery, ignoreCase = true) ||
                server.address.contains(searchQuery, ignoreCase = true) ||
                server.protocol.contains(searchQuery, ignoreCase = true)
            
            val matchesProtocol = selectedProtocol == null || server.protocol == selectedProtocol
            
            matchesSearch && matchesProtocol
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Select Server",
                        style = MaterialTheme.typography.headlineSmall
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Search
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search servers...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Protocol filter chips
                if (protocols.size > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedProtocol == null,
                            onClick = { selectedProtocol = null },
                            label = { Text("All") }
                        )
                        protocols.forEach { protocol ->
                            FilterChip(
                                selected = selectedProtocol == protocol,
                                onClick = { selectedProtocol = protocol },
                                label = { Text(protocol.uppercase()) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                // Server list
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredServers) { server ->
                        ServerListItem(
                            server = server,
                            isSelected = server.tag == currentServer?.tag,
                            onClick = { onSelect(server) }
                        )
                    }
                }
                
                // Stats
                Text(
                    text = "${filteredServers.size} of ${servers.size} servers",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

@Composable
private fun ServerListItem(
    server: ServerConfig,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
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
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name.ifEmpty { "${server.protocol} Server" },
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${server.address}:${server.port}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    AssistChip(
                        onClick = { },
                        label = { Text(server.protocol.uppercase()) },
                        leadingIcon = {
                            Icon(
                                when (server.protocol) {
                                    "vmess", "vless" -> Icons.Default.FlashOn
                                    "trojan" -> Icons.Default.Security
                                    "wireguard", "warp" -> Icons.Default.VpnKey
                                    else -> Icons.Default.NetworkCheck
                                },
                                null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                    if (server.tls) {
                        AssistChip(
                            onClick = { },
                            label = { Text("TLS") },
                            leadingIcon = {
                                Icon(Icons.Default.Lock, null, modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }
            }
            
            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

/**
 * Dialog for adding new server manually
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerDialog(
    onAdd: (ServerConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedProtocol by remember { mutableStateOf("vmess") }
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("443") }
    var uuid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var method by remember { mutableStateOf("auto") }
    var enableTls by remember { mutableStateOf(true) }
    var sni by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    
    val protocols = listOf("vmess", "vless", "trojan", "shadowsocks", "shadowsocksr", 
                          "hysteria2", "tuic", "wireguard")
    
    var showAdvanced by remember { mutableStateOf(false) }
    var hasError by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.95f),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Add Server", style = MaterialTheme.typography.headlineSmall)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close")
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Protocol selector
                Text("Protocol", style = MaterialTheme.typography.labelMedium)
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    protocols.take(4).forEachIndexed { index, protocol ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = 4
                            ),
                            onClick = { selectedProtocol = protocol },
                            selected = selectedProtocol == protocol
                        ) {
                            Text(protocol.uppercase())
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    protocols.drop(4).forEachIndexed { index, protocol ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = protocols.size - 4
                            ),
                            onClick = { selectedProtocol = protocol },
                            selected = selectedProtocol == protocol
                        ) {
                            Text(protocol.uppercase())
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Form fields
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Server Name (optional)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                    
                    item {
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            label = { Text("Server Address *") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            isError = hasError && address.isBlank()
                        )
                    }
                    
                    item {
                        OutlinedTextField(
                            value = port,
                            onValueChange = { 
                                port = it.filter { c -> c.isDigit() }.take(5)
                            },
                            label = { Text("Port *") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            isError = hasError && (port.isBlank() || port.toIntOrNull() == null)
                        )
                    }
                    
                    // Protocol-specific fields
                    when (selectedProtocol) {
                        "vmess", "vless" -> {
                            item {
                                OutlinedTextField(
                                    value = uuid,
                                    onValueChange = { uuid = it },
                                    label = { Text("UUID *") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    isError = hasError && uuid.isBlank()
                                )
                            }
                        }
                        "trojan", "shadowsocks", "shadowsocksr", "hysteria2" -> {
                            item {
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    label = { Text("Password *") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    isError = hasError && password.isBlank()
                                )
                            }
                        }
                        "tuic" -> {
                            item {
                                OutlinedTextField(
                                    value = uuid,
                                    onValueChange = { uuid = it },
                                    label = { Text("UUID *") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                            item {
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    label = { Text("Password *") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    visualTransformation = PasswordVisualTransformation()
                                )
                            }
                        }
                        "wireguard" -> {
                            item {
                                OutlinedTextField(
                                    value = uuid, // Using uuid field for private key
                                    onValueChange = { uuid = it },
                                    label = { Text("Private Key *") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                        }
                    }
                    
                    // Advanced options
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Advanced Options", style = MaterialTheme.typography.titleSmall)
                            IconButton(onClick = { showAdvanced = !showAdvanced }) {
                                Icon(
                                    if (showAdvanced) Icons.Default.ExpandLess 
                                    else Icons.Default.ExpandMore,
                                    null
                                )
                            }
                        }
                    }
                    
                    if (showAdvanced) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = enableTls,
                                    onCheckedChange = { enableTls = it }
                                )
                                Text("Enable TLS")
                            }
                        }
                        
                        if (enableTls) {
                            item {
                                OutlinedTextField(
                                    value = sni,
                                    onValueChange = { sni = it },
                                    label = { Text("SNI / Server Name") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                        }
                        
                        item {
                            OutlinedTextField(
                                value = transport,
                                onValueChange = { transport = it },
                                label = { Text("Transport (ws/grpc/httpupgrade)") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )
                        }
                        
                        if (transport.isNotBlank()) {
                            item {
                                OutlinedTextField(
                                    value = path,
                                    onValueChange = { path = it },
                                    label = { Text("Path") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                            item {
                                OutlinedTextField(
                                    value = host,
                                    onValueChange = { host = it },
                                    label = { Text("Host") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }
                        }
                    }
                    
                    // Error message
                    if (hasError) {
                        item {
                            Text(
                                errorMessage,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            // Validate
                            if (address.isBlank()) {
                                hasError = true
                                errorMessage = "Server address is required"
                                return@Button
                            }
                            if (port.toIntOrNull() == null || port.toInt() !in 1..65535) {
                                hasError = true
                                errorMessage = "Invalid port number"
                                return@Button
                            }
                            when (selectedProtocol) {
                                "vmess", "vless" -> if (uuid.isBlank()) {
                                    hasError = true
                                    errorMessage = "UUID is required for this protocol"
                                    return@Button
                                }
                                "trojan", "shadowsocks", "hysteria2" -> if (password.isBlank()) {
                                    hasError = true
                                    errorMessage = "Password is required for this protocol"
                                    return@Button
                                }
                                "tuic" -> if (uuid.isBlank() || password.isBlank()) {
                                    hasError = true
                                    errorMessage = "UUID and password are required for TUIC"
                                    return@Button
                                }
                            }
                            
                            // Create server config
                            val server = ServerConfig(
                                protocol = selectedProtocol,
                                address = address,
                                port = port.toInt(),
                                name = name.ifBlank { "$selectedProtocol-$address" },
                                uuid = uuid,
                                password = password,
                                method = method,
                                tls = enableTls,
                                sni = sni.ifBlank { address },
                                transport = transport,
                                path = path,
                                host = host
                            )
                            
                            onAdd(server)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Add Server")
                    }
                }
            }
        }
    }
}

/**
 * Dialog for managing subscriptions
 */
@Composable
fun SubscriptionDialog(
    subscriptions: List<SubscriptionInfo>,
    onImport: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Subscriptions", style = MaterialTheme.typography.headlineSmall)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close")
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                if (showAdd) {
                    // Add subscription form
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Subscription Name *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Subscription URL *") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 5
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showAdd = false },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel")
                        }
                        Button(
                            onClick = {
                                if (name.isBlank() || url.isBlank()) return@Button
                                isLoading = true
                                onImport(url, name)
                                isLoading = false
                                showAdd = false
                                url = ""
                                name = ""
                            },
                            enabled = !isLoading && name.isNotBlank() && url.isNotBlank(),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Import")
                            }
                        }
                    }
                } else {
                    // Subscription list
                    if (subscriptions.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.CloudOff,
                                    null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "No subscriptions yet",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(subscriptions) { sub ->
                                SubscriptionItem(sub)
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Button(
                        onClick = { showAdd = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add Subscription")
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionItem(sub: SubscriptionInfo) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(sub.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${sub.serverCount} servers • ${sub.format}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Updated: ${formatTimestamp(sub.lastUpdated)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            IconButton(onClick = { /* Update single */ }) {
                Icon(Icons.Default.Refresh, "Update")
            }
            IconButton(onClick = { /* Delete */ }) {
                Icon(Icons.Default.Delete, "Delete")
            }
        }
    }
}

data class SubscriptionInfo(
    val id: String,
    val name: String,
    val url: String,
    val format: String,
    val serverCount: Int,
    val lastUpdated: Long
)

private fun formatTimestamp(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60000 -> "Just now"
        diff < 3600000 -> "${diff / 60000} min ago"
        diff < 86400000 -> "${diff / 3600000} hours ago"
        else -> "${diff / 86400000} days ago"
    }
}