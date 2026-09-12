# WacomMapper

![WacomMapper app icon](app/src/main/res/drawable-nodpi/wacom_mapper_icon.png)

**WacomMapper turns a Wacom One by Wacom CTL-472 USB tablet into a mapped Android stylus.** It reads the tablet's USB HID reports, maps its pen coordinates to the Android display, and forwards stylus events system-wide through Shizuku. It is a Kotlin and Jetpack Compose Android app, physically validated with a Samsung Galaxy Tab S7 in landscape orientation.

> WacomMapper is an independent community project and is not affiliated with or endorsed by Wacom or Samsung.

## Features

- Reads the **Wacom CTL-472** through Android USB Host (USB OTG).
- Parses pen position and pressure and maps the tablet's full active area to the current logical display.
- Sends global stylus events through **Shizuku** and Android's input service, including hover, contact, pressure, and supported stylus buttons.
- Runs the active tablet session in a foreground service so it can continue while another app is in the foreground.
- Shows an optional, non-interactive hover cursor overlay with configurable size and color.
- Includes USB/HID, parser, mapping, and output diagnostics for development and troubleshooting.

## Compatibility

| Component | Support |
| --- | --- |
| Tablet | Wacom One by Wacom CTL-472 (VID `0x056A`, PID `0x037A`) |
| Android | Android 8.0 (API 26) or newer; device must support USB Host/OTG |
| Tested device and orientation | Samsung Galaxy Tab S7, landscape, tablet rotation `0°`, full-tablet mapping |
| Global output | Shizuku-backed Android input injection; Shizuku must be running and authorized |

Other Wacom models, portrait mapping, and other Android devices are **not claimed as validated**. WacomMapper does not require root, but global input injection does require a correctly running Shizuku service and its permission. The hover overlay also needs Android's “Display over other apps” permission; without it, stylus injection can still be used without the overlay.

## Requirements

For normal use:

1. An Android device with USB Host/OTG support.
2. A Wacom CTL-472 connected to the device.
3. Shizuku installed, started, and granted to WacomMapper.
4. “Display over other apps” permission if the hover cursor is desired.

For building from source, install Android Studio with the Android SDK required by this project and a compatible JDK. The project uses the Gradle wrapper; do not replace its configured Gradle version arbitrarily.

## Build and test

From the repository root on Windows:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

On macOS or Linux:

```sh
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

The APK is a local build artifact and is not committed to this repository. No GitHub Release is provided yet.

## First use

1. Install and start Shizuku using one of its supported service-start methods. On a non-rooted device, this commonly uses Wireless debugging/ADB and may need to be started again after a reboot.
2. Install WacomMapper, connect the CTL-472, and grant the requested USB device permission.
3. Grant WacomMapper Shizuku permission. Confirm Shizuku is ready in the app.
4. Optionally grant “Display over other apps” for the hover cursor.
5. In WacomMapper, choose **Start stylus**. The app does not start global injection automatically on launch.
6. Keep the tablet in landscape with tablet rotation `0°` for the validated Tab S7 setup. Use **Stop stylus** or the persistent notification action to stop and release input resources.

Shizuku setup details can vary by Android version and device. Follow the current instructions in the [official Shizuku project](https://github.com/RikkaApps/Shizuku).

## How it works

```text
CTL-472 USB HID
      ↓
Android USB Host reader
      ↓
CTL-472 report parser
      ↓
Stylus state mapper (hover/contact/buttons/pressure)
      ↓
Coordinate mapper (tablet area → logical display)
      ├── Internal diagnostics canvas
      └── Shizuku input backend → Android apps
```

The USB report parser, stylus-state logic, coordinate mapping, and output backend are separate layers. The global backend uses Shizuku; this project does not implement a root daemon, kernel driver, `uinput` device, or `AccessibilityService`.

## Known limitations

- The validated geometry target is landscape on a Galaxy Tab S7 with full-tablet mapping and tablet rotation `0°`; portrait behavior is not a project requirement and is not validated.
- Android System UI may draw above application overlay windows, so the optional hover cursor may not be visible on every system surface.
- Compatibility with drawing apps can depend on how each app handles injected stylus events. The main validated hardware target is the CTL-472.
- This is an independent project, not an official Wacom driver. It does not claim support for every Wacom tablet or Android device.

## Privacy and repository hygiene

Experimental captures, local Android SDK configuration, build outputs, ADB state, and signing material are excluded from version control. Do not commit USB capture logs or machine-specific files unless they have been reviewed and sanitized.

## License

No license has been added yet. Until a license is chosen and included, standard copyright applies; public visibility alone does not grant permission to reuse, modify, or redistribute the source.

## Search terms

WacomMapper, Wacom CTL-472 Android, One by Wacom Android tablet, Android USB HID stylus, global stylus input Android, Shizuku stylus, pressure-sensitive pen Android, Samsung Galaxy Tab S7 drawing tablet, Kotlin Android tablet mapping.

---

## Descripción en español

WacomMapper conecta una **Wacom One by Wacom CTL-472** a Android y transforma sus reportes USB HID en entrada global de lápiz, con coordenadas mapeadas, presión, hover y botones compatibles. Está desarrollada en Kotlin con Jetpack Compose y utiliza Shizuku para enviar eventos de stylus a otras aplicaciones sin root. La configuración validada es una Samsung Galaxy Tab S7 en horizontal, con rotación de tableta `0°` y mapeo de toda la superficie.

Para utilizarla se necesita USB Host/OTG, una CTL-472 conectada y Shizuku iniciado y autorizado. El cursor visual de hover es opcional y requiere permiso de superposición. La app no inicia la inyección global automáticamente. Consulta las secciones anteriores para compilar, instalar y ver las limitaciones conocidas.
