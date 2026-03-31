package core

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/url"
	"strconv"
	"strings"
)

// SubscriptionParser handles various subscription formats
type SubscriptionParser struct{}

// NewSubscriptionParser creates new parser
func NewSubscriptionParser() *SubscriptionParser {
	return &SubscriptionParser{}
}

// ParseResult holds parsed servers
type ParseResult struct {
	Servers []ServerConfig `json:"servers"`
	Name    string         `json:"name,omitempty"`
	URL     string         `json:"url"`
	Format  string         `json:"format"`
}

// ParseSubscription parses subscription content in various formats
func (p *SubscriptionParser) ParseSubscription(content string, subURL string) (*ParseResult, error) {
	content = strings.TrimSpace(content)
	
	// Try detect format
	if strings.HasPrefix(content, "vmess://") || 
	   strings.HasPrefix(content, "vless://") ||
	   strings.HasPrefix(content, "ss://") ||
	   strings.HasPrefix(content, "trojan://") ||
	   strings.HasPrefix(content, "hysteria2://") ||
	   strings.HasPrefix(content, "tuic://") {
		// Plain text URLs (one per line or mixed)
		return p.parsePlainURLs(content, subURL)
	}

	// Try base64 decode
	if decoded, err := p.tryBase64Decode(content); err == nil {
		// Check if decoded content is URLs
		if strings.Contains(decoded, "://") {
			return p.parsePlainURLs(decoded, subURL)
		}
	}

	// Try JSON (sing-box format)
	if strings.HasPrefix(content, "{") {
		return p.parseSingBoxJSON(content, subURL)
	}

	// Try YAML (Clash format)
	if strings.Contains(content, "proxies:") || strings.Contains(content, "Proxy:") {
		return p.parseClashYAML(content, subURL)
	}

	return nil, fmt.Errorf("unknown subscription format")
}

// tryBase64Decode attempts to base64 decode
func (p *SubscriptionParser) tryBase64Decode(content string) (string, error) {
	// Try standard base64
	decoded, err := base64.StdEncoding.DecodeString(content)
	if err == nil {
		return string(decoded), nil
	}
	
	// Try URL-safe base64
	decoded, err = base64.URLEncoding.DecodeString(content)
	if err == nil {
		return string(decoded), nil
	}
	
	return "", fmt.Errorf("not base64")
}

// parsePlainURLs parses plain text URLs (one per line)
func (p *SubscriptionParser) parsePlainURLs(content string, subURL string) (*ParseResult, error) {
	lines := strings.Split(content, "\n")
	var servers []ServerConfig

	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}

		server, err := p.parseURL(line)
		if err != nil {
			continue // Skip invalid URLs
		}
		servers = append(servers, server)
	}

	if len(servers) == 0 {
		return nil, fmt.Errorf("no valid servers found")
	}

	return &ParseResult{
		Servers: servers,
		URL:     subURL,
		Format:  "plain",
	}, nil
}

// parseURL parses single proxy URL
func (p *SubscriptionParser) parseURL(uri string) (ServerConfig, error) {
	var config ServerConfig

	u, err := url.Parse(uri)
	if err != nil {
		return config, err
	}

	config.Protocol = u.Scheme
	config.Tag = u.Fragment
	if config.Tag == "" {
		config.Tag = fmt.Sprintf("%s-%s", config.Protocol, u.Host)
	}

	switch config.Protocol {
	case "vmess":
		return p.parseVMess(u)
	case "vless":
		return p.parseVLESS(u)
	case "ss", "shadowsocks":
		return p.parseShadowsocks(u)
	case "trojan":
		return p.parseTrojan(u)
	case "hysteria2", "hy2":
		return p.parseHysteria2(u)
	case "tuic":
		return p.parseTUIC(u)
	default:
		return config, fmt.Errorf("unsupported protocol: %s", config.Protocol)
	}
}

// parseVMess parses VMess URL (vmess://base64json)
func (p *SubscriptionParser) parseVMess(u *url.URL) (ServerConfig, error) {
	var config ServerConfig
	config.Protocol = "vmess"

	// Decode base64 JSON
	encoded := u.Host + u.Path
	encoded = strings.TrimSuffix(encoded, "/")
	
	decoded, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return config, err
	}

	var vmess struct {
		V    string `json:"v"`
		PS   string `json:"ps"`   // Name
		Add  string `json:"add"`  // Address
		Port string `json:"port"` // Port
		ID   string `json:"id"`   // UUID
		Aid  string `json:"aid"`  // AlterID
		Scy  string `json:"scy"`  // Security
		Net  string `json:"net"`  // Network (tcp, ws, grpc)
		Type string `json:"type"` // Camouflage type
		Host string `json:"host"` // Host
		Path string `json:"path"` // Path
		TLS  string `json:"tls"`  // TLS type
		SNI  string `json:"sni"`  // SNI
	}

	if err := json.Unmarshal(decoded, &vmess); err != nil {
		return config, err
	}

	config.Name = vmess.PS
	config.Address = vmess.Add
	config.UUID = vmess.ID
	
	port, _ := strconv.Atoi(vmess.Port)
	config.Port = uint16(port)
	
	config.Method = vmess.Scy
	if config.Method == "" {
		config.Method = "auto"
	}

	// Transport
	if vmess.Net != "" && vmess.Net != "tcp" {
		config.Transport = vmess.Net
		config.Host = vmess.Host
		config.Path = vmess.Path
	}

	// TLS
	if vmess.TLS == "tls" || vmess.TLS == "xtls" {
		config.TLS = true
		config.SNI = vmess.SNI
		if config.SNI == "" {
			config.SNI = vmess.Host
		}
	}

	return config, nil
}

// parseVLESS parses VLESS URL
func (p *SubscriptionParser) parseVLESS(u *url.URL) (ServerConfig, error) {
	var config ServerConfig
	config.Protocol = "vless"

	config.Address = u.Hostname()
	port, _ := strconv.Atoi(u.Port())
	config.Port = uint16(port)
	config.UUID = u.User.Username()

	// Parse query parameters
	query := u.Query()
	config.Name = query.Get("remark")
	if config.Name == "" {
		config.Name = u.Fragment
	}

	config.SNI = query.Get("sni")
	config.TLS = query.Get("security") == "tls" || query.Get("security") == "xtls" || query.Get("security") == "reality"
	
	// Reality settings
	if query.Get("security") == "reality" {
		config.RealityEnabled = true
		config.RealityPublicKey = query.Get("pbk")
		config.RealityShortID = query.Get("sid")
		config.RealitySpiderX = query.Get("spx")
	}

	// Transport
	config.Transport = query.Get("type")
	if config.Transport != "" && config.Transport != "tcp" {
		config.Host = query.Get("host")
		config.Path = query.Get("path")
	}

	return config, nil
}

// parseShadowsocks parses Shadowsocks URL
func (p *SubscriptionParser) parseShadowsocks(u *url.URL) (ServerConfig, error) {
	var config ServerConfig
	config.Protocol = "shadowsocks"

	// Format: ss://method:password@host:port#name
	// Or: ss://base64(method:password)@host:port#name

	config.Address = u.Hostname()
	port, _ := strconv.Atoi(u.Port())
	config.Port = uint16(port)
	config.Name = u.Fragment

	// Parse user info
	userInfo := u.User.String()
	if decoded, err := base64.StdEncoding.DecodeString(userInfo); err == nil {
		// base64 encoded
		parts := strings.SplitN(string(decoded), ":", 2)
		if len(parts) == 2 {
			config.Method = parts[0]
			config.Password = parts[1]
		}
	} else {
		// plain
		config.Method = u.User.Username()
		config.Password, _ = u.User.Password()
	}

	return config, nil
}

// parseTrojan parses Trojan URL
func (p *SubscriptionParser) parseTrojan(u *url.URL) (ServerConfig, error) {
	var config ServerConfig
	config.Protocol = "trojan"

	config.Address = u.Hostname()
	port, _ := strconv.Atoi(u.Port())
	config.Port = uint16(port)
	config.Password = u.User.Username()
	config.Name = u.Fragment

	query := u.Query()
	config.SNI = query.Get("sni")
	config.TLS = true // Trojan always uses TLS
	config.AllowInsecure = query.Get("allowInsecure") == "1"

	return config, nil
}

// parseHysteria2 parses Hysteria2 URL
func (p *SubscriptionParser) parseHysteria2(u *url.URL) (ServerConfig, error) {
	var config ServerConfig
	config.Protocol = "hysteria2"

	config.Address = u.Hostname()
	port, _ := strconv.Atoi(u.Port())
	config.Port = uint16(port)
	config.Password = u.User.Username()
	config.Name = u.Fragment

	query := u.Query()
	config.SNI = query.Get("sni")
	config.ObfsType = query.Get("obfs")
	config.ObfsPassword = query.Get("obfs-password")

	return config, nil
}

// parseTUIC parses TUIC URL
func (p *SubscriptionParser) parseTUIC(u *url.URL) (ServerConfig, error) {
	var config ServerConfig
	config.Protocol = "tuic"

	config.Address = u.Hostname()
	port, _ := strconv.Atoi(u.Port())
	config.Port = uint16(port)
	
	// TUIC format: tuic://uuid:password@host:port
	config.UUID = u.User.Username()
	config.Password, _ = u.User.Password()
	config.Name = u.Fragment

	query := u.Query()
	config.SNI = query.Get("sni")
	config.CongestionControl = query.Get("congestion_control")
	if config.CongestionControl == "" {
		config.CongestionControl = "bbr"
	}

	return config, nil
}

// parseSingBoxJSON parses sing-box JSON configuration
func (p *SubscriptionParser) parseSingBoxJSON(content string, subURL string) (*ParseResult, error) {
	var singboxConfig struct {
		Outbounds []json.RawMessage `json:"outbounds"`
	}

	if err := json.Unmarshal([]byte(content), &singboxConfig); err != nil {
		return nil, err
	}

	var servers []ServerConfig
	for _, raw := range singboxConfig.Outbounds {
		var base struct {
			Type string `json:"type"`
			Tag  string `json:"tag"`
		}
		if err := json.Unmarshal(raw, &base); err != nil {
			continue
		}

		// Skip non-proxy outbounds
		if base.Type == "direct" || base.Type == "block" || base.Type == "dns" {
			continue
		}

		server := ServerConfig{
			Protocol: base.Type,
			Tag:      base.Tag,
			Name:     base.Tag,
		}

		// Parse based on type
		switch base.Type {
		case "vmess":
			var vmess struct {
				Server   string `json:"server"`
				Port     uint16 `json:"server_port"`
				UUID     string `json:"uuid"`
				Security string `json:"security"`
			}
			json.Unmarshal(raw, &vmess)
			server.Address = vmess.Server
			server.Port = vmess.Port
			server.UUID = vmess.UUID
			server.Method = vmess.Security

		case "vless":
			var vless struct {
				Server string `json:"server"`
				Port   uint16 `json:"server_port"`
				UUID   string `json:"uuid"`
			}
			json.Unmarshal(raw, &vless)
			server.Address = vless.Server
			server.Port = vless.Port
			server.UUID = vless.UUID

		case "trojan":
			var trojan struct {
				Server   string `json:"server"`
				Port     uint16 `json:"server_port"`
				Password string `json:"password"`
			}
			json.Unmarshal(raw, &trojan)
			server.Address = trojan.Server
			server.Port = trojan.Port
			server.Password = trojan.Password

		case "shadowsocks":
			var ss struct {
				Server   string `json:"server"`
				Port     uint16 `json:"server_port"`
				Method   string `json:"method"`
				Password string `json:"password"`
			}
			json.Unmarshal(raw, &ss)
			server.Address = ss.Server
			server.Port = ss.Port
			server.Method = ss.Method
			server.Password = ss.Password

		case "hysteria2":
			var hy2 struct {
				Server   string `json:"server"`
				Port     uint16 `json:"server_port"`
				Password string `json:"password"`
			}
			json.Unmarshal(raw, &hy2)
			server.Address = hy2.Server
			server.Port = hy2.Port
			server.Password = hy2.Password

		case "tuic":
			var tuic struct {
				Server   string `json:"server"`
				Port     uint16 `json:"server_port"`
				UUID     string `json:"uuid"`
				Password string `json:"password"`
			}
			json.Unmarshal(raw, &tuic)
			server.Address = tuic.Server
			server.Port = tuic.Port
			server.UUID = tuic.UUID
			server.Password = tuic.Password

		case "wireguard":
			var wg struct {
				Server        string   `json:"server"`
				Port          uint16   `json:"server_port"`
				LocalAddress  []string `json:"local_address"`
				PrivateKey    string   `json:"private_key"`
				PeerPublicKey string   `json:"peer_public_key"`
				PreSharedKey  string   `json:"pre_shared_key,omitempty"`
			}
			json.Unmarshal(raw, &wg)
			server.Address = wg.Server
			server.Port = wg.Port
			server.LocalAddress = wg.LocalAddress
			server.PrivateKey = wg.PrivateKey
			server.PeerPublicKey = wg.PeerPublicKey
			server.PreSharedKey = wg.PreSharedKey
		}

		servers = append(servers, server)
	}

	return &ParseResult{
		Servers: servers,
		URL:     subURL,
		Format:  "sing-box",
	}, nil
}

// parseClashYAML parses Clash/Mihomo YAML configuration
func (p *SubscriptionParser) parseClashYAML(content string, subURL string) (*ParseResult, error) {
	// Simplified YAML parsing - in production use proper YAML library
	// This is a basic implementation
	
	var servers []ServerConfig
	
	// Extract proxies section
	proxiesStart := strings.Index(content, "proxies:")
	if proxiesStart == -1 {
		proxiesStart = strings.Index(content, "Proxy:")
	}
	if proxiesStart == -1 {
		return nil, fmt.Errorf("no proxies section found")
	}

	// Very basic parsing - split by lines and look for proxy definitions
	lines := strings.Split(content[proxiesStart:], "\n")
	
	for _, line := range lines {
		line = strings.TrimSpace(line)
		
		// Look for proxy type indicators
		if strings.HasPrefix(line, "- {") {
			// Inline proxy definition
			// Parse: - {name: "name", type: vmess, server: host, port: 443, ...}
			line = strings.TrimPrefix(line, "- {")
			line = strings.TrimSuffix(line, "}")
			
			// Simple key=value parsing
			pairs := strings.Split(line, ",")
			var proxy ServerConfig
			
			for _, pair := range pairs {
				pair = strings.TrimSpace(pair)
				kv := strings.SplitN(pair, ":", 2)
				if len(kv) != 2 {
					continue
				}
				
				key := strings.Trim(strings.TrimSpace(kv[0]), `"`)
				value := strings.Trim(strings.TrimSpace(kv[1]), `"`)
				
				switch key {
				case "name":
					proxy.Name = value
					proxy.Tag = value
				case "type":
					proxy.Protocol = value
				case "server":
					proxy.Address = value
				case "port":
					port, _ := strconv.Atoi(value)
					proxy.Port = uint16(port)
				case "uuid":
					proxy.UUID = value
				case "password":
					proxy.Password = value
				case "cipher":
					proxy.Method = value
				case "tls":
					proxy.TLS = value == "true"
				case "sni":
					proxy.SNI = value
				case "network":
					proxy.Transport = value
				}
			}
			
			if proxy.Address != "" && proxy.Port > 0 {
				servers = append(servers, proxy)
			}
		}
	}

	if len(servers) == 0 {
		return nil, fmt.Errorf("no valid proxies found in YAML")
	}

	return &ParseResult{
		Servers: servers,
		URL:     subURL,
		Format:  "clash",
	}, nil
}

// MobileSubscriptionParser wraps for gomobile
type MobileSubscriptionParser struct {
	parser *SubscriptionParser
}

// NewMobileSubscriptionParser creates mobile parser
func NewMobileSubscriptionParser() *MobileSubscriptionParser {
	return &MobileSubscriptionParser{
		parser: NewSubscriptionParser(),
	}
}

// ParseSubscription parses subscription and returns JSON result
func (m *MobileSubscriptionParser) ParseSubscription(content string, subURL string) string {
	result, err := m.parser.ParseSubscription(content, subURL)
	if err != nil {
		return fmt.Sprintf(`{"error": "%s"}`, err.Error())
	}
	
	jsonBytes, _ := json.Marshal(result)
	return string(jsonBytes)
}