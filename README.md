# ASSEMBL-AR

ASSEMBL-AR is an offline Android application that guides users through assembly and wiring of the **NEOBOT Personal Robot Kit**. It combines manual-derived instructions, live camera checks, augmented-reality guidance, a component library, and learning material to support a step-by-step build on an Android device.

The bundled project contains 128 guided actions across 18 assembly sections. Progress is saved on the device, and a step can require a visual or physical check before the next action is available.

The [video review revisions](../docs/VIDEO_REVIEW_REVISIONS.md) add automatic board advancement, a four-bracket inventory scan and clearer visual mounting instructions.
The [September 27 photo import](../references/processed/individual-20260927/README.md) adds 11 original-photo recognition references and keeps all 46 lossless masters outside the APK. See the [current APK and validation record](../deliverables/individual-20260927/VALIDATION.md) for the tested build and recognition limits.

## Highlights

- **Guided assembly:** Manual-derived, ordered instructions with saved progress, warnings, reference pages, and physical confirmation prompts.
- **Camera-assisted checks:** CameraX and OpenCV identify supported parts and assembly anchors, and evaluate applicable placement, orientation, scale, and polarity conditions.
- **AR guidance:** OpenGL ES overlays provide component outlines, target positions, arrows, pin-routing cues, and visual status feedback.
- **Offline-first:** Manuals, models, reference images, learning content, recognition data, and progress storage are packaged or stored locally. The main app requests camera permission only; it does not declare internet access.
- **Learning and parts reference:** Searchable learning modules and a component library with descriptions, assembly use, pin mappings, manual references, and interactive 3D previews.
- **Local reference registration:** Authorized administrators can capture measured multi-angle component references to improve recognition on that device.
- **Separate parts tester:** The `partsTest` variant installs alongside the main app and provides loose-part diagnostics and exportable recognition samples without assembly-progress gates.

> The app provides guided checks, not a substitute for safe, hands-on inspection. Tightening, hidden connections, electrical continuity, and final mechanical fit still require the user to verify the physical robot.

## Requirements

- Android Studio with Android SDK Platform 37 installed
- JDK 17 for Gradle
- Android device or emulator running Android 10 (API 29) or newer
- A rear camera for live recognition; autofocus is recommended
- OpenGL ES 2.0 support

The app source is written in Java 11. Gradle build configuration uses Kotlin DSL.

## Build and install

Clone the repository, then open it in Android Studio or build with the included Gradle wrapper.

### Main application

```bash
./gradlew assembleDebug
```

On Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

Debug APKs are produced by ABI in `app/build/outputs/apk/debug/`:

- `app-arm64-v8a-debug.apk`
- `app-armeabi-v7a-debug.apk`
- `app-x86-debug.apk`
- `app-x86_64-debug.apk`

There is no universal APK. Install the APK that matches the target device or emulator architecture.

### Standalone parts-test app

```bash
./gradlew assemblePartsTest
```

The generated APKs are in `app/build/outputs/apk/partsTest/`. This variant uses package ID `dev.chyken.assembl_ar.partstest`, so it can remain installed with the primary ASSEMBL-AR app.

## Run the app

1. Install a matching debug APK or deploy from Android Studio.
2. Grant camera permission when prompted.
3. Choose the bundled **NEOBOT Personal Robot Kit** project.
4. Start or resume assembly and follow the active guided action.
5. When a camera check is required, keep the requested part and its board or chassis anchor in view until the app reports a successful alignment. Complete the requested physical check before continuing.

For the most reliable recognition, use a clean, well-lit indoor workspace and keep the whole required part visible. A manual page may be viewed at any time, but it does not bypass a required guided check.

## Verification

Run local unit tests, lint, and Android-test compilation:

```bash
./gradlew testDebugUnitTest lintDebug compileDebugAndroidTestJavaWithJavac
```

Run instrumentation tests on a connected device or running emulator:

```bash
./gradlew connectedDebugAndroidTest
```

## Technology

| Area | Implementation |
| --- | --- |
| App UI and workflow | Java, AndroidX, Material Components, RecyclerView, ConstraintLayout |
| Camera | CameraX with Camera2 |
| Vision | OpenCV feature matching, geometry checks, and shape fallbacks |
| AR rendering | OpenGL ES 2.0 |
| Local storage | SQLite |
| Testing | JUnit 4, AndroidX Test, Espresso |

## Project layout

```text
app/
├── src/main/java/dev/chyken/assembl_ar/
│   ├── camera/       # Camera calibration and reference catalogs
│   ├── data/         # SQLite schema, repository, and bundled NEOBOT data
│   ├── render/       # OpenGL ES renderer and 3D-model support
│   ├── ui/           # Android views, dialogs, and UI utilities
│   ├── vision/       # OpenCV recognition and validation pipeline
│   └── workflow/     # Guided assembly state and progression gates
├── src/main/assets/  # Manuals, reference images, and OBJ/MTL models
├── src/test/         # JVM unit tests
├── src/androidTest/  # Device and emulator instrumentation tests
└── src/partsTest/    # Manifest overlay for the standalone parts-test variant
```

## Privacy and data

Camera frames are processed on-device for assembly guidance. The application does not declare the Android `INTERNET` permission. Progress, administrator credentials, registered reference images, descriptors, and related metadata remain local to the installation.

## License

This project is proprietary and is provided for viewing only. See [LICENSE](LICENSE) for the full terms.
