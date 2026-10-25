from pathlib import Path
import subprocess
root=Path(__file__).resolve().parents[1]; out=root/'app/build/tmp/diagnostic-host-tests';out.mkdir(parents=True,exist_ok=True)
import os
cache=Path(os.environ.get('GRADLE_USER_HOME',str(Path.home()/'.gradle')))/'caches'
sdk=Path(os.environ.get('ANDROID_HOME',str(Path.home()/'AppData/Local/Android/Sdk')))
android=sorted((sdk/'platforms').glob('*/android.jar'))[-1]
compiled=root/'app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
if not compiled.is_dir():raise SystemExit('Build the debug app first: powershell -File tools/build_tablet_debug.ps1')
jars=list(cache.glob('*/transforms/**/classes.jar'))+list((cache/'modules-2/files-2.1').rglob('*.jar'))+list((root/'app/build/intermediates').glob('**/R.jar'))
jars=[p for p in jars if not p.name.endswith('-sources.jar')]
cp=';'.join(map(str,[compiled,android]+jars))

tests=list((root/'app/src/test/java/dev/chyken/assembl_ar/workflow/phase').glob('*.java'))
tests += [root/'app/src/test/java/dev/chyken/assembl_ar/camera'/name for name in ['RawBoardReferenceCatalogTest.java','VisionReferenceFactoryTest.java']]
tests += [root/'app/src/test/java/dev/chyken/assembl_ar/workflow/TransientFrameStreakGateTest.java']
tests += [root/'app/src/test/java/dev/chyken/assembl_ar/ui/AssemblyVoiceGuideTest.java']
tests += [root/'app/src/test/java/dev/chyken/assembl_ar/ui/DemoClipNarratorTest.java']
tests += [root/'app/src/test/java/dev/chyken/assembl_ar/model/ProjectProgressTest.java']
tests += [root/'app/src/test/java/dev/chyken/assembl_ar/camera/Phase2AssetCatalogTest.java',root/'app/src/test/java/dev/chyken/assembl_ar/vision/Phase2ObservationTest.java']
tests += [root/'app/src/test/java/dev/chyken/assembl_ar/vision/Phase2ReferenceEligibilityTest.java']
tests += [root/'app/src/test/java/dev/chyken/assembl_ar'/name for name in ['camera/Phase3AssetCatalogTest.java','vision/Phase3ObservationTest.java','vision/Phase3ReferenceEligibilityTest.java','ui/SpeechTextTest.java','render/ComponentModelCatalogTest.java','model/MotorWireColorConfigTest.java','NeobotSeedDataTest.java','model/SchematicPlacementTest.java','data/SchematicComponentCatalogTest.java','camera/Phase5AssetCatalogTest.java','vision/Phase5ObservationTest.java','camera/Phase7AssetCatalogTest.java']]
tests += [root/'app/src/test/java/dev/chyken/assembl_ar'/name for name in ['model/CircuitWireRouteTest.java','ui/LogoFormationSpecTest.java','model/DriverWireColorConfigTest.java','ui/StartupRevealGateTest.java','vision/Phase6RecognitionRequestTest.java','vision/Phase7MountGeometryTest.java']]
tests += [root/'app/src/test/java/dev/chyken/assembl_ar/vision/ReferenceRegionCacheTest.java']
tests += [root/'app/src/main/java/dev/chyken/assembl_ar/vision/ReferenceRegionCache.java']
tests += [root/'app/src/test/java/dev/chyken/assembl_ar'/p for p in ['camera/Phase8AssetCatalogTest.java','model/ComponentWiringColorConfigTest.java','vision/Phase8RecognitionTest.java']]
tests += [root/'app/src/test/java/dev/chyken/assembl_ar'/p for p in ['camera/Phase9AssetCatalogTest.java','vision/Phase9RecognitionTest.java']]
tests += [root/'app/src/test/java/dev/chyken/assembl_ar'/p for p in ['camera/ActiveReferenceScopeTest.java','vision/PhotoGeometryCacheTest.java','camera/Phase10AssetCatalogTest.java']]
tests += [root/'app/src/test/java/dev/chyken/assembl_ar'/p for p in ['camera/Phase11AssetCatalogTest.java','learning/NeobotProgramTest.java','vision/ReferenceCacheIndexTest.java','vision/Phase11RecognitionTest.java']]
extra=['vision/PreviewOutlineCoordinatesTest.java','diagnostics/LightingEstimatorTest.java','workflow/PartRecognitionTestSessionTest.java','diagnostics/DiagnosticsFormatTest.java','diagnostics/AcademicBenchmarkRunnerTest.java','vision/PoseSmoothingTest.java','camera/UnifiedReferenceCatalogTest.java','vision/GlobalComponentIdentityTest.java']
extra.append('vision/IndividualReferenceSearchWindowTest.java')
extra.append('ui/LatestUiTaskTest.java')
extra.append('vision/AdaptiveIlluminationPolicyTest.java')
extra.append('camera/Phase12VideoReferencesTest.java')
extra.append('camera/Phase35VideoReferencesTest.java')
extra.append('camera/Phase789VideoReferencesTest.java')
extra.append('camera/Phase1011VideoReferencesTest.java')
extra.append('vision/ReferenceCacheFileNameTest.java')
extra.append('camera/DiagnosticTargetCatalogTest.java')
extra.append('camera/StartupReferencePlanTest.java')
tests += [root/'app/src/test/java/dev/chyken/assembl_ar'/name for name in extra]
args=['-encoding','UTF-8','-proc:none','-classpath',cp,'-d',str(out/'tests')]+list(map(str,tests))
(out/'test-javac.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args))
jdk=Path(os.environ.get('JAVA_HOME','C:/Program Files/Android/Android Studio/jbr'))/'bin'
r=subprocess.run([str(jdk/'javac.exe'),'@'+str(out/'test-javac.args')],text=True,capture_output=True)
print(r.stdout+r.stderr,flush=True)
if r.returncode: raise SystemExit(r.returncode)
classes=['dev.chyken.assembl_ar.workflow.phase.'+p.stem for p in tests if '/phase/' in p.as_posix()]
classes+=['dev.chyken.assembl_ar.camera.RawBoardReferenceCatalogTest','dev.chyken.assembl_ar.camera.VisionReferenceFactoryTest','dev.chyken.assembl_ar.workflow.TransientFrameStreakGateTest']
classes+=['dev.chyken.assembl_ar.ui.AssemblyVoiceGuideTest']
classes+=['dev.chyken.assembl_ar.ui.DemoClipNarratorTest']
classes+=['dev.chyken.assembl_ar.model.ProjectProgressTest']
classes+=['dev.chyken.assembl_ar.camera.Phase2AssetCatalogTest','dev.chyken.assembl_ar.vision.Phase2ObservationTest']
classes+=['dev.chyken.assembl_ar.vision.Phase2ReferenceEligibilityTest']
classes += ['dev.chyken.assembl_ar.'+name for name in ['camera.Phase3AssetCatalogTest','vision.Phase3ObservationTest','vision.Phase3ReferenceEligibilityTest','ui.SpeechTextTest','render.ComponentModelCatalogTest','model.MotorWireColorConfigTest','NeobotSeedDataTest','model.SchematicPlacementTest','data.SchematicComponentCatalogTest','camera.Phase5AssetCatalogTest','vision.Phase5ObservationTest','camera.Phase7AssetCatalogTest']]
classes += ['dev.chyken.assembl_ar.'+name for name in ['model.CircuitWireRouteTest','ui.LogoFormationSpecTest','model.DriverWireColorConfigTest','ui.StartupRevealGateTest','vision.Phase6RecognitionRequestTest','vision.Phase7MountGeometryTest']]
classes += ['dev.chyken.assembl_ar.vision.ReferenceRegionCacheTest','dev.chyken.assembl_ar.camera.Phase8AssetCatalogTest','dev.chyken.assembl_ar.model.ComponentWiringColorConfigTest','dev.chyken.assembl_ar.vision.Phase8RecognitionTest']
classes += ['dev.chyken.assembl_ar.camera.Phase9AssetCatalogTest','dev.chyken.assembl_ar.vision.Phase9RecognitionTest']
classes += ['dev.chyken.assembl_ar.camera.ActiveReferenceScopeTest','dev.chyken.assembl_ar.vision.PhotoGeometryCacheTest','dev.chyken.assembl_ar.camera.Phase10AssetCatalogTest']
classes += ['dev.chyken.assembl_ar.camera.Phase11AssetCatalogTest','dev.chyken.assembl_ar.learning.NeobotProgramTest','dev.chyken.assembl_ar.vision.ReferenceCacheIndexTest','dev.chyken.assembl_ar.vision.Phase11RecognitionTest']
(out/'test-temp').mkdir(exist_ok=True)
classes += ['dev.chyken.assembl_ar.'+name[:-5].replace('/','.') for name in extra]
args=['-Djava.io.tmpdir='+str(out/'test-temp'),'-classpath',str(out/'tests')+';'+cp,'org.junit.runner.JUnitCore']+classes
(out/'test-java.args').write_text('\n'.join('"'+a.replace('\\','/')+'"' for a in args))
r=subprocess.run([str(jdk/'java.exe'),'@'+str(out/'test-java.args')],cwd=root/'app',text=True,capture_output=True)
print(r.stdout+r.stderr); raise SystemExit(r.returncode)
