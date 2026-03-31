package core

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/box"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common/json/badjson"
)

// HealthCheckConfig configures health checking behavior
type HealthCheckConfig struct {
	Enabled         bool          `json:"enabled"`
	Interval        time.Duration `json:"interval"`        // Default: 5m
	Timeout         time.Duration `json:"timeout"`         // Default: 5s
	RetryCount      int           `json:"retryCount"`      // Default: 3
	TestURL         string        `json:"testURL"`         // Default: https://www.google.com/generate_204
	ConcurrentChecks int          `json:"concurrentChecks"` // Default: 5
	FailoverEnabled bool          `json:"failoverEnabled"` // Auto-switch on failure
	FailoverDelay   time.Duration `json:"failoverDelay"`   // Delay before failover
}

// DefaultHealthCheckConfig returns default configuration
func DefaultHealthCheckConfig() HealthCheckConfig {
	return HealthCheckConfig{
		Enabled:          true,
		Interval:         5 * time.Minute,
		Timeout:          5 * time.Second,
		RetryCount:       3,
		TestURL:          "https://www.google.com/generate_204",
		ConcurrentChecks: 5,
		FailoverEnabled:  true,
		FailoverDelay:    3 * time.Second,
	}
}

// ServerHealth holds health status for a server
type ServerHealth struct {
	ServerID        string    `json:"serverId"`
	ServerName      string    `json:"serverName"`
	Latency         int64     `json:"latency"`         // ms, -1 if failed
	LastCheck       time.Time `json:"lastCheck"`
	LastSuccess     time.Time `json:"lastSuccess"`
	FailCount       int       `json:"failCount"`
	SuccessCount    int       `json:"successCount"`
	Available       bool      `json:"available"`
	ConsecutiveFails int      `json:"consecutiveFails"`
}

// HealthChecker manages health checks for multiple servers
type HealthChecker struct {
	ctx            context.Context
	cancel         context.CancelFunc
	config         HealthCheckConfig
	servers        []ServerConfig
	healthStatus   map[string]*ServerHealth
	statusMu       sync.RWMutex
	activeServer   string
	onFailover     func(string) error // Callback when failover needed
	tunnel         *Tunnel            // Reference to active tunnel
	checkerWg      sync.WaitGroup
}

// NewHealthChecker creates a new health checker
func NewHealthChecker(config HealthCheckConfig) *HealthChecker {
	ctx, cancel := context.WithCancel(context.Background())
	return &HealthChecker{
		ctx:          ctx,
		cancel:       cancel,
		config:       config,
		healthStatus: make(map[string]*ServerHealth),
		servers:      make([]ServerConfig, 0),
	}
}

// SetServers updates the list of servers to check
func (h *HealthChecker) SetServers(servers []ServerConfig) {
	h.statusMu.Lock()
	defer h.statusMu.Unlock()

	h.servers = servers
	
	// Initialize health status for new servers
	for _, srv := range servers {
		id := h.serverID(srv)
		if _, exists := h.healthStatus[id]; !exists {
			h.healthStatus[id] = &ServerHealth{
				ServerID:   id,
				ServerName: srv.Name,
				Available:  true, // Assume available initially
			}
		}
	}
}

// SetActiveServer sets the currently active server
func (h *HealthChecker) SetActiveServer(serverID string) {
	h.statusMu.Lock()
	h.activeServer = serverID
	h.statusMu.Unlock()
}

// SetFailoverCallback sets the callback for failover events
func (h *HealthChecker) SetFailoverCallback(cb func(string) error) {
	h.onFailover = cb
}

// SetTunnel sets the active tunnel for site checking
func (h *HealthChecker) SetTunnel(tunnel *Tunnel) {
	h.tunnel = tunnel
}

// serverID generates unique ID for server
func (h *HealthChecker) serverID(s ServerConfig) string {
	return fmt.Sprintf("%s:%s:%d", s.Protocol, s.Address, s.Port)
}

// Start begins health checking
func (h *HealthChecker) Start() {
	if !h.config.Enabled {
		return
	}

	h.checkerWg.Add(1)
	go h.checkLoop()
}

// Stop stops health checking
func (h *HealthChecker) Stop() {
	h.cancel()
	h.checkerWg.Wait()
}

// checkLoop runs periodic health checks
func (h *HealthChecker) checkLoop() {
	defer h.checkerWg.Done()

	// Initial check
	h.runChecks()

	ticker := time.NewTicker(h.config.Interval)
	defer ticker.Stop()

	for {
		select {
		case <-h.ctx.Done():
			return
		case <-ticker.C:
			h.runChecks()
		}
	}
}

// runChecks performs health checks on all servers
func (h *HealthChecker) runChecks() {
	h.statusMu.RLock()
	servers := make([]ServerConfig, len(h.servers))
	copy(servers, h.servers)
	h.statusMu.RUnlock()

	if len(servers) == 0 {
		return
	}

	// Semaphore for concurrent checks
	semaphore := make(chan struct{}, h.config.ConcurrentChecks)
	var wg sync.WaitGroup

	for _, srv := range servers {
		wg.Add(1)
		semaphore <- struct{}{}

		go func(server ServerConfig) {
			defer wg.Done()
			defer func() { <-semaphore }()

			h.checkServer(server)
		}(srv)
	}

	wg.Wait()

	// After all checks, evaluate if failover needed
	h.evaluateFailover()
}

// checkServer performs health check on single server
func (h *HealthChecker) checkServer(server ServerConfig) {
	id := h.serverID(server)
	start := time.Now()

	// Create test connection
	latency, err := h.testServerLatency(server)

	h.statusMu.Lock()
	health := h.healthStatus[id]
	if health == nil {
		health = &ServerHealth{ServerID: id, ServerName: server.Name}
		h.healthStatus[id] = health
	}

	health.LastCheck = time.Now()

	if err != nil {
		health.FailCount++
		health.ConsecutiveFails++
		if health.ConsecutiveFails >= h.config.RetryCount {
			health.Available = false
		}
		health.Latency = -1
	} else {
		health.Latency = latency
		health.SuccessCount++
		health.ConsecutiveFails = 0
		health.Available = true
		health.LastSuccess = time.Now()
	}
	h.statusMu.Unlock()
}

// testServerLatency tests latency to server
func (h *HealthChecker) testServerLatency(server ServerConfig) (int64, error) {
	address := net.JoinHostPort(server.Address, fmt.Sprintf("%d", server.Port))

	// Try TCP connection first
	start := time.Now()
	conn, err := net.DialTimeout("tcp", address, h.config.Timeout)
	if err != nil {
		return -1, err
	}
	defer conn.Close()

	latency := time.Since(start).Milliseconds()

	// For more accurate test, could do protocol-specific handshake
	// But TCP connect is usually sufficient for health check

	return latency, nil
}