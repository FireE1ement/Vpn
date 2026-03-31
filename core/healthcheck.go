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
// SiteCheckConfig configures site availability monitoring
type SiteCheckConfig struct {
	Enabled       bool          `json:"enabled"`
	Interval      time.Duration `json:"interval"`      // Default: 60s
	Timeout       time.Duration `json:"timeout"`     // Default: 10s
	Sites         []string      `json:"sites"`       // Sites to check
	FailThreshold int           `json:"failThreshold"` // Consecutive fails before alert
}

// DefaultSiteCheckConfig returns default site check configuration
func DefaultSiteCheckConfig() SiteCheckConfig {
	return SiteCheckConfig{
		Enabled:       true,
		Interval:      60 * time.Second,
		Timeout:       10 * time.Second,
		Sites: []string{
			"https://www.google.com",
			"https://www.youtube.com",
			"https://github.com",
			"https://api.telegram.org",
		},
		FailThreshold: 3,
	}
}

// SiteCheckResult holds result of site check
type SiteCheckResult struct {
	URL        string        `json:"url"`
	Available  bool          `json:"available"`
	Latency    int64         `json:"latency"`    // ms
	StatusCode int           `json:"statusCode"`
	Error      string        `json:"error,omitempty"`
	Timestamp  time.Time     `json:"timestamp"`
}

// SiteMonitor monitors actual site availability through active tunnel
type SiteMonitor struct {
	ctx           context.Context
	cancel        context.CancelFunc
	config        SiteCheckConfig
	tunnel        *Tunnel
	results       []SiteCheckResult
	resultsMu     sync.RWMutex
	failCount     map[string]int
	onSitesDown   func([]string) // Callback when sites are down
	checkerWg     sync.WaitGroup
}

// NewSiteMonitor creates a new site monitor
func NewSiteMonitor(config SiteCheckConfig) *SiteMonitor {
	ctx, cancel := context.WithCancel(context.Background())
	return &SiteMonitor{
		ctx:         ctx,
		cancel:      cancel,
		config:      config,
		failCount:   make(map[string]int),
		results:     make([]SiteCheckResult, 0),
	}
}

// SetTunnel sets the active tunnel for checking
func (s *SiteMonitor) SetTunnel(tunnel *Tunnel) {
	s.tunnel = tunnel
}

// SetSitesDownCallback sets callback for site failures
func (s *SiteMonitor) SetSitesDownCallback(cb func([]string)) {
	s.onSitesDown = cb
}

// Start begins site monitoring
func (s *SiteMonitor) Start() {
	if !s.config.Enabled {
		return
	}

	s.checkerWg.Add(1)
	go s.monitorLoop()
}

// Stop stops site monitoring
func (s *SiteMonitor) Stop() {
	s.cancel()
	s.checkerWg.Wait()
}

// monitorLoop runs periodic site checks
func (s *SiteMonitor) monitorLoop() {
	defer s.checkerWg.Done()

	// Wait for tunnel to be ready
	for s.tunnel == nil || !s.tunnel.IsConnected() {
		select {
		case <-s.ctx.Done():
			return
		case <-time.After(5 * time.Second):
		}
	}

	// Initial check
	s.checkSites()

	ticker := time.NewTicker(s.config.Interval)
	defer ticker.Stop()

	for {
		select {
		case <-s.ctx.Done():
			return
		case <-ticker.C:
			s.checkSites()
		}
	}
}

// checkSites checks all configured sites
func (s *SiteMonitor) checkSites() {
	if s.tunnel == nil || !s.tunnel.IsConnected() {
		return
	}

	var failedSites []string
	var results []SiteCheckResult

	for _, site := range s.config.Sites {
		result := s.checkSite(site)
		results = append(results, result)

		if !result.Available {
			s.failCount[site]++
			if s.failCount[site] >= s.config.FailThreshold {
				failedSites = append(failedSites, site)
			}
		} else {
			s.failCount[site] = 0
		}
	}

	s.resultsMu.Lock()
	s.results = results
	s.resultsMu.Unlock()

	// If all sites failed, trigger callback
	if len(failedSites) == len(s.config.Sites) && s.onSitesDown != nil {
		s.onSitesDown(failedSites)
	}
}

// checkSite checks single site availability
func (s *SiteMonitor) checkSite(url string) SiteCheckResult {
	result := SiteCheckResult{
		URL:       url,
		Timestamp: time.Now(),
	}

	// Create HTTP client with timeout
	client := &http.Client{
		Timeout: s.config.Timeout,
		Transport: &http.Transport{
			// Use tunnel's proxy if available
			Proxy: nil, // Will use system proxy if set
			DialContext: (&net.Dialer{
				Timeout:   s.config.Timeout,
				KeepAlive: 30 * time.Second,
			}).DialContext,
		},
	}

	start := time.Now()
	resp, err := client.Get(url)
	if err != nil {
		result.Available = false
		result.Error = err.Error()
		return result
	}
	defer resp.Body.Close()

	result.Latency = time.Since(start).Milliseconds()
	result.StatusCode = resp.StatusCode
	result.Available = resp.StatusCode >= 200 && resp.StatusCode < 300

	return result
}

// GetResults returns last check results
func (s *SiteMonitor) GetResults() []SiteCheckResult {
	s.resultsMu.RLock()
	defer s.resultsMu.RUnlock()
	
	results := make([]SiteCheckResult, len(s.results))
	copy(results, s.results)
	return results
}

// evaluateFailover checks if failover is needed based on health status
func (h *HealthChecker) evaluateFailover() {
	if !h.config.FailoverEnabled {
		return
	}

	h.statusMu.RLock()
	activeID := h.activeServer
	h.statusMu.RUnlock()

	if activeID == "" {
		return
	}

	// Check if active server is healthy
	h.statusMu.RLock()
	activeHealth := h.healthStatus[activeID]
	h.statusMu.RUnlock()

	if activeHealth == nil || activeHealth.Available {
		return // Active server is healthy
	}

	// Find best alternative server
	var bestServer *ServerHealth
	h.statusMu.RLock()
	for id, health := range h.healthStatus {
		if id == activeID {
			continue
		}
		if !health.Available {
			continue
		}
		if bestServer == nil || health.Latency < bestServer.Latency {
			bestServer = health
		}
	}
	h.statusMu.RUnlock()

	if bestServer == nil {
		return // No healthy alternatives
	}

	// Trigger failover
	if h.onFailover != nil {
		h.onFailover(bestServer.ServerID)
	}
}

// GetHealthStatus returns health status for all servers
func (h *HealthChecker) GetHealthStatus() map[string]*ServerHealth {
	h.statusMu.RLock()
	defer h.statusMu.RUnlock()

	result := make(map[string]*ServerHealth)
	for k, v := range h.healthStatus {
		// Deep copy
		healthCopy := *v
		result[k] = &healthCopy
	}
	return result
}

// GetHealthStatusJSON returns health status as JSON string
func (h *HealthChecker) GetHealthStatusJSON() string {
	h.statusMu.RLock()
	defer h.statusMu.RUnlock()

	// Manual JSON construction for gomobile compatibility
	jsonStr := "["
	first := true
	for id, health := range h.healthStatus {
		if !first {
			jsonStr += ","
		}
		first = false
		
		jsonStr += fmt.Sprintf(`{
			"serverId": "%s",
			"serverName": "%s",
			"latency": %d,
			"available": %t,
			"consecutiveFails": %d,
			"lastCheck": %d,
			"lastSuccess": %d
		}`, id, health.ServerName, health.Latency, health.Available,
			health.ConsecutiveFails, health.LastCheck.Unix(),
			health.LastSuccess.Unix())
	}
	jsonStr += "]"
	
	return jsonStr
}

// GetBestServer returns ID of best available server
func (h *HealthChecker) GetBestServer() string {
	h.statusMu.RLock()
	defer h.statusMu.RUnlock()

	var best *ServerHealth
	for _, health := range h.healthStatus {
		if !health.Available {
			continue
		}
		if best == nil || health.Latency < best.Latency {
			best = health
		}
	}

	if best == nil {
		return ""
	}
	return best.ServerID
}

// ForceCheck triggers immediate health check
func (h *HealthChecker) ForceCheck() {
	go h.runChecks()
}

// MobileHealthChecker wraps HealthChecker for gomobile
type MobileHealthChecker struct {
	checker *HealthChecker
}

// NewMobileHealthChecker creates mobile health checker
func NewMobileHealthChecker(configJSON string) (*MobileHealthChecker, error) {
	var config HealthCheckConfig
	if err := json.Unmarshal([]byte(configJSON), &config); err != nil {
		return nil, err
	}
	
	return &MobileHealthChecker{
		checker: NewHealthChecker(config),
	}, nil
}

// Start starts health checking
func (m *MobileHealthChecker) Start() {
	m.checker.Start()
}

// Stop stops health checking
func (m *MobileHealthChecker) Stop() {
	m.checker.Stop()
}

// SetServers sets servers to check (JSON array)
func (m *MobileHealthChecker) SetServers(serversJSON string) {
	var servers []ServerConfig
	if err := json.Unmarshal([]byte(serversJSON), &servers); err != nil {
		return
	}
	m.checker.SetServers(servers)
}

// SetActiveServer sets active server ID
func (m *MobileHealthChecker) SetActiveServer(serverID string) {
	m.checker.SetActiveServer(serverID)
}

// GetHealthStatusJSON returns health status
func (m *MobileHealthChecker) GetHealthStatusJSON() string {
	return m.checker.GetHealthStatusJSON()
}

// GetBestServer returns best server ID
func (m *MobileHealthChecker) GetBestServer() string {
	return m.checker.GetBestServer()
}

// ForceCheck triggers immediate check
func (m *MobileHealthChecker) ForceCheck() {
	m.checker.ForceCheck()
}