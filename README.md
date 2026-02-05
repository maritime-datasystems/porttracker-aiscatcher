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
