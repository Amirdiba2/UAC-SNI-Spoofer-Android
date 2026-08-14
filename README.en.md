<div align="center">

# UAC SNI Spoofer Android

[فارسی](./README.md) · [English](./README.en.md)

</div>

## Overview

UAC SNI Spoofer is an open-source Android tool for managing secure connections. It uses Android's native VPN/TUN path with the Xray core and provides a focused interface for connecting, managing configurations, and inspecting real network status.

Current version: [![Latest release](https://img.shields.io/github/v/release/Floxu1/UAC-SNI-Spoofer-Android?display_name=tag&sort=semver&label=version)](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/releases/latest)

## Features

- Full-device Android tunnel powered by `VpnService`, Xray, and a native TUN path
- VLESS, VMess, and Trojan profiles with their original transport, security, SNI, Host, Path, ALPN, and fingerprint fields preserved
- **Adaptive Connection:** creates a network fingerprint from transport, carrier, ASN, and provider information, prioritizes compatible routes, learns successful results, and reuses them on later connections
- Carrier-aware primary and fallback Edge pools, plus a Direct Compatibility route that tests the original profile without address substitution, ALPN injection, or FinalMask overrides
- Automatic recovery after a network change or quality drop using the saved winner, a backup route, and cooldowns for repeatedly failing paths
- **Route Speed Test:** exhaustively evaluates the `Edge × DNS × Fragment × MTU` matrix, producing hundreds of independent route candidates for each profile and network
- A multi-stage route competition covering qualification, verification, stability, stress, and an A-B-B-A final, with cold Xray starts, multi-target HTTP, DNS, payload, throughput, latency, jitter, pass-rate, and confidence measurements
- Live ranking, persistent pause/resume, manual stage advancement, and a previous-final list scoped to the current profile and network fingerprint
- Per-profile Champion and Backup routes that are saved for the current network and used directly by later connections
- Independent DoH resolvers and bootstrap addresses for Cloudflare, Google, Quad9, AdGuard, and OpenDNS
- **Config Maker:** Quick Scan and Deep Adaptive Test modes with live Candidate details and early stopping on the first fully healthy result
- Import from text, clipboard, local files, and subscription URLs, with non-destructive subscription merging and duplicate removal
- Three per-app routing modes: tunnel every app, bypass selected apps, or tunnel only selected apps
- Tunnel and local SOCKS proxy modes, with Fragment, FinalMask, MTU, Mux, Keepalive, QUIC, and routing controls
- Live latency, traffic, exit IP/country, connection-health, and technical diagnostics
- Direct VPN connect/disconnect from Android Quick Settings and notification controls

## Requirements

- Android 7.0 or newer
- Standard Android VPN permission
- Other VPN apps must be disconnected while UAC SNI Spoofer is active

## Installation

1. Download the latest APK from [Releases](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/releases).
2. Install and open the app.
3. Select a configuration and tap Connect.
4. Approve Android's VPN request.

## Build from source

JDK 17 and Android SDK 35 are required.

```powershell
git clone https://github.com/Floxu1/UAC-SNI-Spoofer-Android.git
cd UAC-SNI-Spoofer-Android
.\gradlew.bat assembleDebug
```

The debug APK is generated at:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Support

- Telegram channel: [t.me/UacSniSpoofer](https://t.me/UacSniSpoofer)
- Telegram group: [t.me/UacSniSpooferGroup](https://t.me/UacSniSpooferGroup)
- Bug reports: [GitHub Issues](https://github.com/Floxu1/UAC-SNI-Spoofer-Android/issues)

Connection quality depends on the carrier, selected configuration, and current network conditions. No single configuration performs identically on every network.

Third-party dependency notices are available in [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md).

If this project helps you, please give it a star ⭐
