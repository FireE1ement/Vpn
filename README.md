# Universal Tunnel

Advanced Android VPN/Proxy client with automatic server selection, health checking, and failover support.

## Features

- **Multiple Protocols**: VMess, VLESS, Trojan, Shadowsocks, Hysteria2, TUIC, WireGuard, WARP
- **VPN & Proxy Modes**: Full system VPN (TUN) or local SOCKS5/HTTP proxy
- **Smart Routing**: GeoIP/Geosite rules, custom domains, regex patterns
- **Health Monitoring**: Automatic latency checks and site availability monitoring
- **Auto-Failover**: Switch to best server when connection fails
- **Subscription Support**: Import from standard proxy subscription URLs
- **Quick Settings Tile**: Fast connect/disconnect from notification shade

## Project Structure
UniversalTunnel/
├── app/                    # Android application (Kotlin + Compose)
│   ├── src/main/
│   │   ├── java/com/universaltunnel/
│   │   │   ├── core/      # JNI bridge to Go
│   │   │   ├── vpn/       # VpnService implementation
│   │   │   ├── data/      # Repository and data models
│   │   │   ├── ui/        # Compose UI
│   │   │   └── receiver/  # Broadcast receivers
│   │   └── cpp/           # Native C++ (if needed)
├── core/                   # Go core library (sing-box based)
│   ├── tunnel.go          # Main tunnel interface
│   ├── healthcheck.go     # Health check and failover
│   ├── subscription.go    # Subscription parser
│   └── android.go         # gomobile bindings
plain
Copy

## Requirements

- **Android Studio**: Hedgehog (2023.1.1) or newer
- **Go**: 1.21 or newer
- **Android SDK**: API 26+ (Android 8.0)
- **NDK**: 26.1.10909125 or newer
- **gomobile**: Latest version

## Setup

### 1. Install Go and gomobile

```bash
# Install Go 1.21+
# Download from https://go.dev/dl/

# Install gomobile
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
gomobile init
Продолжаем с корневым build.gradle, настройками проекта и инструкциями по сборке.
Файл 19: build.gradle.kts (root) - Корневой build script
kotlin
Copy
// Top-level build file
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    id("org.jetbrains.kotlin.kapt") version "1.9.22" apply false
    id("com.android.library") version "8.2.2" apply false
}

buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.2.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

// Clean task
tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}

// Version constants
extra["compileSdkVersion"] = 34
extra["minSdkVersion"] = 26
extra["targetSdkVersion"] = 34
extra["kotlinVersion"] = "1.9.22"
extra["composeCompilerVersion"] = "1.5.8"
Файл 20: settings.gradle.kts - Настройки проекта
kotlin
Copy
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "UniversalTunnel"
include(":app")

// Enable Gradle configuration cache
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
Файл 21: gradle.properties - Properties Gradle
properties
Copy
# Project-wide Gradle settings
org.gradle.jvmargs=-Xmx8192m -Dfile.encoding=UTF-8
org.gradle.caching=true
org.gradle.configuration-cache=true
org.gradle.parallel=true
org.gradle.configureondemand=true

# Android settings
android.useAndroidX=true
android.enableJetifier=true
kotlin.code.style=official
android.nonTransitiveRClass=true

# Compose settings
android.enableComposeCompilerMetrics=true
android.enableComposeCompilerReports=true

# NDK settings (for Go bindings)
android.ndkVersion=26.1.10909125
Файл 22: app/src/main/java/com/universaltunnel/receiver/BootReceiver.kt - Автозапуск при загрузке
kotlin
Copy
package com.universaltunnel.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.universaltunnel.data.ServerRepository
import com.universaltunnel.vpn.TunnelVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receiver for boot completed event.
 * Auto-starts VPN if it was active before reboot.
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
        private const val PREFS_NAME = "boot_prefs"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_LAST_SERVER = "last_server"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) {
            return
        }

        Log.i(TAG, "Boot completed received")

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        // Check if auto-start is enabled
        if (!prefs.getBoolean(KEY_AUTO_START, false)) {
            Log.d(TAG, "Auto-start disabled")
            return
        }

        val lastServerTag = prefs.getString(KEY_LAST_SERVER, null)
        if (lastServerTag == null) {
            Log.d(TAG, "No last server found")
            return
        }

        // Get server from repository and start
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repository = ServerRepository.getInstance(context)
                val server = repository.getServer(lastServerTag)
                
                if (server != null) {
                    Log.i(TAG, "Auto-starting VPN with server: ${server.name}")
                    
                    // Start VPN service
                    val serviceIntent = Intent(context, TunnelVpnService::class.java).apply {
                        action = TunnelVpnService.ACTION_CONNECT
                        // Server config will be loaded from repository in service
                    }
                    context.startForegroundService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to auto-start VPN", e)
            }
        }
    }

    companion object {
        /**
         * Enable/disable auto-start
         */
        fun setAutoStart(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_START, enabled)
                .apply()
        }

        /**
         * Save last connected server for auto-start
         */
        fun saveLastServer(context: Context, serverTag: String?) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_SERVER, serverTag)
                .apply()
        }

        /**
         * Check if auto-start is enabled
         */
        fun isAutoStartEnabled(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_START, false)
        }
    }
}
Файл 23: app/src/main/java/com/universaltunnel/receiver/NetworkChangeReceiver.kt - Мониторинг сети
kotlin
Copy
package com.universaltunnel.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.universaltunnel.core.TunnelState
import com.universaltunnel.vpn.TunnelVpnService
import kotlinx.coroutines.*

/**
 * Receiver for network connectivity changes.
 * Handles reconnection when network switches (WiFi <-> Mobile).
 */
class NetworkChangeReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "NetworkChangeReceiver"
        private const val RECONNECT_DELAY = 3000L // 3 seconds
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ConnectivityManager.CONNECTIVITY_ACTION) {
            return
        }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val capabilities = cm.getNetworkCapabilities(network)

        val isConnected = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val isValidated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

        Log.d(TAG, "Network change: connected=$isConnected, validated=$isValidated")

        if (isConnected && isValidated) {
            // Network is available, check if VPN needs restart
            if (TunnelVpnService.isRunning) {
                handleNetworkSwitch(context)
            }
        }
    }

    private fun handleNetworkSwitch(context: Context) {
        scope.launch {
            try {
                Log.i(TAG, "Network switched, scheduling reconnect...")
                
                // Wait a bit for network to stabilize
                delay(RECONNECT_DELAY)
                
                // Send reconnect broadcast to service
                context.sendBroadcast(
                    Intent(ACTION_NETWORK_SWITCHED)
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Error handling network switch", e)
            }
        }
    }

    companion object {
        const val ACTION_NETWORK_SWITCHED = "com.universaltunnel.action.NETWORK_SWITCHED"
    }
}
Файл 24: app/src/main/java/com/universaltunnel/service/TunnelTileService.kt - Quick Settings Tile
kotlin
Copy
package com.universaltunnel.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import com.universaltunnel.core.TunnelState
import com.universaltunnel.data.ServerRepository
import com.universaltunnel.vpn.TunnelVpnService

/**
 * Quick Settings Tile for Universal Tunnel.
 * Allows quick connect/disconnect from notification shade.
 */
@RequiresApi(Build.VERSION_CODES.N)
class TunnelTileService : TileService() {
    
    companion object {
        private const val TAG = "TunnelTileService"
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateTileState()
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(
            stateReceiver,
            IntentFilter(TunnelVpnService.ACTION_STATE_CHANGE),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stateReceiver)
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        
        val currentState = qsTile?.state ?: Tile.STATE_INACTIVE
        
        when (currentState) {
            Tile.STATE_INACTIVE -> {
                // Connect
                connect()
            }
            Tile.STATE_ACTIVE -> {
                // Disconnect
                disconnect()
            }
            else -> {
                // Connecting state, do nothing
            }
        }
    }

    private fun connect() {
        // Update tile to show connecting
        qsTile?.apply {
            state = Tile.STATE_UNAVAILABLE
            label = "Connecting..."
            updateTile()
        }

        // Start service
        val repository = ServerRepository.getInstance(this)
        val servers = repository.servers.value
        
        if (servers.isEmpty()) {
            qsTile?.apply {
                state = Tile.STATE_INACTIVE
                label = "No servers"
                updateTile()
            }
            return
        }

        // Use first server or last used
        val server = servers.first()
        
        startService(
            Intent(this, TunnelVpnService::class.java).apply {
                action = TunnelVpnService.ACTION_CONNECT
                // Config will be loaded from repository
            }
        )
    }

    private fun disconnect() {
        startService(
            Intent(this, TunnelVpnService::class.java).apply {
                action = TunnelVpnService.ACTION_DISCONNECT
            }
        )
    }

    private fun updateTileState() {
        val isRunning = TunnelVpnService.isRunning
        
        qsTile?.apply {
            if (isRunning) {
                state = Tile.STATE_ACTIVE
                label = "Universal Tunnel"
                contentDescription = "VPN Connected"
                icon = android.graphics.drawable.Icon.createWithResource(
                    this@TunnelTileService,
                    android.R.drawable.ic_lock_idle_lock // Replace with custom icon
                )
            } else {
                state = Tile.STATE_INACTIVE
                label = "Universal Tunnel"
                contentDescription = "VPN Disconnected"
                icon = android.graphics.drawable.Icon.createWithResource(
                    this@TunnelTileService,
                    android.R.drawable.ic_menu_close_clear_cancel // Replace with custom icon
                )
            }
            updateTile()
        }
    }
}
Файл 25: app/src/main/java/com/universaltunnel/UniversalTunnelApp.kt - Application class
kotlin
Copy
package com.universaltunnel

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.universaltunnel.data.ServerRepository

/**
 * Application class for Universal Tunnel.
 * Initializes core components and notification channels.
 */
class UniversalTunnelApp : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Initialize notification channels
        createNotificationChannels()
        
        // Initialize repository
        ServerRepository.getInstance(this)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // VPN Service channel
            val vpnChannel = NotificationChannel(
                "universal_tunnel_vpn",
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "VPN connection status"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }

            // Proxy Service channel
            val proxyChannel = NotificationChannel(
                "universal_tunnel_proxy",
                "Proxy Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Proxy service status"
                setShowBadge(false)
            }

            // Health check alerts
            val alertChannel = NotificationChannel(
                "universal_tunnel_alerts",
                "Connection Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Connection and failover alerts"
            }

            notificationManager.createNotificationChannels(
                listOf(vpnChannel, proxyChannel, alertChannel)
            )
        }
    }
}
Файл 26: core/Makefile - Makefile для сборки Go core
makefile
Copy
# Makefile for building Universal Tunnel Go core

.PHONY: all build android clean test deps

# Variables
PACKAGE := universaltunnel/core
OUTPUT_DIR := ../app/libs
AAR_NAME := core.aar
MIN_ANDROID_API := 26

# Default target
all: deps build

# Install dependencies
deps:
	@echo "Installing Go dependencies..."
	go mod download
	go install golang.org/x/mobile/cmd/gomobile@latest
	go install golang.org/x/mobile/cmd/gobind@latest
	gomobile init

# Build Android AAR
android: deps
	@echo "Building Android AAR..."
	mkdir -p $(OUTPUT_DIR)
	gomobile bind \
		-target=android \
		-androidapi=$(MIN_ANDROID_API) \
		-o $(OUTPUT_DIR)/$(AAR_NAME) \
		-v \
		.

# Build for all Android architectures
android-all: deps
	@echo "Building for all architectures..."
	mkdir -p $(OUTPUT_DIR)
	gomobile bind \
		-target=android/arm,android/arm64,android/386,android/amd64 \
		-androidapi=$(MIN_ANDROID_API) \
		-o $(OUTPUT_DIR)/$(AAR_NAME) \
		-v \
		.

# Run tests
test:
	@echo "Running tests..."
	go test -v ./...

# Clean build artifacts
clean:
	@echo "Cleaning..."
	rm -rf $(OUTPUT_DIR)/$(AAR_NAME)
	rm -rf build/
	go clean

# Format code
fmt:
	@echo "Formatting..."
	go fmt ./...

# Update dependencies
update:
	@echo "Updating dependencies..."
	go get -u ./...
	go mod tidy

# Generate mocks (if needed)
generate:
	@echo "Generating..."
	go generate ./...

# Debug build with symbols
debug: deps
	@echo "Building debug version..."
	mkdir -p $(OUTPUT_DIR)
	gomobile bind \
		-target=android \
		-androidapi=$(MIN_ANDROID_API) \
		-o $(OUTPUT_DIR)/$(AAR_NAME) \
		-v \
		-tags=debug \
		.

# Help
help:
	@echo "Available targets:"
	@echo "  make deps      - Install Go dependencies and gomobile"
	@echo "  make android   - Build Android AAR (arm64 only)"
	@echo "  make android-all - Build for all architectures"
	@echo "  make test      - Run Go tests"
	@echo "  make clean     - Clean build artifacts"
	@echo "  make fmt       - Format Go code"
	@echo "  make update    - Update dependencies"
	@echo "  make debug     - Build debug version with symbols"
Файл 27: README.md - Инструкция по сборке и установке
Markdown
Copy
Code
Preview
# Universal Tunnel

Advanced Android VPN/Proxy client with automatic server selection, health checking, and failover support.

## Features

- **Multiple Protocols**: VMess, VLESS, Trojan, Shadowsocks, Hysteria2, TUIC, WireGuard, WARP
- **VPN & Proxy Modes**: Full system VPN (TUN) or local SOCKS5/HTTP proxy
- **Smart Routing**: GeoIP/Geosite rules, custom domains, regex patterns
- **Health Monitoring**: Automatic latency checks and site availability monitoring
- **Auto-Failover**: Switch to best server when connection fails
- **Subscription Support**: Import from standard proxy subscription URLs
- **Quick Settings Tile**: Fast connect/disconnect from notification shade

## Project Structure
UniversalTunnel/
├── app/                    # Android application (Kotlin + Compose)
│   ├── src/main/
│   │   ├── java/com/universaltunnel/
│   │   │   ├── core/      # JNI bridge to Go
│   │   │   ├── vpn/       # VpnService implementation
│   │   │   ├── data/      # Repository and data models
│   │   │   ├── ui/        # Compose UI
│   │   │   └── receiver/  # Broadcast receivers
│   │   └── cpp/           # Native C++ (if needed)
├── core/                   # Go core library (sing-box based)
│   ├── tunnel.go          # Main tunnel interface
│   ├── healthcheck.go     # Health check and failover
│   ├── subscription.go    # Subscription parser
│   └── android.go         # gomobile bindings
plain
Copy

## Requirements

- **Android Studio**: Hedgehog (2023.1.1) or newer
- **Go**: 1.21 or newer
- **Android SDK**: API 26+ (Android 8.0)
- **NDK**: 26.1.10909125 or newer
- **gomobile**: Latest version

## Setup

### 1. Install Go and gomobile

```bash
# Install Go 1.21+
# Download from https://go.dev/dl/

# Install gomobile
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
gomobile init
2. Clone and Setup Project
bash
Copy
git clone https://github.com/yourusername/universaltunnel.git
cd universaltunnel
3. Build Go Core
bash
Copy
cd core
make deps      # First time only
make android   # Build AAR library
This creates app/libs/core.aar which is used by the Android app.
4. Open in Android Studio
Open the project root in Android Studio
Sync project with Gradle files
Build the project
5. Run on Device
Connect Android device (API 26+) or use emulator and click "Run".
Configuration
Adding Servers
Manual: Tap "+" → Enter server details
Subscription: Menu → Import → Enter subscription URL
QR Code: Menu → Scan QR (for vmess/vless links)
Modes
VPN Mode: Routes all system traffic through tunnel (requires VPN permission)
Proxy Mode: Local SOCKS5 (10808) and HTTP (10809) proxies
Auto-Failover
Enable in settings to automatically switch servers when:
Current server fails health check
Target websites become unreachable
Latency exceeds threshold
Build Variants
Debug Build
bash
Copy
./gradlew assembleDebug
Release Build
bash
Copy
./gradlew assembleRelease
Custom Go Core Build
bash
Copy
cd core
make android-all  # All architectures
make debug        # Debug symbols
Troubleshooting
"gomobile: command not found"
bash
Copy
export PATH=$PATH:$(go env GOPATH)/bin
"NDK not found"
Set ndk.dir in local.properties:
plain
Copy
ndk.dir=/path/to/android-ndk
VPN not connecting
Check VPN permission in system settings
Verify server configuration
Check logcat for errors: adb logcat -s TunnelVpnService:*
Architecture
The app uses a layered architecture:
UI Layer: Jetpack Compose screens and ViewModels
Service Layer: Android VpnService and ProxyService
Bridge Layer: JNI interface to Go core
Core Layer: Go library based on sing-box for protocol implementation
Health checking and failover run in background coroutines, monitoring both server latency and actual site availability.
License
MIT License - See LICENSE file
plain
Copy

---

## Файл 28: `local.properties.example` - Пример локальных настроек

```properties
# This file is machine-specific and should NOT be committed to version control
# Copy to local.properties and update paths for your system

# SDK location
sdk.dir=/Users/username/Library/Android/sdk
# or on Windows:
# sdk.dir=C:\\Users\\username\\AppData\\Local\\Android\\Sdk

# NDK location (optional, will use default if not set)
ndk.dir=/Users/username/Library/Android/sdk/ndk/26.1.10909125

# Go path (for gomobile)
go.path=/usr/local/go/bin/go
# or on Windows:
# go.path=C:\\Program Files\\Go\\bin\\go.exe
Итоговая структура проекта
plain
Copy
UniversalTunnel/
├── app/
│   ├── build.gradle.kts
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/universaltunnel/
│   │   │   ├── UniversalTunnelApp.kt
│   │   │   ├── core/
│   │   │   │   ├── NativeBridge.kt
│   │   │   │   └── TunnelConfig.kt
│   │   │   ├── vpn/
│   │   │   │   ├── TunnelVpnService.kt
│   │   │   │   └── ProxyService.kt
│   │   │   ├── data/
│   │   │   │   └── ServerRepository.kt
│   │   │   ├── ui/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── MainViewModel.kt
│   │   │   │   └── dialogs/
│   │   │   │       └── ServerDialogs.kt
│   │   │   ├── receiver/
│   │   │   │   ├── BootReceiver.kt
│   │   │   │   └── NetworkChangeReceiver.kt
│   │   │   └── service/
│   │   │       └── TunnelTileService.kt
│   │   └── res/ (resources)
├── core/
│   ├── go.mod
│   ├── Makefile
│   ├── tunnel.go
│   ├── healthcheck.go
│   ├── subscription.go
│   └── android.go
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── local.properties.example
└── README.md