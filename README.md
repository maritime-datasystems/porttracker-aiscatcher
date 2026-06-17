# AIS PortTracker

A minimal headless AIS receiver service for Android. This app runs as a background service, receiving AIS messages from SDR devices and forwarding them to configured outputs.

## Features

- **Headless Operation**: Runs as an Android foreground service
- **USB SDR Support**: RTL-SDR, AirSpy, AirSpy HF+
- **Network Sources**: RTL-TCP, SpyServer
- **Multiple Outputs**:
  - UDP (NMEA or JSON)
  - TCP Listener
  - Built-in Web Interface with map
- **Auto-Start**: On boot or USB device connection
- **Simple Settings UI**: Minimal configuration screen

## Requirements

- Android 6.0+ (API 23+)
- USB OTG support (for USB SDR devices)
- AIS-catcher-for-Android project (for native sources)

## Building

This project references native C++ sources from the AIS-catcher-for-Android project.

```bash
# Clone both projects side-by-side
git clone https://github.com/jvde-github/AIS-catcher-for-Android.git
cd AIS-catcher-for-Android
git submodule update --init --recursive
cd ..

# This project should be next to it
# aiscatcher_porttracker/

# Build
cd aiscatcher_porttracker
./gradlew assembleDebug
```

## Remote access (FRP) binary

Remote access tunnels the local web interface to the PortTracker FRP server
using the `frpc` client. That binary is **not committed** to the repo (~13-15 MB
per ABI) and is gitignored. Fetch it before building if you need remote access:

```bash
scripts/fetch-frpc.sh          # defaults to frp 0.61.2 (compatible with the PortTracker frps)
# or pin a version:
scripts/fetch-frpc.sh 0.61.2
```

This downloads `frpc` from the [frp releases](https://github.com/fatedier/frp/releases)
and stages it as `app/src/main/jniLibs/<abi>/libfrpc.so` for arm64-v8a,
armeabi-v7a and x86_64. Android executes it from the app's native library
directory at runtime (see `FrpTunnelManager`).

The app **builds and runs without it** — remote access just won't start
(`FrpTunnelManager` logs "Binary not found in native lib dir" and the rest of
the app is unaffected). Choose a frpc version compatible with your frps; any
frpc >= 0.52 supports the TOML config the app generates.

## Configuration

### Via Settings UI
Open the app to access the settings screen where you can configure:
- SDR device type
- UDP output (host, port, format)
- TCP listener port
- Web interface port  
- Auto-start options

### Via Web Interface
Access `http://<phone-ip>:8080` from any browser when the service is running.

## Architecture

```
┌─────────────────────────────────────────────┐
│              AIS PortTracker                │
├─────────────────────────────────────────────┤
│  SettingsActivity     │  UsbDeviceReceiver  │
│  (Minimal Config UI)  │  (Auto-detect SDR)  │
├───────────────────────┴─────────────────────┤
│           AisReceiverService                │
│     (Foreground Service + Wake Lock)        │
├─────────────────────────────────────────────┤
│            AIScatcherNDK (JNI)              │
│  ┌─────────┐ ┌─────────┐ ┌─────────────┐   │
│  │ RTL-SDR │ │ AirSpy  │ │ AIS Decoder │   │
│  └─────────┘ └─────────┘ └─────────────┘   │
├─────────────────────────────────────────────┤
│               Data Outputs                  │
│  ┌─────┐  ┌──────────┐  ┌───────────────┐  │
│  │ UDP │  │ TCP Port │  │ Web Interface │  │
│  └─────┘  └──────────┘  └───────────────┘  │
└─────────────────────────────────────────────┘
```

## License

Uses native code from AIS-catcher (Copyright 2021-2024 jvde.github@gmail.com). 
See the original project for license details.
