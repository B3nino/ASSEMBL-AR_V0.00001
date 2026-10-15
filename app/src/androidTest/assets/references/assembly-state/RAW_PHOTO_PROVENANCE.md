# Held-out installed-board photographs

The following JPEGs are byte-identical copies of the supplied originals in
`references/new/multiple parts/board + two motors installed/`. They are test APK
assets only, with no cropping, overlays, resampling, or re-encoding. Each visibly
shows the white main board and both installed yellow gear motors from a different
camera angle. They come from the same capture series as the training photo, so
they are held-out views rather than an independent device or lighting study.

| Test JPEG | SHA-256 |
| --- | --- |
| `20260906_124307.jpg` | `ef19ca6e4e9145a893c01e2d35d2735e1de48f7bbbfe73d98a8f87d03f4b467d` |
| `20260906_124312.jpg` | `8d85f29cc2a3a17d69375a6b1e8b21fc10f9f0685dc1ee72c9d57ada4cc78661` |
| `20260906_124316.jpg` | `5d2a3e2eeb76ad268713c0637036baed5cdc1f1da52ede76fe470ced6b905ee7` |

The excluded training capture, `20260906_124302.jpg`, has SHA-256
`6fe93b8d0ec6939007a9c3b7e6b948ce9a8c4d9df8f09fcfd83c1e7c837c4f0d` and is the
production asset `references/assembly-captures/main-board-motors-20260906_124302.jpg`.
A SHA-256 audit of all production reference images found this training photo only;
none of the three test JPEGs appeared in production assets.

`MotorAssemblyInspectorInstrumentedTest.heldOutRawInstalledPhotosFindTheBoardAndBothMotorsAcrossAssemblySteps`
decodes the complete photograph, scales it to a width of 480 pixels while keeping
its aspect ratio, and obtains the board geometry through `recognizeBoard`. It
requires a fresh main-board pose and acceptance of both motor slots in sections
1, 2, 3, 14, and 18. Intrinsics use a documented approximate focal length of
1.2 times the larger image dimension; this test does not establish metric pose
accuracy, hidden fastening integrity, or electrical continuity.

The older `white-board-two-motors-*.jpg` screenshot fixtures are separate synthetic
and failure-path regressions; they are not used as held-out positive photo evidence.
