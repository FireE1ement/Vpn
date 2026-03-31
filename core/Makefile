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