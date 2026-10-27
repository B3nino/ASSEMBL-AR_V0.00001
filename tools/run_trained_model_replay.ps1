param([string]$Serial='368e278b')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskAdb=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$taskLogs=Join-Path $taskRoot 'app/build/tmp/trained-model-native'
New-Item -ItemType Directory -Path $taskLogs -Force | Out-Null
$taskCases=@(
 'guidedAndIndividualRoutesExecuteTheirActualModelWithoutRestart',
 'proposalExtractionKeepsBothPhysicalPartsAndWholeSceneContext',
 'proposalDescriptorsCannotPoisonTheFollowingFullFrameSearch',
 'boardInventoryHardwareAndNanoRecoverInOneContinuousAnalyzer',
 'allNinePackagedModelsLoadAndExecuteOnAndroid',
 'proposalCannotPublishIdentityAndFailureRetriesOriginalSearch',
 'expiryAndMotionChecksNeverReuseAnOldPhysicalLocation',
 'queuedFrameIsReleasedAndCannotPublishAfterPauseOrClose',
 'backgroundResultBelongsToOneRunAndIsConsumedOnlyOnce',
 'missingModelDisablesOnlyAssistanceAndReleasesItsFrame',
 'realDriverBadHintFallsBackToVerifiedPhotoGeometry',
 'fastVerifiedNanoReceivesPriorityQueryAndAutomaticFallback',
 'cameraRecoveryUsesFreshPixelsAndPreservesTheVerifier',
 'expiredColdResultRetriesCurrentPixelsWithoutManualReset',
 'initialPhotoPassDoesNotWaitForTheTrainedWorker',
 'realLoosePhotosCompareProposalAndFullSearchTimings',
 'reversedFacesWrongBoardsAndOneWheelCannotPassFromHints',
 'variedFramesMeasureWarmVerificationWithoutRepeatingOneCachedImage',
 'capture:realCameraRecoveryWorkerKeepsDeliveryAndBoundedMemory'
)
$taskResults=@()
foreach($taskCase in $taskCases){
 Write-Output "Running $taskCase"
 $taskStarted=[System.Diagnostics.Stopwatch]::StartNew()
 $taskEntry=if($taskCase.StartsWith('capture:')){'vision.InstalledCapturePerformanceInstrumentedTest#'+$taskCase.Substring(8)}else{"vision.TrainedModelAssistInstrumentedTest#$taskCase"}
 $taskText=(& $taskAdb -s $Serial shell am instrument -w -r -e class dev.chyken.assembl_ar.CandidateCodeReplayInstrumentedTest -e candidateDex /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip -e candidateCases $taskEntry dev.chyken.assembl_ar.test/androidx.test.runner.AndroidJUnitRunner) -join "`n"
 $taskPassed=$taskText -match 'OK \(1 test\)'
 $taskText | Set-Content -LiteralPath (Join-Path $taskLogs ($taskCase.Replace(':','-')+'.txt')) -Encoding utf8
 $taskResults += [pscustomobject]@{case=$taskCase;passed=$taskPassed;seconds=$taskStarted.Elapsed.TotalSeconds}
 if($taskPassed){Write-Output 'PASS'}else{Write-Output $taskText}
}
$taskResults | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskLogs 'results.json') -Encoding utf8
if($taskResults.Where({!$_.passed}).Count){throw 'Trained-model native regression failed; inspect per-case logs.'}
Write-Output "All $($taskResults.Count) trained-model native groups passed."
