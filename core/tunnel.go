package core

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/netip"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/box"
	"github.com/sagernet/sing-box/common/settings"
	"github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/control"
	F "github.com/sagernet/sing/common/format"
	"github.com/sagernet/sing/common/json/badjson"
	"github.com/sagernet/sing/service"
)

// TunnelState represents the current state of the VPN tunnel
type TunnelState int

const (
	StateDisconnected TunnelState = iota
	StateConnecting
	StateConnected
	StateDisconnecting
	StateError
)

func (s TunnelState) String() string {
	switch s {
	case StateDisconnected:
		return "disconnected"
	case StateConnecting:
		return "connecting"
	case StateConnected:
		return "connected"
	case StateDisconnecting:
		return "disconnecting"
	case StateError:
		return "error"
	default:
		return "unknown"
	}
}

// TunnelStats holds real-time statistics
type TunnelStats struct {
	UploadTotal   int64     `json:"uploadTotal"`
	DownloadTotal int64     `json:"downloadTotal"`
	UploadSpeed   int64     `json:"uploadSpeed"`
	DownloadSpeed int64     `json:"downloadSpeed"`
	Connections   int       `json:"connections"`
	LastUpdated   time.Time `json:"lastUpdated"`
}

// ServerInfo holds information about current server
type ServerInfo struct {
	Name      string `json:"name"`
	Address   string `json:"address"`
	Protocol  string `json:"protocol"`
	Port      uint16 `json:"port"`
	Country   string `json:"country"`
	Latency   int64  `json:"latency"` // ms
	Connected bool   `json:"connected"`
}

// Tunnel is the main VPN tunnel controller
type Tunnel struct {
	ctx           context.Context
	cancel        context.CancelFunc
	box           *box.Box
	state         TunnelState
	stateMu       sync.RWMutex
	config        *TunnelConfig
	stats         TunnelStats
	statsMu       sync.RWMutex
	serverInfo    ServerInfo
	serverInfoMu  sync.RWMutex
	tunOptions    tun.Options
	tunInterface  tun.Tun
	androidTunFd  int32 // File descriptor from Android VpnService
	logger        log.Logger
}

// TunnelConfig holds configuration for the tunnel
type TunnelConfig struct {
	// Server configuration
	Server ServerConfig `json:"server"`

	// DNS configuration
	DNS DNSConfig `json:"dns"`

	// Route configuration
	Route RouteConfig `json:"route"`

	// TUN configuration
	TUN TUNConfig `json:"tun"`

	// Experimental options
	Experimental ExperimentalConfig `json:"experimental"`
}

type ServerConfig struct {
	// Protocol: vmess, vless, trojan, shadowsocks, shadowsocksr, 
	// hysteria2, tuic, wireguard, warp
	Protocol string `json:"protocol"`

	// Server address
	Address string `json:"address"`
	Port    uint16 `json:"port"`

	// UUID for VMess/VLESS
	UUID string `json:"uuid,omitempty"`

	// Password for Trojan/SS/SSR
	Password string `json:"password,omitempty"`

	// Security method
	Method string `json:"method,omitempty"` // aes-128-gcm, chacha20-poly1305, etc.

	// TLS/XTLS settings
	TLS          bool   `json:"tls,omitempty"`
	ALPN         string `json:"alpn,omitempty"`
	SNI          string `json:"sni,omitempty"`
	AllowInsecure bool  `json:"allowInsecure,omitempty"`

	// Reality settings (for VLESS)
	RealityEnabled   bool   `json:"realityEnabled,omitempty"`
	RealityPublicKey string `json:"realityPublicKey,omitempty"`
	RealityShortID   string `json:"realityShortID,omitempty"`
	RealitySpiderX   string `json:"realitySpiderX,omitempty"`

	// WebSocket/gRPC settings
	Transport     string `json:"transport,omitempty"` // ws, grpc, httpupgrade
	Path          string `json:"path,omitempty"`
	Host          string `json:"host,omitempty"`
	Headers       map[string]string `json:"headers,omitempty"`

	// Hysteria2/TUIC settings
	ObfsType      string `json:"obfsType,omitempty"`
	ObfsPassword  string `json:"obfsPassword,omitempty"`
	CongestionControl string `json:"congestionControl,omitempty"`

	// WireGuard settings
	LocalAddress  []string `json:"localAddress,omitempty"`
	PrivateKey    string   `json:"privateKey,omitempty"`
	PeerPublicKey string   `json:"peerPublicKey,omitempty"`
	PreSharedKey  string   `json:"preSharedKey,omitempty"`
	Reserved      []uint8  `json:"reserved,omitempty"`

	// WARP settings (Cloudflare)
	WarpEnabled   bool   `json:"warpEnabled,omitempty"`
	WarpKey       string `json:"warpKey,omitempty"`
	WarpDeviceID  string `json:"warpDeviceId,omitempty"`

	// Connection metadata
	Name    string `json:"name"`
	Group   string `json:"group"`
	Tag     string `json:"tag"`
}

type DNSConfig struct {
	Servers          []DNSServer `json:"servers"`
	Rules            []DNSRule   `json:"rules,omitempty"`
	Final            string      `json:"final,omitempty"`
	Strategy         string      `json:"strategy,omitempty"` // ipv4_only, ipv6_only, prefer_ipv4, prefer_ipv6
	DisableCache     bool        `json:"disableCache,omitempty"`
	DisableExpire    bool        `json:"disableExpire,omitempty"`
	FakeIP           bool        `json:"fakeip,omitempty"`
	FakeIPRange      string      `json:"fakeipRange,omitempty"`
}

type DNSServer struct {
	Tag             string `json:"tag"`
	Address         string `json:"address"` // 8.8.8.8, https://1.1.1.1/dns-query, etc.
	AddressResolver string `json:"addressResolver,omitempty"`
	Strategy        string `json:"strategy,omitempty"`
}

type DNSRule struct {
	Type        string   `json:"type"` // domain, domain_suffix, domain_keyword, geosite, geoip
	Domain      []string `json:"domain,omitempty"`
	DomainSuffix []string `json:"domainSuffix,omitempty"`
	GeoSite     []string `json:"geosite,omitempty"`
	GeoIP       []string `json:"geoip,omitempty"`
	Server      string   `json:"server"`
	DisableCache bool    `json:"disableCache,omitempty"`
}

type RouteConfig struct {
	Rules          []RouteRule `json:"rules"`
	Final          string      `json:"final"` // proxy, direct, reject
	AutoDetectInterface bool   `json:"autoDetectInterface,omitempty"`
	OverrideAndroidVPN  bool   `json:"overrideAndroidVpn,omitempty"`
}

type RouteRule struct {
	Type          string   `json:"type"`
	Domain        []string `json:"domain,omitempty"`
	DomainSuffix  []string `json:"domainSuffix,omitempty"`
	DomainKeyword []string `json:"domainKeyword,omitempty"`
	DomainRegex   []string `json:"domainRegex,omitempty"`
	GeoSite       []string `json:"geosite,omitempty"`
	GeoIP         []string `json:"geoip,omitempty"`
	Port          []uint16 `json:"port,omitempty"`
	ProcessName   []string `json:"processName,omitempty"`
	Outbound      string   `json:"outbound"` // proxy, direct, reject
}

type TUNConfig struct {
	InterfaceName string   `json:"interfaceName,omitempty"`
	MTU           uint32   `json:"mtu,omitempty"`
	Address       []string `json:"address"` // 10.0.0.2/32, fdfe:dcba:9876::1/126
	Gateway       string   `json:"gateway,omitempty"`
	DNSHijack     []string `json:"dnsHijack,omitempty"`
	AutoRoute     bool     `json:"autoRoute,omitempty"`
	StrictRoute   bool     `json:"strictRoute,omitempty"`
	Stack         string   `json:"stack,omitempty"` // system, gvisor, mixed
}

type ExperimentalConfig struct {
	// Cache file for GeoIP/Geosite
	CacheFile string `json:"cacheFile,omitempty"`

	// Clash API (for external controller)
	ClashAPI ClashAPIConfig `json:"clashApi,omitempty"`

	// V2Ray API
	V2RayAPI V2RayAPIConfig `json:"v2rayApi,omitempty"`
}

type ClashAPIConfig struct {
	Enabled    bool   `json:"enabled"`
	Listen     string `json:"listen,omitempty"`
	Secret     string `json:"secret,omitempty"`
	ExternalUI string `json:"externalUi,omitempty"`
}

type V2RayAPIConfig struct {
	Enabled bool   `json:"enabled"`
	Listen  string `json:"listen,omitempty"`
}

// NewTunnel creates a new tunnel instance
func NewTunnel() *Tunnel {
	ctx, cancel := context.WithCancel(context.Background())
	return &Tunnel{
		ctx:    ctx,
		cancel: cancel,
		state:  StateDisconnected,
		logger: log.NewLogger("tunnel"),
		tunOptions: tun.Options{
			MTU: 9000,
			Inet4Address: []netip.Prefix{
				netip.MustParsePrefix("10.0.0.2/32"),
			},
			Inet6Address: []netip.Prefix{
				netip.MustParsePrefix("fdfe:dcba:9876::2/128"),
			},
		},
	}
}

// SetAndroidTunFd sets the TUN file descriptor from Android VpnService
// This is called from Java/Kotlin side when VPN is established
func (t *Tunnel) SetAndroidTunFd(fd int32) {
	t.androidTunFd = fd
	t.logger.Info("Android TUN fd set: ", fd)
}

// GetState returns current tunnel state
func (t *Tunnel) GetState() TunnelState {
	t.stateMu.RLock()
	defer t.stateMu.RUnlock()
	return t.state
}

func (t *Tunnel) setState(state TunnelState) {
	t.stateMu.Lock()
	t.state = state
	t.stateMu.Unlock()
}

// GetStats returns current statistics
func (t *Tunnel) GetStats() TunnelStats {
	t.statsMu.RLock()
	defer t.statsMu.RUnlock()
	return t.stats
}

// GetServerInfo returns current server information
func (t *Tunnel) GetServerInfo() ServerInfo {
	t.serverInfoMu.RLock()
	defer t.serverInfoMu.RUnlock()
	return t.serverInfo
}
// Start initializes and starts the tunnel with given configuration
func (t *Tunnel) Start(configJSON string) error {
	t.setState(StateConnecting)

	// Parse configuration
	var config TunnelConfig
	if err := json.Unmarshal([]byte(configJSON), &config); err != nil {
		t.setState(StateError)
		return fmt.Errorf("failed to parse config: %w", err)
	}
	t.config = &config

	// Update server info
	t.serverInfoMu.Lock()
	t.serverInfo = ServerInfo{
		Name:     config.Server.Name,
		Address:  config.Server.Address,
		Protocol: config.Server.Protocol,
		Port:     config.Server.Port,
		Country:  config.Server.Group,
		Connected: false,
	}
	t.serverInfoMu.Unlock()

	// Build sing-box options
	options, err := t.buildOptions()
	if err != nil {
		t.setState(StateError)
		return fmt.Errorf("failed to build options: %w", err)
	}

	// Create context with service manager
	ctx := service.ContextWith[t.Context](t.ctx, nil)

	// Create the box instance
	instance, err := box.New(box.Options{
		Context: ctx,
		Options: options,
	})
	if err != nil {
		t.setState(StateError)
		return fmt.Errorf("failed to create box: %w", err)
	}

	t.box = instance

	// Start the instance
	if err := instance.Start(); err != nil {
		t.setState(StateError)
		return fmt.Errorf("failed to start box: %w", err)
	}

	// Setup TUN if in VPN mode
	if t.androidTunFd >= 0 {
		if err := t.setupTUN(); err != nil {
			t.setState(StateError)
			return fmt.Errorf("failed to setup TUN: %w", err)
		}
	}

	// Start statistics collector
	go t.collectStats()

	t.setState(StateConnected)
	t.serverInfoMu.Lock()
	t.serverInfo.Connected = true
	t.serverInfoMu.Unlock()

	t.logger.Info("Tunnel started successfully")
	return nil
}

// Stop gracefully stops the tunnel
func (t *Tunnel) Stop() error {
	t.setState(StateDisconnecting)

	// Cancel context
	if t.cancel != nil {
		t.cancel()
	}

	// Close TUN interface
	if t.tunInterface != nil {
		if err := t.tunInterface.Close(); err != nil {
			t.logger.Warn("Failed to close TUN: ", err)
		}
		t.tunInterface = nil
	}

	// Stop box instance
	if t.box != nil {
		if err := t.box.Close(); err != nil {
			t.logger.Warn("Failed to close box: ", err)
		}
		t.box = nil
	}

	// Reset state
	t.androidTunFd = -1
	t.setState(StateDisconnected)
	
	t.serverInfoMu.Lock()
	t.serverInfo.Connected = false
	t.serverInfoMu.Unlock()

	t.logger.Info("Tunnel stopped")
	return nil
}

// TestLatency performs a latency test to the configured server
func (t *Tunnel) TestLatency() (int64, error) {
	if t.config == nil {
		return -1, fmt.Errorf("no configuration loaded")
	}

	server := t.config.Server
	address := net.JoinHostPort(server.Address, fmt.Sprintf("%d", server.Port))

	// Create a test connection based on protocol
	start := time.Now()
	
	// Simple TCP connect test (can be enhanced with protocol-specific tests)
	conn, err := net.DialTimeout("tcp", address, 5*time.Second)
	if err != nil {
		return -1, err
	}
	defer conn.Close()

	latency := time.Since(start).Milliseconds()

	// Update server info
	t.serverInfoMu.Lock()
	t.serverInfo.Latency = latency
	t.serverInfoMu.Unlock()

	return latency, nil
}

// buildOptions creates sing-box configuration from our TunnelConfig
func (t *Tunnel) buildOptions() (option.Options, error) {
	opts := option.Options{
		Log: &option.LogOptions{
			Disabled:  false,
			Level:     "info",
			Output:    "",
			Timestamp: true,
		},
	}

	// Build outbounds
	outbound, err := t.buildOutbound()
	if err != nil {
		return opts, err
	}
	opts.Outbounds = []option.Outbound{outbound}

	// Build DNS
	opts.DNS = t.buildDNS()

	// Build route
	opts.Route = t.buildRoute()

	// Build inbounds (TUN or SOCKS/HTTP proxy)
	inbounds, err := t.buildInbounds()
	if err != nil {
		return opts, err
	}
	opts.Inbounds = inbounds

	// Build experimental
	opts.Experimental = t.buildExperimental()

	return opts, nil
}

func (t *Tunnel) buildOutbound() (option.Outbound, error) {
	server := t.config.Server
	tag := "proxy"
	if server.Tag != "" {
		tag = server.Tag
	}

	outbound := option.Outbound{
		Tag:  tag,
		Type: server.Protocol,
	}

	// Build protocol-specific options
	switch server.Protocol {
	case "vmess":
		outbound.VMessOptions = option.VMessOutboundOptions{
			ServerOptions: option.ServerOptions{
				Server:     server.Address,
				ServerPort: server.Port,
			},
			UUID: server.UUID,
			Security: "auto",
		}
		if server.TLS {
			outbound.VMessOptions.TLS = &option.OutboundTLSOptions{
				Enabled:    true,
				ServerName: server.SNI,
				Insecure:   server.AllowInsecure,
			}
			if server.ALPN != "" {
				outbound.VMessOptions.TLS.ALPN = []string{server.ALPN}
			}
		}
		if server.Transport != "" {
			outbound.VMessOptions.Transport = &option.V2RayTransportOptions{
				Type: server.Transport,
			}
			if server.Transport == "ws" {
				outbound.VMessOptions.Transport.WebsocketOptions = option.V2RayWebsocketOptions{
					Path: server.Path,
					Headers: server.Headers,
				}
			}
		}

	case "vless":
		outbound.VLESSOptions = option.VLESSOutboundOptions{
			ServerOptions: option.ServerOptions{
				Server:     server.Address,
				ServerPort: server.Port,
			},
			UUID: server.UUID,
		}
		if server.TLS {
			outbound.VLESSOptions.TLS = &option.OutboundTLSOptions{
				Enabled:    true,
				ServerName: server.SNI,
				Insecure:   server.AllowInsecure,
			}
		}
		if server.RealityEnabled {
			outbound.VLESSOptions.TLS.Reality = &option.OutboundRealityOptions{
				Enabled:   true,
				PublicKey: server.RealityPublicKey,
				ShortID:   server.RealityShortID,
				SpiderX:   server.RealitySpiderX,
			}
		}

	case "trojan":
		outbound.TrojanOptions = option.TrojanOutboundOptions{
			ServerOptions: option.ServerOptions{
				Server:     server.Address,
				ServerPort: server.Port,
			},
			Password: server.Password,
		}
		if server.TLS {
			outbound.TrojanOptions.TLS = &option.OutboundTLSOptions{
				Enabled:    true,
				ServerName: server.SNI,
				Insecure:   server.AllowInsecure,
			}
		}

	case "shadowsocks":
		outbound.ShadowsocksOptions = option.ShadowsocksOutboundOptions{
			ServerOptions: option.ServerOptions{
				Server:     server.Address,
				ServerPort: server.Port,
			},
			Method:   server.Method,
			Password: server.Password,
		}

	case "shadowsocksr":
		// SSR uses shadowsocks with extra options
		outbound.ShadowsocksOptions = option.ShadowsocksOutboundOptions{
			ServerOptions: option.ServerOptions{
				Server:     server.Address,
				ServerPort: server.Port,
			},
			Method:   server.Method,
			Password: server.Password,
		}

	case "hysteria2":
		outbound.Hysteria2Options = option.Hysteria2OutboundOptions{
			ServerOptions: option.ServerOptions{
				Server:     server.Address,
				ServerPort: server.Port,
			},
			Password: server.Password,
		}
		if server.ObfsType != "" {
			outbound.Hysteria2Options.Obfs = &option.Hysteria2Obfs{
				Type:     server.ObfsType,
				Password: server.ObfsPassword,
			}
		}

	case "tuic":
		outbound.TUICOptions = option.TUICOutboundOptions{
			ServerOptions: option.ServerOptions{
				Server:     server.Address,
				ServerPort: server.Port,
			},
			UUID:     server.UUID,
			Password: server.Password,
			CongestionControl: server.CongestionControl,
		}

	case "wireguard":
		outbound.WireGuardOptions = option.WireGuardOutboundOptions{
			ServerOptions: option.ServerOptions{
				Server:     server.Address,
				ServerPort: server.Port,
			},
			PrivateKey:    server.PrivateKey,
			PeerPublicKey: server.PeerPublicKey,
			PreSharedKey:  server.PreSharedKey,
			Reserved:      server.Reserved,
		}
		for _, addr := range server.LocalAddress {
			prefix, err := netip.ParsePrefix(addr)
			if err != nil {
				return outbound, fmt.Errorf("invalid WireGuard address: %s", addr)
			}
			outbound.WireGuardOptions.Address = append(outbound.WireGuardOptions.Address, prefix)
		}

	case "warp":
		// WARP uses wireguard with Cloudflare specific settings
		outbound.WireGuardOptions = option.WireGuardOutboundOptions{
			ServerOptions: option.ServerOptions{
				Server:     "engage.cloudflareclient.com",
				ServerPort: 2408,
			},
		}
		// WARP key generation would be handled separately

	default:
		return outbound, fmt.Errorf("unsupported protocol: %s", server.Protocol)
	}

	return outbound, nil
}

func (t *Tunnel) buildDNS() *option.DNSOptions {
	if t.config.DNS.Servers == nil || len(t.config.DNS.Servers) == 0 {
		// Default DNS configuration
		return &option.DNSOptions{
			Servers: []option.DNSServerOptions{
				{
					Tag:     "remote",
					Address: "https://1.1.1.1/dns-query",
				},
				{
					Tag:     "local",
					Address: "223.5.5.5",
					Detour:  "direct",
				},
			},
			Rules: []option.DNSRule{
				{
					Type: "logical",
					LogicalDNSRule: option.LogicalDNSRule{
						Mode: "or",
						Rules: []option.DNSRule{
							{
								Type:       "geosite",
								Geosite:    &option.GeositeDNSRule{Geosite: []string{"cn"}},
							},
							{
								Type:      "geosite",
								Geosite:   &option.GeositeDNSRule{Geosite: []string{"category-games@cn"}},
							},
						},
					},
					Server: "local",
				},
			},
			Final: "remote",
		}
	}

	// Build from config
	var servers []option.DNSServerOptions
	for _, s := range t.config.DNS.Servers {
		srv := option.DNSServerOptions{
			Tag:     s.Tag,
			Address: s.Address,
		}
		if s.AddressResolver != "" {
			srv.AddressResolver = s.AddressResolver
		}
		if s.Strategy != "" {
			srv.Strategy = s.Strategy
		}
		servers = append(servers, srv)
	}

	return &option.DNSOptions{
		Servers:      servers,
		Final:        t.config.DNS.Final,
		Strategy:     t.config.DNS.Strategy,
		DisableCache: t.config.DNS.DisableCache,
		DisableExpire: t.config.DNS.DisableExpire,
	}
}

func (t *Tunnel) buildRoute() *option.RouteOptions {
	route := &option.RouteOptions{
		AutoDetectInterface: t.config.Route.AutoDetectInterface,
		Final:               "proxy",
	}

	if t.config.Route.Final != "" {
		route.Final = t.config.Route.Final
	}

	// Build rules
	for _, rule := range t.config.Route.Rules {
		r := option.Rule{
			Type: rule.Type,
		}

		switch rule.Type {
		case "domain":
			r.Domain = &option.DomainRule{Domain: rule.Domain}
		case "domain_suffix":
			r.DomainSuffix = &option.DomainSuffixRule{DomainSuffix: rule.DomainSuffix}
		case "domain_keyword":
			r.DomainKeyword = &option.DomainKeywordRule{DomainKeyword: rule.DomainKeyword}
		case "geosite":
			r.Geosite = &option.GeositeRule{Geosite: rule.GeoSite}
		case "geoip":
			r.GeoIP = &option.GeoIPRule{GeoIP: rule.GeoIP}
		}

		r.Outbound = rule.Outbound
		route.Rules = append(route.Rules, r)
	}

	// Default rules if none specified
	if len(route.Rules) == 0 {
		route.Rules = []option.Rule{
			{
				Type:     "geosite",
				Geosite:  &option.GeositeRule{Geosite: []string{"cn", "private"}},
				Outbound: "direct",
			},
			{
				Type:    "geoip",
				GeoIP:   &option.GeoIPRule{GeoIP: []string{"cn", "private"}},
				Outbound: "direct",
			},
		}
	}

	return route
}
func (t *Tunnel) buildInbounds() ([]option.Inbound, error) {
	var inbounds []option.Inbound

	// Always add direct outbound reference
	inbounds = append(inbounds, option.Inbound{
		Type: "direct",
		Tag:  "direct",
	})

	// If we have Android TUN fd, configure TUN inbound
	if t.androidTunFd >= 0 {
		tunInbound := option.Inbound{
			Type: "tun",
			Tag:  "tun-in",
			TunOptions: option.TunInboundOptions{
				MTU:          t.config.TUN.MTU,
				AutoRoute:    t.config.TUN.AutoRoute,
				StrictRoute:  t.config.TUN.StrictRoute,
				EndpointIndependentNat: true,
			},
		}

		// Set addresses
		for _, addr := range t.config.TUN.Address {
			prefix, err := netip.ParsePrefix(addr)
			if err != nil {
				return nil, fmt.Errorf("invalid TUN address: %s", addr)
			}
			if prefix.Addr().Is4() {
				tunInbound.TunOptions.Inet4Address = append(tunInbound.TunOptions.Inet4Address, prefix)
			} else {
				tunInbound.TunOptions.Inet6Address = append(tunInbound.TunOptions.Inet6Address, prefix)
			}
		}

		// Set stack (system, gvisor, or mixed)
		if t.config.TUN.Stack != "" {
			tunInbound.TunOptions.Stack = t.config.TUN.Stack
		} else {
			tunInbound.TunOptions.Stack = "mixed" // default
		}

		// DNS hijack
		if len(t.config.TUN.DNSHijack) > 0 {
			tunInbound.TunOptions.DNSHijack = t.config.TUN.DNSHijack
		} else {
			tunInbound.TunOptions.DNSHijack = []string{"tcp://8.8.8.8:53"}
		}

		inbounds = append(inbounds, tunInbound)
	} else {
		// Proxy mode: SOCKS5 + HTTP inbounds
		socksInbound := option.Inbound{
			Type: "socks",
			Tag:  "socks-in",
			SocksOptions: option.SocksInboundOptions{
				ListenOptions: option.ListenOptions{
					Listen:     option.NewListenAddress(netip.MustParseAddr("127.0.0.1")),
					ListenPort: 10808,
				},
			},
		}
		inbounds = append(inbounds, socksInbound)

		httpInbound := option.Inbound{
			Type: "http",
			Tag:  "http-in",
			HTTPOptions: option.HTTPMixedInboundOptions{
				ListenOptions: option.ListenOptions{
					Listen:     option.NewListenAddress(netip.MustParseAddr("127.0.0.1")),
					ListenPort: 10809,
				},
			},
		}
		inbounds = append(inbounds, httpInbound)
	}

	return inbounds, nil
}

func (t *Tunnel) buildExperimental() *option.ExperimentalOptions {
	exp := &option.ExperimentalOptions{}

	// Cache file for GeoIP/Geosite
	if t.config.Experimental.CacheFile != "" {
		exp.CacheFile = &option.CacheFileOptions{
			Enabled: true,
			Path:    t.config.Experimental.CacheFile,
		}
	}

	// Clash API
	if t.config.Experimental.ClashAPI.Enabled {
		exp.ClashAPI = &option.ClashAPIOptions{
			ExternalController: t.config.Experimental.ClashAPI.Listen,
			Secret:             t.config.Experimental.ClashAPI.Secret,
		}
		if t.config.Experimental.ClashAPI.ExternalUI != "" {
			exp.ClashAPI.ExternalUI = t.config.Experimental.ClashAPI.ExternalUI
		}
	}

	return exp
}

// setupTUN configures the TUN interface using Android file descriptor
func (t *Tunnel) setupTUN() error {
	if t.androidTunFd < 0 {
		return fmt.Errorf("no TUN file descriptor provided")
	}

	// Create TUN options
	options := tun.Options{
		Name: t.config.TUN.InterfaceName,
		MTU:  uint32(t.config.TUN.MTU),
	}

	if options.MTU == 0 {
		options.MTU = 9000
	}

	// Parse addresses
	for _, addr := range t.config.TUN.Address {
		prefix, err := netip.ParsePrefix(addr)
		if err != nil {
			return fmt.Errorf("invalid address %s: %w", addr, err)
		}
		if prefix.Addr().Is4() {
			options.Inet4Address = append(options.Inet4Address, prefix)
		} else {
			options.Inet6Address = append(options.Inet6Address, prefix)
		}
	}

	// Create TUN interface from Android fd
	tunInterface, err := tun.New(t.ctx, options, t.androidTunFd)
	if err != nil {
		return fmt.Errorf("failed to create TUN: %w", err)
	}

	t.tunInterface = tunInterface
	t.logger.Info("TUN interface created successfully")
	return nil
}

// collectStats periodically collects statistics from the box instance
func (t *Tunnel) collectStats() {
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

	var lastUpload, lastDownload int64

	for {
		select {
		case <-t.ctx.Done():
			return
		case <-ticker.C:
			if t.box == nil {
				continue
			}

			// Get statistics from service manager
			var uploadTotal, downloadTotal int64
			
			// Access statistics through service context
			if statsService := service.FromContext[adapter.StatisticsService](t.ctx); statsService != nil {
				uploadTotal = statsService.UploadTotal()
				downloadTotal = statsService.DownloadTotal()
			}

			// Calculate speeds
			uploadSpeed := uploadTotal - lastUpload
			downloadSpeed := downloadTotal - lastDownload
			lastUpload = uploadTotal
			lastDownload = downloadTotal

			// Update stats
			t.statsMu.Lock()
			t.stats.UploadTotal = uploadTotal
			t.stats.DownloadTotal = downloadTotal
			t.stats.UploadSpeed = uploadSpeed
			t.stats.DownloadSpeed = downloadSpeed
			t.stats.LastUpdated = time.Now()
			t.statsMu.Unlock()

			// Count active connections
			if router := service.FromContext[adapter.Router](t.ctx); router != nil {
				// Connection count would be tracked by connection tracker
			}
		}
	}
}

// GetConfigJSON returns current configuration as JSON
func (t *Tunnel) GetConfigJSON() (string, error) {
	if t.config == nil {
		return "", fmt.Errorf("no configuration loaded")
	}
	
	data, err := json.MarshalIndent(t.config, "", "  ")
	if err != nil {
		return "", err
	}
	return string(data), nil
}

// UpdateConfig updates configuration without restarting (hot reload)
func (t *Tunnel) UpdateConfig(configJSON string) error {
	if t.GetState() != StateConnected {
		return fmt.Errorf("tunnel not connected")
	}

	var newConfig TunnelConfig
	if err := json.Unmarshal([]byte(configJSON), &newConfig); err != nil {
		return fmt.Errorf("invalid config: %w", err)
	}

	// For now, require restart for major changes
	// In future, implement hot reload for route/DNS changes only
	t.config = &newConfig
	return nil
}

// GetLogLevel returns current log level
func (t *Tunnel) GetLogLevel() string {
	return "info" // TODO: make configurable
}

// SetLogLevel sets log level dynamically
func (t *Tunnel) SetLogLevel(level string) {
	// TODO: implement dynamic log level change
	t.logger.Info("Log level change requested: ", level)
}

// IsConnected returns true if tunnel is connected
func (t *Tunnel) IsConnected() bool {
	return t.GetState() == StateConnected
}

// GetVersion returns core version
func (t *Tunnel) GetVersion() string {
	return "1.0.0-singbox"
}
