# T9 Gamepad Mapper

A root-required Android app that maps the physical T9 keypad of devices like the **DuoQin F22 Pro** to a virtual gamepad, enabling analog joystick emulation for apps and emulators that require a physical controller.

> **Built with AI assistance** — This project was developed in collaboration with [Claude](https://claude.ai) (Anthropic). The architecture, C native driver, Kotlin service layer, and debugging process were all carried out through an iterative conversation with the AI. The human provided the device, requirements, testing, and logs; the AI provided the implementation.

---

## The Problem

Phones with physical T9 keypads are great for retro gaming and emulation, but emulators like **Azahar (3DS)** only accept analog input for the Circle Pad — they reject digital D-Pad events. This app solves that by creating a virtual Xbox 360-compatible gamepad that translates physical key presses into analog axis events.

## How It Works

```
Physical T9 key press
        │
        ▼
uinput_helper (root daemon)
  ├── Reads /dev/input/eventX directly from the kernel
  ├── Translates keycodes → gamepad events (BTN_*, ABS_X/Y)
  └── Writes to /dev/uinput → Virtual gamepad device
        │
        ▼
Android sees "T9 Gamepad Mapper" as a physical Xbox 360 controller
        │
        ▼
Emulator / game receives analog input
```

The app also uses an `AccessibilityService` to detect which app is in the foreground and automatically switch profiles.

## Features

- **Virtual gamepad** — Appears as an Xbox 360 controller to any app
- **Analog D-Pad emulation** — Maps physical D-Pad to Circle Pad / Left Stick axes
- **Multiple profiles** — Different key mappings per game or app
- **Automatic profile switching** — Detects foreground app and switches profile
- **Transparent mode** — When no profile is assigned, the keyboard works normally
- **Persistent notification** — Pause or stop the service from the notification shade
- **Boot on startup** — Optional auto-start when the phone boots

## Requirements

- Android 10+ (API 29), tested on **Android 12**
- **Root access** (Magisk or SuperSU) — required to open `/dev/uinput`
- Accessibility Service enabled (the app guides you through this)
- Kernel with `CONFIG_INPUT_UINPUT=y` (verify with `zcat /proc/config.gz | grep UINPUT`)

## Building (No Android Studio Required)

Only the Android SDK Command Line Tools are needed.

### 1. Install SDK components

```bash
sdkmanager "platforms;android-34"
sdkmanager "build-tools;34.0.0"
sdkmanager "ndk;26.1.10909125"
sdkmanager "cmake;3.22.1"
```

### 2. Set environment variables

```bash
export ANDROID_HOME=/path/to/your/sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.1.10909125
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools
```

### 3. Build and install

```bash
# Debug build
./gradlew assembleDebug

# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk
```

## First-Time Setup

1. Install the APK on your device
2. Open **T9 Gamepad Mapper**
3. Enable the **Accessibility Service** when prompted (Settings → Accessibility → T9 Gamepad)
4. Tap the **power button** on the Dashboard — grant root access when asked
5. Go to **Profiles** → Edit the default profile → Map the D-Pad keys as **Analog (Circle Pad)**
6. Open your emulator — the device appears as **"T9 Gamepad Mapper"**

## DuoQin F22 Pro Key Mapping Reference

These are the Linux kernel keycodes used by this device (verified via `getevent`):

| Physical Key | Kernel Keycode | Recommended Mapping |
|---|---|---|
| D-Pad ↑ | 103 | Analog Y- (Circle Pad Up) |
| D-Pad ↓ | 108 | Analog Y+ (Circle Pad Down) |
| D-Pad ← | 105 | Analog X- (Circle Pad Left) |
| D-Pad → | 106 | Analog X+ (Circle Pad Right) |
| OK / Center | 353 | — |
| 1 | 2 | Y button |
| 2 | 3 | X button |
| 3 | 4 | A button |
| 4 | 5 | L button |
| 5 | 6 | Start |
| 6 | 7 | R button |
| 7 | 8 | B button |
| 0 | 11 | Select |
| * | 522 | ZL |
| # | 523 | ZR |
| Call | 169 | Home |
| Back | 158 | Back |
| Menu | 139 | Menu |
| Vol+ | 115 | Volume Up |
| Vol- | 114 | Volume Down |

## Architecture

```
app/src/main/
├── java/com/t9mapper/
│   ├── ui/                    # Jetpack Compose UI (3 screens)
│   │   ├── screens/
│   │   │   ├── DashboardScreen.kt
│   │   │   ├── ProfilesScreen.kt
│   │   │   └── AutomationScreen.kt
│   │   └── MainActivity.kt
│   ├── service/
│   │   ├── GamepadService.kt  # ForegroundService — orchestrates everything
│   │   ├── GamepadNative.kt   # JNI wrapper
│   │   └── BootReceiver.kt    # Auto-start on boot
│   ├── automation/
│   │   └── AppDetectionService.kt  # AccessibilityService
│   └── data/
│       ├── model/Models.kt    # Room entities
│       └── db/AppDatabase.kt  # DAOs
└── cpp/
    ├── uinput_helper.c        # Root daemon: reads keyboard, writes gamepad
    ├── fd_receiver.c          # JNI: TCP connection to helper
    └── gamepad.c              # JNI: sends events via TCP proxy
```

## Known Limitations

- The Accessibility Service occasionally needs to be manually re-enabled after a fresh install. The app attempts to repair this automatically on start.
- Closing the app from the recent apps screen kills the Accessibility Service. Use the notification actions (Pause/Stop) instead.
- Multiple virtual gamepad entries may appear in emulators if the service is restarted repeatedly. The helper cleans up orphaned devices on each start, but a phone reboot will always clear them completely.

## License

MIT — use it, modify it, share it.

## Acknowledgements

Developed by **agopdev** with AI assistance from **Claude (Anthropic)**.  
Tested on a **DuoQin F22 Pro** running Android 12.
