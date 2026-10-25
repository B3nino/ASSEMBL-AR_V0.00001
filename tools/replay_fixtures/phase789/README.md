# Real Phase 7–9 replay pixels

These 43 JPEGs are held-out camera frames, not recognition templates. They are
outside the Android module's production assets and are included only in the
isolated native replay archive by `tools/prepare_candidate_replay.py`.

`tools/phase789_video_manifest.json` records the source video, timestamp,
rotation and SHA256 of each frame. New-room replay timestamps are at least two
seconds from all recognition-template timestamps in the same recording. The
older fixtures retain their original timestamp separation. This is a regression
dataset from the supplied recordings, not an independent accuracy study.

Run `python tools/ingest_phase789_videos.py --verify` to verify every reference
and replay hash without OpenCV or the original videos. The source MP4s are needed
only to regenerate or extend the dataset; deleting the Downloads copies does not
remove packaged application references or these replay fixtures.
