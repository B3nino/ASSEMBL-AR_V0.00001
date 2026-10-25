param([string]$Serial='368e278b')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskAdb=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$taskLogs=Join-Path $taskRoot 'app/build/tmp/qa-overhaul'
New-Item -ItemType Directory -Path $taskLogs -Force | Out-Null
$taskCases=@(
 'vision.StartupQualityInstrumentedTest#compactIndexPreservesEveryCountGeometryAndDescriptorWithoutFullBankReload',
 'vision.StartupQualityInstrumentedTest#corruptOrDifferentCatalogCannotPublishPartialGlobalEvidence',
 'vision.StartupQualityInstrumentedTest#previewSkipIsVisibleAccessibleAndCannotLeakIntoNonVideoContent',
 'vision.CameraPauseResumeInstrumentedTest#pauseDropsInflightEvidenceAndReleasesFramesWhileReferencesSurvive',
 'vision.CameraPauseResumeInstrumentedTest#opticalBridgePauseReleasesLatestImageAndCanResumeTracking',
 'vision.ImageOutlineTrackerInstrumentedTest#resetAndCloseReleaseFrameBuffersAcrossRepeatedScans',
 'DynamicOutlinePipelineInstrumentedTest#completionChangesTheTokenButKeepsVisualLocalizationWithoutNewWorkflowEvidence',
 'vision.Phase789VideoReplayInstrumentedTest#darkTrackersRequireBothBodiesAndCasterAndKeepMeasuredOutlines',
 'vision.Phase789VideoReplayInstrumentedTest#sensorRequiresBothEyesAndRejectsRealInwardAndMissingInstallations',
 'vision.Phase789VideoReplayInstrumentedTest#wheelsNeedTwoDistinctInstancesAndInstalledStandWithOutlines',
 'vision.Phase1011VideoReplayInstrumentedTest#individualToolShowsOutlinesBeforeSuccessForEveryNewLoosePart',
 'DynamicOutlineUiInstrumentedTest#welcomeFooterIsVisibleEnabledAndContrastingAtLargeFonts',
 'DynamicOutlineUiInstrumentedTest#bothCamerasHaveAnIndependentFullSizeDynamicOverlay',
 'DynamicOutlineUiInstrumentedTest#phaseOneDeveloperCompletionShowsAnEnabledFooterWithoutCameraEvidence',
 'PhysicalValidationLayoutInstrumentedTest#longInstructionsAndLargeFontsKeepAllControlsReachableByScrolling'
)
$taskFailed=@()
foreach($taskCase in $taskCases){
 Write-Output "Running $taskCase"
 $taskText=(& $taskAdb -s $Serial shell am instrument -w -r -e class dev.chyken.assembl_ar.CandidateCodeReplayInstrumentedTest -e candidateDex /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip -e candidateCases $taskCase dev.chyken.assembl_ar.test/androidx.test.runner.AndroidJUnitRunner) -join "`n"
 $taskText | Set-Content -LiteralPath (Join-Path $taskLogs ($taskCase.Replace('#','-')+'.txt')) -Encoding utf8
 if($taskText -notmatch 'OK \(1 test\)'){$taskFailed+=$taskCase;Write-Output $taskText}else{Write-Output 'PASS'}
}
if($taskFailed.Count){throw ('Failed groups: '+($taskFailed -join ', '))}
Write-Output "All $($taskCases.Count) tablet regression groups passed."
