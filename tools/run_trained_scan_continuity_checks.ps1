param([string]$Serial='368e278b',[switch]$FocusedFinal)
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskAdb=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$taskLogs=Join-Path $taskRoot 'app/build/tmp/trained-scan-continuity'
New-Item -ItemType Directory -Path $taskLogs -Force | Out-Null
$taskCases=@(
 'vision.Phase12VideoReplayInstrumentedTest#rawInstalledTopLeftRightRequireHardwareAndKeepOutlines',
 'vision.Phase12VideoReplayInstrumentedTest#reviewedBracketPhotosCannotCreateAMissingFourthPart',
 'vision.Phase12VideoReplayInstrumentedTest#rawBoardBracketsAndMotorsUseTheSharedCameraPipeline',
 'vision.Phase35VideoReplayInstrumentedTest#driverHeldOutFrontBackAndMountedOrientationsKeepOutlines',
 'vision.Phase35VideoReplayInstrumentedTest#nanoHeldOutFrontInstalledReversedAndMissingRequireBodyEvidence',
 'vision.Phase35VideoReplayInstrumentedTest#isolatedCropsUseIndividualToolRequestsAndPublishOutlineBeforeResult',
 'vision.NanoDnnInstrumentedTest#trainedBoardModelLoadsOnCpuAndProposesNanoRegions',
 'vision.NanoDnnInstrumentedTest#trainedProposalCannotPassReversedOrMissingNano',
 'vision.NanoDnnInstrumentedTest#trainedTogglePersistsWithoutChangingAssemblyProgress',
 'vision.Phase789VideoReplayInstrumentedTest#darkTrackersRequireBothBodiesAndCasterAndKeepMeasuredOutlines',
 'vision.Phase789VideoReplayInstrumentedTest#sensorRequiresBothEyesAndRejectsRealInwardAndMissingInstallations',
 'vision.Phase789VideoReplayInstrumentedTest#wheelsNeedTwoDistinctInstancesAndInstalledStandWithOutlines',
 'vision.Phase1011VideoReplayInstrumentedTest#phase10ProtectorAssemblyRejectsMissingProtectorAndMissingTopBoard',
 'vision.Phase1011VideoReplayInstrumentedTest#phase11MountedOledRequiresIndependentModuleAndBoardEvidence',
 'vision.Phase10FollowupReplayInstrumentedTest#stackedTopBoardRequiresLowerChassisEvidence',
 'vision.CameraPauseResumeInstrumentedTest#pauseDropsInflightEvidenceAndReleasesFramesWhileReferencesSurvive',
 'vision.HygieneSafetyInstrumentedTest#providerAndPlaneFailuresCloseFramesAndReleaseAdmission',
 'vision.HygieneSafetyInstrumentedTest#closeQueuesNativeCleanupAfterTheInflightReader',
 'vision.HygieneSafetyInstrumentedTest#rapidPauseResumeKeepsOnlyCurrentEpochEvidence',
 'vision.BoardRelativeInspectionInstrumentedTest#activeLockedFlowAtNativeSizeKeepsTimeAndMemoryBounded'
)
if($FocusedFinal){$taskCases=@($taskCases[0],$taskCases[1],$taskCases[2],$taskCases[6],$taskCases[7],$taskCases[8])}
$taskResultsName=if($FocusedFinal){'final-results.json'}else{'results.json'}
$taskResults=@()
foreach($taskCase in $taskCases){
 if(((& $taskAdb -s $Serial get-state 2>&1) -join '').Trim() -ne 'device'){throw 'Tablet disconnected; retain completed results and resume after reconnection.'}
 Write-Output "Running $taskCase"
 $taskStarted=[System.Diagnostics.Stopwatch]::StartNew()
 $taskText=(& $taskAdb -s $Serial shell am instrument -w -r -e class dev.chyken.assembl_ar.CandidateCodeReplayInstrumentedTest -e candidateDex /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip -e candidateCases $taskCase dev.chyken.assembl_ar.test/androidx.test.runner.AndroidJUnitRunner) -join "`n"
 $taskPassed=$taskText -match 'OK \(1 test\)'
 $taskText | Set-Content -LiteralPath (Join-Path $taskLogs ($taskCase.Replace('#','-')+'.txt')) -Encoding utf8
 $taskResults += [pscustomobject]@{case=$taskCase;passed=$taskPassed;seconds=$taskStarted.Elapsed.TotalSeconds}
 $taskResults | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskLogs $taskResultsName) -Encoding utf8
 if($taskPassed){Write-Output 'PASS'}else{Write-Output $taskText}
}
if($taskResults.Where({!$_.passed}).Count){throw 'Continuity regression failed; inspect individual logs.'}
Write-Output "All $($taskResults.Count) scan continuity checks passed."
