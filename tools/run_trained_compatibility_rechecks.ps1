param([string]$Serial='368e278b')
$ErrorActionPreference='Stop'
$taskRoot=Split-Path -Parent $PSScriptRoot
$taskAdb=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$taskLogs=Join-Path $taskRoot 'app/build/tmp/trained-model-rechecks'
New-Item -ItemType Directory -Path $taskLogs -Force | Out-Null
$taskCases=@(
 'vision.Phase1011VideoReplayInstrumentedTest#phase11MountedOledRequiresIndependentModuleAndBoardEvidence',
 'vision.BoardRelativeInspectionInstrumentedTest#activeLockedFlowAtNativeSizeKeepsTimeAndMemoryBounded',
 'vision.Phase35VideoReplayInstrumentedTest#nanoHeldOutFrontInstalledReversedAndMissingRequireBodyEvidence',
 'vision.Phase1011VideoReplayInstrumentedTest#phase11MountedOledRequiresIndependentModuleAndBoardEvidence',
 'vision.BoardRelativeInspectionInstrumentedTest#activeLockedFlowAtNativeSizeKeepsTimeAndMemoryBounded'
)
$taskResults=@()
for($taskIndex=0;$taskIndex -lt $taskCases.Count;$taskIndex++){
 $taskCase=$taskCases[$taskIndex];Write-Output "Running $taskCase"
 $taskText=(& $taskAdb -s $Serial shell am instrument -w -r -e class dev.chyken.assembl_ar.CandidateCodeReplayInstrumentedTest -e candidateDex /sdcard/Android/data/dev.chyken.assembl_ar/files/candidate-code.zip -e candidateCases $taskCase dev.chyken.assembl_ar.test/androidx.test.runner.AndroidJUnitRunner) -join "`n"
 $taskPassed=$taskText -match 'OK \(1 test\)'
 $taskText | Set-Content -LiteralPath (Join-Path $taskLogs ("$taskIndex-"+$taskCase.Replace('#','-')+'.txt')) -Encoding utf8
 $taskResults += [pscustomobject]@{case=$taskCase;passed=$taskPassed}
 if($taskPassed){Write-Output 'PASS'}else{Write-Output $taskText}
}
$taskResults | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskLogs 'results.json') -Encoding utf8
if($taskResults.Where({!$_.passed}).Count){throw 'One or more compatibility rechecks failed; retain the individual logs.'}
Write-Output "All $($taskResults.Count) focused compatibility rechecks passed."
