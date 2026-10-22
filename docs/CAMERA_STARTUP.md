# Camera startup repair

## Cause and changes

`AssemblyActivity.initializeVision()` previously opened CameraX only after preparing the entire
reference catalog. The preview-only branch in `startCamera()` was therefore unreachable during
normal loading. Slow descriptor preparation kept the screen black; a preparation failure never
opened the camera at all.

- Open CameraX as soon as permission is available, independently of OpenCV/reference preparation.
- Bind Preview and ImageAnalysis together with the existing shared viewport and latest-frame
  backpressure. Attach the analyzer when recognition is ready without explicitly unbinding or
  replacing the preview. Retain the bound camera through lifecycle pause/resume.
- Prepare reference descriptors and model templates on a dedicated background-priority loader,
  separate from the UI, repository, and live analysis executors.
- Cancel abandoned loads between reference photos and release partially prepared databases when
  an Activity is destroyed, avoiding competition with a newly resumed screen.
- Cache Phase 7 source feature regions beside their content/algorithm-versioned descriptors.
  Warm loads can skip repeated source-image geometry detection. Invalid or unavailable sidecars
  fall back to the original photos. Recognition thresholds and regions are unchanged.

## Files changed for this repair

- `app/src/main/java/dev/chyken/assembl_ar/AssemblyActivity.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/ReferenceDatabase.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/Phase7PhotoGeometry.java`
- `app/src/main/java/dev/chyken/assembl_ar/vision/ReferenceRegionCache.java` (new)
- `app/src/test/java/dev/chyken/assembl_ar/vision/ReferenceRegionCacheTest.java` (new)
- `app/src/androidTest/java/dev/chyken/assembl_ar/CameraStartupInstrumentedTest.java` (new)
- `docs/CAMERA_STARTUP.md` (new)

## Verification

- All 209 main Java sources compile against cached Android/OpenCV/Media3 dependencies.
- 236 host JUnit tests pass, including geometry-cache round-trip, replacement, corruption,
  invalid coordinates, bounded parsing, and unavailable-storage checks.
- The new real-camera instrumentation regression holds the loader blocked while checking that
  PreviewView reaches STREAMING and resumes with the same camera. Its source compiles, but the
  test has not been executed on a device.
- Offline Gradle APK assembly is blocked before compilation by
  `java.io.IOException: Unable to establish loopback connection`. Tablet startup latency and
  the final APK still require verification after that environment issue is resolved.

Camera visibility no longer depends on catalog completion. Scanning still waits for prepared
references; a first installation can take longer than a warm launch. No progress reset, reference
removal, workflow changes, or OpenGL setting changes are part of this repair.

## Later device-debugging update

On 22 October the Gradle socket failure was isolated and bypassed using the JDK's built-in TCP
fallback in the build process only. Both debug and Android-test APKs now build successfully.
`tools/build_tablet_debug.ps1` reproduces that build without changing project or IDE settings.
The diagnostic update and subsequent matcher correction were installed on the tablet. After
repeated USB approval activity failures, the final standard debug APK was approved through the
normal on-device installer. Both targeted native matcher tests passed, including replay of the
actual tablet camera frame; these are matcher checks, not a startup performance benchmark.
See `TABLET_DEBUGGING.md` for the live HUD results and preserved learner progress.
