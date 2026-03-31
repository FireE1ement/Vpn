package core

import (
	"fmt"
	"runtime"
	"sync"

	"gomobilebind"
)

// MobileTunnel wraps Tunnel for gomobile compatibility
type MobileTunnel struct {
	tunnel *Tunnel
	mu     sync.Mutex
}

// NewMobileTunnel creates a new mobile tunnel instance
func NewMobileTunnel() *MobileTunnel {
	return &MobileTunnel{
		tunnel: NewTunnel(),
	}
}

// Start starts the tunnel with JSON configuration
func (m *MobileTunnel) Start(config string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.tunnel.GetState() != StateDisconnected && m.tunnel.GetState() != StateError {
		return fmt.Errorf("tunnel already running")
	}

	return m.tunnel.Start(config)
}

// Stop stops the tunnel
func (m *MobileTunnel) Stop() error {
	m.mu.Lock()
	defer m.mu.Unlock()

	return m.tunnel.Stop()
}

// SetTunFd sets the TUN file descriptor from Android
func (m *MobileTunnel) SetTunFd(fd int32) {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.tunnel.SetAndroidTunFd(fd)
}

// GetState returns current state as string
func (m *MobileTunnel) GetState() string {
	return m.tunnel.GetState().String()
}

// GetStatsJSON returns statistics as JSON string
func (m *MobileTunnel) GetStatsJSON() string {
	m.mu.Lock()
	defer m.mu.Unlock()

	stats := m.tunnel.GetStats()
	
	// Manual JSON construction for gomobile compatibility
	return fmt.Sprintf(`{
		"uploadTotal": %d,
		"downloadTotal": %d,
		"uploadSpeed": %d,
		"downloadSpeed": %d,
		"connections": %d,
		"lastUpdated": %d
	}`, stats.UploadTotal, stats.DownloadTotal, 
		stats.UploadSpeed, stats.DownloadSpeed,
		stats.Connections, stats.LastUpdated.Unix())
}

// GetServerInfoJSON returns server info as JSON
func (m *MobileTunnel) GetServerInfoJSON() string {
	m.mu.Lock()
	defer m.mu.Unlock()

	info := m.tunnel.GetServerInfo()
	return fmt.Sprintf(`{
		"name": "%s",
		"address": "%s",
		"protocol": "%s",
		"port": %d,
		"country": "%s",
		"latency": %d,
		"connected": %t
	}`, info.Name, info.Address, info.Protocol, info.Port,
		info.Country, info.Latency, info.Connected)
}

// TestLatency tests latency to current server
func (m *MobileTunnel) TestLatency() int64 {
	m.mu.Lock()
	defer m.mu.Unlock()

	latency, err := m.tunnel.TestLatency()
	if err != nil {
		return -1
	}
	return latency
}

// GetVersion returns version string
func (m *MobileTunnel) GetVersion() string {
	return m.tunnel.GetVersion()
}

// IsConnected returns connection status
func (m *MobileTunnel) IsConnected() bool {
	return m.tunnel.IsConnected()
}

// Initialize initializes the mobile binding
func Initialize() {
	// Set up Android-specific logging
	runtime.GOMAXPROCS(runtime.NumCPU())
}

// gomobile bind exports
func init() {
	gomobilebind.Register("UniversalTunnel", func() interface{} {
		return NewMobileTunnel()
	})
}