![markmap](https://github.com/user-attachments/assets/a4c66d81-ac27-4312-8f60-50aefade7c5c)


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

```
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
```

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
```

### 2. Clone and Setup Project

```bash
git clone https://github.com/FireE1ement/Vpn.git
cd Vpn
```

### 3. Build Go Core

```bash
cd core
make deps      # First time only
make android   # Build AAR library
```

This creates `app/libs/core.aar` which is used by the Android app.

### 4. Open in Android Studio

1. Open the project root in Android Studio
2. Sync project with Gradle files
3. Build the project

### 5. Run on Device

Connect Android device (API 26+) or use emulator and click "Run".

## Configuration

### Adding Servers

1. **Manual**: Tap "+" → Enter server details
2. **Subscription**: Menu → Import → Enter subscription URL
3. **QR Code**: Menu → Scan QR (for vmess/vless links)

### Modes

- **VPN Mode**: Routes all system traffic through tunnel (requires VPN permission)
- **Proxy Mode**: Local SOCKS5 (10808) and HTTP (10809) proxies

### Auto-Failover

Enable in settings to automatically switch servers when:
- Current server fails health check
- Target websites become unreachable
- Latency exceeds threshold

## Build Variants

### Debug Build
```bash
./gradlew assembleDebug
```

### Release Build
```bash
./gradlew assembleRelease
```

### Custom Go Core Build
```bash
cd core
make android-all  # All architectures
make debug        # Debug symbols
```

## Troubleshooting

### "gomobile: command not found"
```bash
export PATH=$PATH:$(go env GOPATH)/bin
```

### "NDK not found"
Set `ndk.dir` in `local.properties`:
```
ndk.dir=/path/to/android-ndk
```

### VPN not connecting
- Check VPN permission in system settings
- Verify server configuration
- Check logcat for errors: `adb logcat -s TunnelVpnService:*`

## Architecture

The app uses a layered architecture:

1. **UI Layer**: Jetpack Compose screens and ViewModels
2. **Service Layer**: Android VpnService and ProxyService
3. **Bridge Layer**: JNI interface to Go core
4. **Core Layer**: Go library based on sing-box for protocol implementation

Health checking and failover run in background coroutines, monitoring both server latency and actual site availability.

## License

MIT License - See LICENSE file
