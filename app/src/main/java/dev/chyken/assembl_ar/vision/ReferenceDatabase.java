package dev.chyken.assembl_ar.vision;

import android.content.res.AssetManager;
import android.util.Log;

import org.opencv.core.KeyPoint;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.features2d.ORB;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Prepared, fully offline component references loaded from configured asset paths or stored
 * administrator descriptor blobs. The owner must close this database when recognition ends.
 */
public final class ReferenceDatabase implements AutoCloseable {
    /**
     * Bump whenever image-backed descriptor preparation changes. Descriptor-cache filenames carry
     * this value so a cache produced by the old full-photo pipeline cannot silently bypass ROI
     * normalization.
     */
    private static final int REFERENCE_PREPARATION_VERSION = 2;
    private static final double ROI_PADDING_RATIO = 0.05;

    private List<PreparedReference> references;
    private Set<String> componentIds;
    private IndividualBank individualBank;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();

    private ReferenceDatabase(List<PreparedReference> references) {
        this.references = Collections.unmodifiableList(new ArrayList<>(references));
        Set<String> ids = new LinkedHashSet<>();
        for (PreparedReference reference : references) {
            ids.add(reference.spec.getComponentId());
        }
        componentIds = Collections.unmodifiableSet(ids);
    }

    /** Standalone diagnostics retain only one small native bank. The complete shared
     * catalog and compiled disk cache remain available; loading occurs on the existing
     * background recognition worker, never the UI or CameraX delivery thread. */
    public static ReferenceDatabase loadIndividualBank(AssetManager assets,List<ComponentReferenceSpec> catalog,
            String component,VisionConfig config,File directory,java.util.function.BooleanSupplier active)throws IOException{
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IllegalStateException("Individual banks must load off the UI thread");
        IndividualBank bank=new IndividualBank(assets,catalog,config,directory,active);
        List<ComponentReferenceSpec> first=bank.next(component);
        ReferenceDatabase database=load(assets,first,config,directory,active);
        database.individualBank=bank;
        Set<String> ids=new LinkedHashSet<>();for(ComponentReferenceSpec spec:catalog)ids.add(spec.getComponentId());
        database.componentIds=Collections.unmodifiableSet(ids);return database;
    }

    boolean isIndividualBank(){return individualBank!=null;}
    /** Called before taking this database's recognition read-lock. */
    boolean prepareIndividualBank(RecognitionRequest request){
        IndividualBank bank=individualBank;
        if(bank==null||request.getExpectation()==null)return false;
        String expected=request.getExpectation().getComponentId();
        if(java.util.Objects.equals(expected,bank.component)&&bank.failures<3)return false;
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IllegalStateException("Individual banks must load off the UI thread");
        List<ComponentReferenceSpec> specs=bank.next(expected);
        ReferenceDatabase loaded;
        try{loaded=load(bank.assets,specs,bank.config,bank.directory,()->!closed.get()&&bank.active.getAsBoolean());}
        catch(IOException failure){throw new IllegalStateException("Could not open component reference bank",failure);}
        lifecycleLock.writeLock().lock();
        try{
            ensureOpen();List<PreparedReference> old=references;references=loaded.references;
            // Transfer native ownership; closing the temporary owner must not release it.
            loaded.references=Collections.emptyList();loaded.close();
            for(PreparedReference ref:old)ref.release();return true;
        }finally{lifecycleLock.writeLock().unlock();if(!loaded.closed.get())loaded.close();}
    }
    void observeIndividualBank(RecognitionResult result){
        IndividualBank bank=individualBank;if(bank==null)return;
        if(result.hasLocatedComponent()&&java.util.Objects.equals(bank.component,result.getComponentId())){bank.failures=0;bank.window.matched(result.getReferenceId());}
        else bank.failures++;
    }
    private static final class IndividualBank {
        final AssetManager assets;final List<ComponentReferenceSpec> catalog;final VisionConfig config;final File directory;final java.util.function.BooleanSupplier active;
        final IndividualReferenceSearchWindow window=new IndividualReferenceSearchWindow();String component;int failures;
        IndividualBank(AssetManager a,List<ComponentReferenceSpec> specs,VisionConfig c,File d,java.util.function.BooleanSupplier active){assets=a;catalog=Collections.unmodifiableList(new ArrayList<>(specs));config=c;directory=d;this.active=active;}
        List<ComponentReferenceSpec> next(String id){component=id;failures=0;Set<String> chosen=window.next(catalog,id);List<ComponentReferenceSpec> selected=new ArrayList<>();for(ComponentReferenceSpec spec:catalog)if(chosen.contains(spec.getReferenceId()))selected.add(spec);return selected;}
    }

    /** OpenCV must be initialized before this method is called. */
    public static ReferenceDatabase load(AssetManager assetManager,
                                         List<ComponentReferenceSpec> specs,
                                         VisionConfig config) throws IOException {
        return load(assetManager, specs, config, null);
    }

    /**
     * Loads the offline catalog and optionally persists versioned ORB blobs in app-private
     * storage. A fresh install needs no user input; later sessions avoid recomputing descriptors.
     */
    public static ReferenceDatabase load(AssetManager assetManager,
                                         List<ComponentReferenceSpec> specs,
                                         VisionConfig config,
                                         File descriptorCacheDirectory) throws IOException {
        return load(assetManager, specs, config, descriptorCacheDirectory, () -> true);
    }

    /** Activity-owned loads stop between photos when the screen is destroyed or superseded. */
    public static ReferenceDatabase load(AssetManager assetManager,
                                         List<ComponentReferenceSpec> specs,
                                         VisionConfig config,
                                         File descriptorCacheDirectory,
                                         java.util.function.BooleanSupplier isActive) throws IOException {
        return load(assetManager,specs,config,descriptorCacheDirectory,isActive,(done,total)->{});
    }
    public static ReferenceDatabase load(AssetManager assetManager,List<ComponentReferenceSpec> specs,VisionConfig config,
            File descriptorCacheDirectory,java.util.function.BooleanSupplier isActive,java.util.function.BiConsumer<Integer,Integer> progress)throws IOException {
        if (assetManager == null) {
            throw new IllegalArgumentException("AssetManager is required");
        }
        if (isActive == null) throw new IllegalArgumentException("Load lifecycle is required");
        if (specs == null || specs.isEmpty()) {
            throw new IllegalArgumentException("At least one component reference is required");
        }
        if (config == null) {
            throw new IllegalArgumentException("VisionConfig is required");
        }

        checkLoadActive(isActive);
        ORB orb = OpenCvVisionUtils.createOrb(config);
        CLAHE clahe = OpenCvVisionUtils.createClahe(config);
        List<PreparedReference> prepared = new ArrayList<>();
        Set<String> referenceIds = new HashSet<>();
        int attempted=0;
        try {
            for (ComponentReferenceSpec spec : specs) {
                checkLoadActive(isActive);
                if (spec == null) {
                    throw new IllegalArgumentException("Reference specs cannot contain null");
                }
                if (!referenceIds.add(spec.getReferenceId())) {
                    throw new IllegalArgumentException(
                            "Duplicate reference id: " + spec.getReferenceId());
                }
                try {
                    PreparedReference ref = prepareReference(
                            assetManager, spec, orb, clahe, config, descriptorCacheDirectory);
                    prepared.add(ref);
                } catch (IOException | RuntimeException perSpecFailure) {
                    String assetPath = spec == null ? "unknown" : spec.getJpegAssetPath();
                    Log.e("ReferenceDatabase", "Skipping reference due to preparation failure: "
                            + spec.getReferenceId() + " (asset=" + assetPath + ")",
                            perSpecFailure);
                    // skip this reference and continue with others
                }
                progress.accept(++attempted,specs.size());
            }
            checkLoadActive(isActive);
            if (prepared.isEmpty()) {
                throw new IOException("No valid references available for offline recognition");
            }
            return new ReferenceDatabase(prepared);
        } catch (IOException | RuntimeException | Error failure) {
            for (PreparedReference reference : prepared) {
                reference.release();
            }
            throw failure;
        } finally {
            clahe.clear();
            orb.clear();
        }
    }

    private static void checkLoadActive(java.util.function.BooleanSupplier isActive)
            throws java.io.InterruptedIOException {
        if (Thread.currentThread().isInterrupted() || !isActive.getAsBoolean())
            throw new java.io.InterruptedIOException("Reference loading cancelled");
    }

    public int size() {
        lifecycleLock.readLock().lock();
        try {
            ensureOpen();
            return references.size();
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    public Set<String> getComponentIds() {
        lifecycleLock.readLock().lock();
        try {
            ensureOpen();
            return componentIds;
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    List<PreparedReference> preparedReferences() {
        lifecycleLock.readLock().lock();
        try {
            ensureOpen();
            return references;
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    boolean containsComponent(String componentId) {
        lifecycleLock.readLock().lock();
        try {
            ensureOpen();
            return componentIds.contains(componentId);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    void acquireForRecognition() {
        lifecycleLock.readLock().lock();
        try {
            ensureOpen();
        } catch (RuntimeException failure) {
            lifecycleLock.readLock().unlock();
            throw failure;
        }
    }

    void releaseAfterRecognition() {
        lifecycleLock.readLock().unlock();
    }

    @Override
    public void close() {
        lifecycleLock.writeLock().lock();
        try {
            if (closed.compareAndSet(false, true)) {
                for (PreparedReference reference : references) {
                    reference.release();
                }
            }
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("ReferenceDatabase is closed");
        }
    }

    private static ComponentReferenceSpec resolveGeometry(ComponentReferenceSpec spec,byte[] imageBytes,File cacheFile)throws IOException{
        if (spec.isAutomaticBoardGeometry()) {
            spec = BoardPhotoGeometry.resolve(spec, imageBytes, cacheFile);
        }
        if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase2AssetCatalog.PREFIX))
            spec=Phase2PhotoGeometry.resolve(spec,imageBytes,cacheFile);
        final ComponentReferenceSpec geometrySource=spec;
        if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase3AssetCatalog.PREFIX))
            spec=PhotoGeometryCache.resolve(geometrySource,cacheFile,3,28,37,()->Phase3PhotoGeometry.resolve(geometrySource,imageBytes));
        if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase8AssetCatalog.PREFIX))
            spec=PhotoGeometryCache.resolve(geometrySource,cacheFile,8,45,20,()->Phase8PhotoGeometry.resolve(geometrySource,imageBytes));
        if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase10AssetCatalog.PREFIX))spec=Phase10PhotoGeometry.resolve(spec,imageBytes,cacheFile);
        if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase9AssetCatalog.PREFIX))spec=Phase9PhotoGeometry.resolve(spec,imageBytes,cacheFile);
        if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase5AssetCatalog.PREFIX))
            spec=PhotoGeometryCache.resolve(geometrySource,cacheFile,5,18,45,()->Phase5PhotoGeometry.resolve(geometrySource,imageBytes));
        if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase7AssetCatalog.PREFIX))spec=Phase7PhotoGeometry.resolve(spec,imageBytes,cacheFile);
        if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase11AssetCatalog.PREFIX))spec=PhotoGeometryCache.resolve(geometrySource,cacheFile,11,spec.getPhysicalWidthMm(),spec.getPhysicalHeightMm(),()->Phase11PhotoGeometry.resolve(geometrySource,imageBytes));
        return spec;
    }
    /** Stream a whole-build preparation pass without retaining a catalog's native matrices. */
    public static void prepareLibrary(AssetManager assets,List<ComponentReferenceSpec> specs,VisionConfig config,File directory,ReferenceCacheIndex index,java.util.function.BooleanSupplier active,java.util.function.BiConsumer<Integer,Integer> progress)throws IOException{
        ORB orb=OpenCvVisionUtils.createOrb(config);CLAHE clahe=OpenCvVisionUtils.createClahe(config);int done=0;
        try{for(ComponentReferenceSpec spec:specs){checkLoadActive(active);if(index.isSkipped(spec,config)){KeypointInventoryManager.INSTANCE.skipped(spec);progress.accept(++done,specs.size());continue;}if(!index.terminal(spec,config)||!GlobalComponentRegistry.INSTANCE.isReady()){
            PreparedReference ready=null;
            try{ready=prepareReference(assets,spec,orb,clahe,config,directory);if(spec.getAdminDescriptorBlob()!=null)index.administrator(spec,config);}
            catch(IOException|RuntimeException unusable){Log.w("ReferenceLibrary","Unusable reference: "+spec.getReferenceId(),unusable);index.skipped(spec,config);KeypointInventoryManager.INSTANCE.skipped(spec);}
            finally{if(ready!=null)ready.release();}
            if(!index.terminal(spec,config))throw new IOException("Compiled reference could not be saved. Check available app storage.");
        }progress.accept(++done,specs.size());}checkLoadActive(active);}finally{orb.clear();clahe.clear();}
    }
    public static String cacheRevision(){return "library1:"+REFERENCE_PREPARATION_VERSION+":"+ReferenceDescriptorPyramid.VERSION+":"+Phase2PhotoGeometry.CACHE_VERSION+":"+Phase3PhotoGeometry.VERSION+":p3manual"+Phase3ReferenceAnnotations.VERSION+":"+Phase5PhotoGeometry.VERSION+":p5local"+Phase5DescriptorSampler.VERSION+":"+Phase7PhotoGeometry.VERSION+":"+Phase8PhotoGeometry.VERSION+":"+Phase9PhotoGeometry.VERSION+":"+Phase10PhotoGeometry.VERSION+":"+Phase11PhotoGeometry.VERSION;}

    /** A geometry edit only invalidates the affected phase, not the entire library. */
    static String cacheRevision(ComponentReferenceSpec spec){
        int phase=spec.getAssemblyStepNumber();
        int geometry=phase==2?Phase2PhotoGeometry.CACHE_VERSION:phase==3?Phase3PhotoGeometry.VERSION:phase==5?Phase5PhotoGeometry.VERSION:phase==7?Phase7PhotoGeometry.VERSION:phase==8?Phase8PhotoGeometry.VERSION:phase==9?Phase9PhotoGeometry.VERSION:phase==10?Phase10PhotoGeometry.VERSION:phase==11?Phase11PhotoGeometry.VERSION:0;
        return "scoped-library1:"+REFERENCE_PREPARATION_VERSION+":"+ReferenceDescriptorPyramid.VERSION+":"+phase+":"+geometry+(Phase5DescriptorSampler.installed(spec)?":p5local"+Phase5DescriptorSampler.VERSION:"")+(Phase3ReferenceAnnotations.forPath(spec.getJpegAssetPath())==null?"":":p3manual"+Phase3ReferenceAnnotations.VERSION)+(dev.chyken.assembl_ar.camera.Phase789VideoReferences.annotation(spec.getJpegAssetPath())==null?"":":video789_"+dev.chyken.assembl_ar.camera.Phase789VideoReferences.DESCRIPTOR_VERSION);
    }

    public static void preloadLibrary(AssetManager assets,List<ComponentReferenceSpec> specs,VisionConfig config,File directory,ReferenceCacheIndex index,java.util.function.BiConsumer<Integer,Integer> progress)throws IOException {
        prepareLibrary(assets,specs,config,directory,index,()->true,progress);
    }
    private static PreparedReference prepareReference(AssetManager assets,ComponentReferenceSpec spec,ORB orb,CLAHE clahe,VisionConfig config,File directory)throws IOException {
        VisionPreloadManager cache=VisionPreloadManager.active(assets);
        ReferenceCacheIndex provenance=ReferenceCacheIndex.active(assets,directory);
        String key=provenance==null?null:provenance.key(spec,config);
        PreparedReference hit=cache==null||key==null?null:cache.acquire(key);
        if(hit!=null)return hit;
        PreparedReference result=prepareReferenceUncached(assets,spec,orb,clahe,config,directory);
        KeypointInventoryManager.INSTANCE.prepared(result);
        GlobalComponentRegistry.INSTANCE.prepared(result);
        if(cache!=null&&key!=null)cache.retain(key,result);
        return result;
    }
    private static PreparedReference prepareReferenceUncached(AssetManager assetManager,
                                                      ComponentReferenceSpec spec,
                                                      ORB orb,
                                                      CLAHE clahe,
                                                      VisionConfig config,
                                                      File descriptorCacheDirectory) throws IOException {
        ComponentReferenceSpec registeredSpec=spec;ReferenceCacheIndex index=ReferenceCacheIndex.active(assetManager,descriptorCacheDirectory);
        if(index!=null){File compiled=index.compiled(spec,config);if(compiled!=null&&compiled.isFile())try(InputStream in=new FileInputStream(compiled)){return preparedFromBlob(resolveGeometry(spec,null,compiled),readFully(in),config);}catch(IOException|RuntimeException corrupt){/* Repair only this source from its retained photo. */}}
        byte[] storedBlob = spec.getAdminDescriptorBlob();
        // A current precompiled blob avoids repeating ORB extraction at startup. Legacy/corrupt
        // administrator blobs deliberately fall through to their retained app-private image.
        // ASOR administrator blobs already store keypoints in complete-source coordinates, so
        // they remain compatible; only image-backed cache filenames need the preparation token.
        if (storedBlob != null) {
            try {
                return preparedFromBlob(spec, storedBlob, config);
            } catch (IllegalArgumentException staleOrCorruptBlob) {
                if (spec.getJpegAssetPath() == null && spec.getLocalImageFilePath() == null) {
                    throw staleOrCorruptBlob;
                }
            }
        }

        byte[] imageBytes = readReferenceImageBytes(assetManager, spec);
        if (imageBytes == null) {
            throw new IllegalArgumentException(
                    "Reference has neither compatible descriptor data nor an image source: "
                            + spec.getReferenceId());
        }
        String sourceLabel = spec.getJpegAssetPath() != null
                ? spec.getJpegAssetPath() : spec.getLocalImageFilePath();
        File cacheFile = cacheFile(descriptorCacheDirectory, spec, imageBytes);
        spec=resolveGeometry(spec,imageBytes,cacheFile);
        if (cacheFile != null && cacheFile.isFile()) {
            try (InputStream input = new FileInputStream(cacheFile)) {
                PreparedReference ready=preparedFromBlob(spec,readFully(input),config);if(index!=null)try{index.remember(registeredSpec,config,cacheFile);}catch(IOException ignored){}return ready;
            } catch (IOException | IllegalArgumentException staleOrCorruptCache) {
                // Rebuild below. The retained image remains the source of truth.
            }
        }
        MatOfByte encoded = new MatOfByte();
        Mat grayscale = null;
        Mat mask = new Mat();
        DescriptorExtractionRegion extractionRegion = null;
        ORB phase7Orb = null;
        List<AdminDescriptorBlobCodec.DescriptorVariant> variants = Collections.emptyList();
        try {
            encoded.fromArray(imageBytes);
            grayscale = Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_GRAYSCALE);
            if (grayscale.empty()) {
                throw new IOException("Unable to decode reference image: " + sourceLabel);
            }
            if (spec.isDescriptorRegionNormalizationEnabled()) {
                mask.release();
                mask = referenceMask(spec, grayscale.cols(), grayscale.rows());
                extractionRegion = canonicalDescriptorRegion(grayscale, mask, spec);
                variants = ReferenceDescriptorPyramid.forReference(spec,
                        extractionRegion.grayscale, extractionRegion.mask, orb, clahe);
                remapKeypointsToSource(variants, extractionRegion.sourceBounds,
                        extractionRegion.grayscale.cols(), extractionRegion.grayscale.rows());
            } else {
                // Preserve the proven board and legacy-reference pipeline. Region normalization is
                // deliberately opt-in because changing the sampling scale changes ORB descriptors
                // even when their remapped geometry is mathematically equivalent.
                int limit = spec.getMaximumImageDimension();
                int largestSide = Math.max(grayscale.cols(), grayscale.rows());
                if (limit > 0 && largestSide > limit) {
                    double scale = limit / (double) largestSide;
                    Mat resized = new Mat();
                    try {
                        Imgproc.resize(grayscale, resized,
                                new Size(Math.round(grayscale.cols() * scale),
                                        Math.round(grayscale.rows() * scale)),
                                0.0, 0.0, Imgproc.INTER_AREA);
                        grayscale.release();
                        grayscale = resized;
                        resized = null;
                    } finally {
                        if (resized != null) resized.release();
                    }
                }
                mask.release();
                mask = referenceMask(spec, grayscale.cols(), grayscale.rows());
                // The small caster plate and sensor trim pots need enough local descriptors
                // alongside board context. Keep all other reference extraction settings intact.
                if(Phase3ReferenceAnnotations.forPath(spec.getJpegAssetPath())!=null
                        ||dev.chyken.assembl_ar.camera.Phase7AssetCatalog.installed(
                        dev.chyken.assembl_ar.camera.Phase7AssetCatalog.referenceRole(spec.getReferenceId()))
                        ||spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase8AssetCatalog.PREFIX)
                        ||spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase10AssetCatalog.PREFIX)||spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase11AssetCatalog.PREFIX))
                    phase7Orb=ORB.create(3200,1.2f,8,31,0,2,ORB.HARRIS_SCORE,31,10);
                dev.chyken.assembl_ar.camera.Phase789VideoReferences.Entry reviewed=dev.chyken.assembl_ar.camera.Phase789VideoReferences.annotation(spec.getJpegAssetPath());
                if(reviewed!=null&&reviewed.phase==7){if(phase7Orb!=null)phase7Orb.clear();phase7Orb=ORB.create(3200,1.2f,8,12,0,2,ORB.HARRIS_SCORE,31,7);}
                variants = reviewed!=null&&(reviewed.phase==7||reviewed.phase==8)
                        ?ReviewedHardwareDescriptorSampler.compute(spec,grayscale,mask,phase7Orb!=null?phase7Orb:orb,clahe)
                        :Phase5DescriptorSampler.installed(spec)
                        ?Phase5DescriptorSampler.compute(spec,grayscale,mask,orb,clahe)
                        :Phase11DescriptorSampler.installed(spec)
                        ?Phase11DescriptorSampler.compute(spec,grayscale,mask,phase7Orb,clahe)
                        :spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase9AssetCatalog.PREFIX)||reviewed!=null&&reviewed.phase==9
                        ?Phase9DescriptorSampler.compute(spec,grayscale,mask,clahe)
                        :ReferenceDescriptorPyramid.forReference(spec,grayscale,mask,phase7Orb!=null?phase7Orb:orb,clahe);
            }
            if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase10AssetCatalog.PREFIX))Phase10DescriptorSampler.isolateProtectorMaterial(spec,grayscale,variants);
            validateTexturedVariants(spec, variants, config, "Reference image");
            if (cacheFile != null && !variants.isEmpty()) {
                try {
                    byte[] cacheBlob = AdminDescriptorBlobCodec.encode(
                            grayscale.cols(), grayscale.rows(), variants, config);
                    writeCacheAtomically(cacheFile, cacheBlob);
                } catch (IOException | RuntimeException cacheFailure) {
                    // Cache failure must never make the packaged offline catalog unavailable.
                }
            }
            PreparedReference ready=new PreparedReference(spec,grayscale.cols(),grayscale.rows(),variants);
            if(index!=null)try{index.remember(registeredSpec,config,cacheFile);}catch(IOException ignored){}
            return ready;
        } finally {
            if(phase7Orb!=null)phase7Orb.clear();
            releaseDescriptorVariants(variants);
            if (extractionRegion != null) {
                extractionRegion.release();
            }
            mask.release();
            if (grayscale != null) {
                grayscale.release();
            }
            encoded.release();
        }
    }

    private static PreparedReference preparedFromBlob(ComponentReferenceSpec spec,
                                                       byte[] blob,
                                                       VisionConfig config) {
        try (AdminDescriptorBlobCodec.DecodedDescriptorBlob decoded =
                     AdminDescriptorBlobCodec.decode(blob, config)) {
            validateTexturedVariants(spec, decoded.variants, config, "Descriptor blob");
            return new PreparedReference(spec, decoded.imageWidth, decoded.imageHeight,
                    decoded.variants);
        }
    }

    private static void validateTexturedVariants(
            ComponentReferenceSpec spec,
            List<AdminDescriptorBlobCodec.DescriptorVariant> variants,
            VisionConfig config,
            String source) {
        if (spec.getShapeHint() != ComponentReferenceSpec.ShapeHint.TEXTURED_PLANAR) {
            return;
        }
        for (AdminDescriptorBlobCodec.DescriptorVariant variant : variants) {
            if (variant.keypoints.length >= config.getMinimumGoodMatches()
                    && !variant.descriptors.empty()) {
                return;
            }
        }
        throw new IllegalArgumentException(source + " cannot satisfy the minimum of "
                + config.getMinimumGoodMatches() + " ORB matches: " + spec.getReferenceId());
    }

    private static byte[] readReferenceImageBytes(AssetManager assetManager,
                                                  ComponentReferenceSpec spec)
            throws IOException {
        String assetPath = spec.getJpegAssetPath();
        if (assetPath != null) {
            try (InputStream input = assetManager.open(assetPath)) {
                return readFully(input);
            }
        }
        String localPath = spec.getLocalImageFilePath();
        if (localPath == null) {
            return null;
        }
        File source = new File(localPath);
        if (!source.isFile()) {
            throw new IOException("Administrator reference image is unavailable: " + localPath);
        }
        try (InputStream input = new FileInputStream(source)) {
            return readFully(input);
        }
    }

    private static void releaseDescriptorVariants(
            List<AdminDescriptorBlobCodec.DescriptorVariant> variants) {
        if (variants == null) {
            return;
        }
        for (AdminDescriptorBlobCodec.DescriptorVariant variant : variants) {
            if (variant != null) {
                variant.release();
            }
        }
    }

    private static byte[] readFully(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static File cacheFile(File directory, ComponentReferenceSpec spec, byte[] assetBytes) {
        if (directory == null) {
            return null;
        }
        if ((!directory.isDirectory() && !directory.mkdirs()) || !directory.canWrite()) {
            return null;
        }
        String safeId = spec.getReferenceId().replaceAll("[^A-Za-z0-9._-]", "_");
        String assetFingerprint = Integer.toUnsignedString(Arrays.hashCode(assetBytes), 16)
                + "_" + assetBytes.length
                + "_" + Integer.toUnsignedString(
                spec.getPlanarCornersNormalized().hashCode(), 16)
                + "_regions" + Integer.toUnsignedString(
                spec.getFeatureRegionsNormalized().hashCode(), 16)
                + "_step" + spec.getAssemblyStepNumber()
                + "_installedBracket" + (spec.isInstalledBracketView() ? 1 : 0)
                + "_size" + spec.getMaximumImageDimension()
                + "_roi" + (spec.isDescriptorRegionNormalizationEnabled() ? 1 : 0)
                + "_raw" + (spec.isAutomaticBoardGeometry() ? 1 : 0)
                + "_bottom" + (spec.isBottomFace() ? 1 : 0)
                + "_prep" + REFERENCE_PREPARATION_VERSION
                + "_sampling" + ReferenceDescriptorPyramid.VERSION;
        if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase2AssetCatalog.PREFIX))
            assetFingerprint+="_p2roi"+Phase2PhotoGeometry.CACHE_VERSION;
        if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase8AssetCatalog.PREFIX))assetFingerprint+="_p8roi"+Phase8PhotoGeometry.VERSION;
        if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase9AssetCatalog.PREFIX))assetFingerprint+="_p9roi"+Phase9PhotoGeometry.VERSION;
        if(Phase11DescriptorSampler.installed(spec))assetFingerprint+="_oledDetail"+Phase11DescriptorSampler.VERSION;
        if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase11AssetCatalog.PREFIX))assetFingerprint+="_p11roi"+Phase11PhotoGeometry.VERSION;
        if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase10AssetCatalog.PREFIX))assetFingerprint+="_p10roi"+Phase10PhotoGeometry.VERSION;
        if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase5AssetCatalog.PREFIX))assetFingerprint+="_p5roi"+Phase5PhotoGeometry.VERSION;
        if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase7AssetCatalog.PREFIX))assetFingerprint+="_p7roi"+Phase7PhotoGeometry.VERSION;
        if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase3AssetCatalog.PREFIX))assetFingerprint+="_p3roi"+Phase3PhotoGeometry.VERSION;
        if(Phase5DescriptorSampler.installed(spec))assetFingerprint+="_p5local"+Phase5DescriptorSampler.VERSION;
        if(Phase3ReferenceAnnotations.forPath(spec.getJpegAssetPath())!=null)assetFingerprint+="_manual"+Phase3ReferenceAnnotations.VERSION;
        if(dev.chyken.assembl_ar.camera.Phase789VideoReferences.annotation(spec.getJpegAssetPath())!=null)assetFingerprint+="_video789_"+dev.chyken.assembl_ar.camera.Phase789VideoReferences.DESCRIPTOR_VERSION;
        return new File(directory, safeId + "_" + assetFingerprint + ".asor");
    }

    /**
     * Crops descriptor extraction to the authored mask and scales that crop to a stable working
     * size. The configured maximum dimension is applied to the component crop, rather than to the
     * surrounding photograph. This preserves substantially more feature detail for small loose
     * parts without allowing high-resolution sources to make ORB preparation unbounded.
     */
    private static DescriptorExtractionRegion canonicalDescriptorRegion(
            Mat sourceGrayscale, Mat sourceMask, ComponentReferenceSpec spec) {
        Mat grayscaleView = null;
        Mat maskView = null;
        Mat canonicalGrayscale = new Mat();
        Mat canonicalMask = new Mat();
        boolean success = false;
        try {
            // boundingRect accepts an 8-bit mask directly. Avoid findNonZero here: materializing
            // every active pixel in a high-resolution board mask can consume hundreds of MB.
            Rect activeBounds = Imgproc.boundingRect(sourceMask);
            if (activeBounds.width <= 0 || activeBounds.height <= 0) {
                throw new IllegalArgumentException(
                        "Reference descriptor mask is empty: " + spec.getReferenceId());
            }
            Rect paddedBounds = paddedBounds(
                    activeBounds, sourceGrayscale.cols(), sourceGrayscale.rows());
            grayscaleView = sourceGrayscale.submat(paddedBounds);
            maskView = sourceMask.submat(paddedBounds);

            int sourceLongestEdge = Math.max(sourceGrayscale.cols(), sourceGrayscale.rows());
            int configuredLimit = spec.getMaximumImageDimension();
            int targetLongestEdge = configuredLimit > 0
                    ? Math.min(configuredLimit, sourceLongestEdge)
                    : sourceLongestEdge;
            double scale = targetLongestEdge
                    / (double) Math.max(paddedBounds.width, paddedBounds.height);
            int targetWidth = Math.max(1, (int) Math.round(paddedBounds.width * scale));
            int targetHeight = Math.max(1, (int) Math.round(paddedBounds.height * scale));

            if (targetWidth == paddedBounds.width && targetHeight == paddedBounds.height) {
                grayscaleView.copyTo(canonicalGrayscale);
                maskView.copyTo(canonicalMask);
            } else {
                int grayscaleInterpolation = scale < 1.0
                        ? Imgproc.INTER_AREA : Imgproc.INTER_CUBIC;
                Imgproc.resize(grayscaleView, canonicalGrayscale,
                        new Size(targetWidth, targetHeight), 0.0, 0.0,
                        grayscaleInterpolation);
                Imgproc.resize(maskView, canonicalMask,
                        new Size(targetWidth, targetHeight), 0.0, 0.0,
                        Imgproc.INTER_NEAREST);
            }
            DescriptorExtractionRegion region = new DescriptorExtractionRegion(
                    canonicalGrayscale, canonicalMask, paddedBounds);
            success = true;
            return region;
        } finally {
            if (maskView != null) maskView.release();
            if (grayscaleView != null) grayscaleView.release();
            if (!success) {
                canonicalMask.release();
                canonicalGrayscale.release();
            }
        }
    }

    private static Rect paddedBounds(Rect active, int imageWidth, int imageHeight) {
        int padding = Math.max(1, (int) Math.ceil(
                Math.max(active.width, active.height) * ROI_PADDING_RATIO));
        int left = Math.max(0, active.x - padding);
        int top = Math.max(0, active.y - padding);
        int right = Math.min(imageWidth, active.x + active.width + padding);
        int bottom = Math.min(imageHeight, active.y + active.height + padding);
        return new Rect(left, top, right - left, bottom - top);
    }

    /**
     * ORB runs in canonical-crop pixels, while homography projection and pose use normalized
     * coordinates from the complete source photograph. Move keypoints back into that original
     * source coordinate system before caching or exposing the prepared reference.
     */
    static void remapKeypointsToSource(
            List<AdminDescriptorBlobCodec.DescriptorVariant> variants,
            Rect sourceBounds, int preparedWidth, int preparedHeight) {
        if (variants == null || sourceBounds == null
                || sourceBounds.width <= 0 || sourceBounds.height <= 0
                || preparedWidth <= 0 || preparedHeight <= 0) {
            throw new IllegalArgumentException("Complete descriptor crop geometry is required");
        }
        double xScale = preparedWidth > 1
                ? (sourceBounds.width - 1.0) / (preparedWidth - 1.0) : 1.0;
        double yScale = preparedHeight > 1
                ? (sourceBounds.height - 1.0) / (preparedHeight - 1.0) : 1.0;
        float sizeScale = (float) Math.sqrt(xScale * yScale);
        for (AdminDescriptorBlobCodec.DescriptorVariant variant : variants) {
            if (variant == null) {
                throw new IllegalArgumentException("Descriptor variants cannot contain null");
            }
            for (KeyPoint keypoint : variant.keypoints) {
                if (keypoint == null) {
                    throw new IllegalArgumentException("Descriptor keypoints cannot contain null");
                }
                keypoint.pt.x = sourceBounds.x + keypoint.pt.x * xScale;
                keypoint.pt.y = sourceBounds.y + keypoint.pt.y * yScale;
                keypoint.size *= sizeScale;
            }
        }
    }

    private static Mat referenceMask(ComponentReferenceSpec spec, int width, int height) {
        List<VisionPoint> normalized = spec.getPlanarCornersNormalized();
        Point[] pixels = new Point[normalized.size()];
        for (int index = 0; index < normalized.size(); index++) {
            VisionPoint point = normalized.get(index);
            pixels[index] = new Point(
                    point.getX() * Math.max(0, width - 1),
                    point.getY() * Math.max(0, height - 1));
        }
        Mat mask = Mat.zeros(height, width, org.opencv.core.CvType.CV_8UC1);
        MatOfPoint polygon = new MatOfPoint(pixels);
        try {
            Imgproc.fillConvexPoly(mask, polygon, new Scalar(255));
            if(dev.chyken.assembl_ar.camera.Phase3AssetCatalog.installed(dev.chyken.assembl_ar.camera.Phase3AssetCatalog.referenceRole(spec.getReferenceId())))mask.setTo(new Scalar(255));
            if(Phase11DescriptorSampler.installed(spec))mask.setTo(new Scalar(255));
            if(dev.chyken.assembl_ar.camera.Phase8AssetCatalog.installed(dev.chyken.assembl_ar.camera.Phase8AssetCatalog.referenceRole(spec.getReferenceId())))mask.setTo(new Scalar(255));
            if(dev.chyken.assembl_ar.camera.Phase5AssetCatalog.installed(dev.chyken.assembl_ar.camera.Phase5AssetCatalog.referenceRole(spec.getReferenceId())))mask.setTo(new Scalar(255));
            if(dev.chyken.assembl_ar.camera.Phase7AssetCatalog.installed(dev.chyken.assembl_ar.camera.Phase7AssetCatalog.referenceRole(spec.getReferenceId()))){mask.setTo(new Scalar(255));return mask;}
            List<List<VisionPoint>> featureRegions = spec.getFeatureRegionsNormalized();
            if (featureRegions.isEmpty()) {
                return mask;
            }

            Mat featureMask = Mat.zeros(
                    height, width, org.opencv.core.CvType.CV_8UC1);
            try {
                for (List<VisionPoint> region : featureRegions) {
                    Point[] regionPixels = new Point[region.size()];
                    for (int index = 0; index < region.size(); index++) {
                        VisionPoint point = region.get(index);
                        regionPixels[index] = new Point(
                                point.getX() * Math.max(0, width - 1),
                                point.getY() * Math.max(0, height - 1));
                    }
                    MatOfPoint featurePolygon = new MatOfPoint(regionPixels);
                    try {
                        // Regions may be concave; their union is authored by filling each source
                        // polygon into the same mask before intersecting the physical board quad.
                        Imgproc.fillPoly(featureMask,
                                Collections.singletonList(featurePolygon), new Scalar(255));
                    } finally {
                        featurePolygon.release();
                    }
                }
                Core.bitwise_and(mask, featureMask, mask);
                if(spec.getReferenceId().startsWith(dev.chyken.assembl_ar.camera.Phase3AssetCatalog.PREFIX))Phase3PhotoGeometry.excludeMountingHoles(mask,spec);
                return mask;
            } finally {
                featureMask.release();
            }
        } catch (RuntimeException failure) {
            mask.release();
            throw failure;
        } finally {
            polygon.release();
        }
    }

    private static void writeCacheAtomically(File destination, byte[] data) throws IOException {
        File temporary = new File(destination.getParentFile(), destination.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(data);
            output.getFD().sync();
        }
        if (destination.exists() && !destination.delete()) {
            throw new IOException("Unable to replace stale descriptor cache");
        }
        if (!temporary.renameTo(destination)) {
            throw new IOException("Unable to publish descriptor cache");
        }
    }

    private static final class DescriptorExtractionRegion {
        final Mat grayscale;
        final Mat mask;
        final Rect sourceBounds;
        private boolean released;

        DescriptorExtractionRegion(Mat grayscale, Mat mask, Rect sourceBounds) {
            this.grayscale = grayscale;
            this.mask = mask;
            this.sourceBounds = sourceBounds;
        }

        void release() {
            if (!released) {
                released = true;
                mask.release();
                grayscale.release();
            }
        }
    }

    enum PhotometricVariant {
        NORMALIZED(1, false),
        INVERTED(2, true);

        final int encodedValue;
        private final boolean inverted;

        PhotometricVariant(int encodedValue, boolean inverted) {
            this.encodedValue = encodedValue;
            this.inverted = inverted;
        }

        boolean isInverted() {
            return inverted;
        }

        static PhotometricVariant fromEncodedValue(int encodedValue) {
            for (PhotometricVariant variant : values()) {
                if (variant.encodedValue == encodedValue) {
                    return variant;
                }
            }
            throw new IllegalArgumentException(
                    "Descriptor blob contains an unknown photometric variant");
        }
    }

    static final class PreparedOrbVariant {
        final PhotometricVariant photometricVariant;
        final KeyPoint[] keypoints;
        final Mat descriptors;
        private boolean released;

        PreparedOrbVariant(PhotometricVariant photometricVariant,
                           KeyPoint[] keypoints, Mat descriptors) {
            if (photometricVariant == null || keypoints == null || descriptors == null) {
                throw new IllegalArgumentException("Complete prepared ORB data is required");
            }
            this.photometricVariant = photometricVariant;
            this.keypoints = keypoints.clone();
            this.descriptors = descriptors.clone();
        }

        boolean hasOrbData() {
            return keypoints.length >= 4 && !descriptors.empty();
        }

        void release() {
            if (!released) {
                released = true;
                descriptors.release();
            }
        }
    }

    static final class PreparedReference {
        final ComponentReferenceSpec spec;
        final int imageWidth;
        final int imageHeight;
        final List<PreparedOrbVariant> orbVariants;
        // Compatibility aliases for callers being migrated to explicit photometric alternatives.
        final KeyPoint[] keypoints;
        final Mat descriptors;

        PreparedReference(ComponentReferenceSpec spec, int imageWidth, int imageHeight,
                          List<AdminDescriptorBlobCodec.DescriptorVariant> variants) {
            this.spec = spec;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            List<PreparedOrbVariant> prepared = new ArrayList<>(variants.size());
            boolean preparedSuccessfully = false;
            try {
                for (AdminDescriptorBlobCodec.DescriptorVariant variant : variants) {
                    prepared.add(new PreparedOrbVariant(
                            variant.photometricVariant, variant.keypoints, variant.descriptors));
                }
                preparedSuccessfully = true;
            } finally {
                if (!preparedSuccessfully) {
                    for (PreparedOrbVariant variant : prepared) {
                        variant.release();
                    }
                }
            }
            orbVariants = Collections.unmodifiableList(prepared);
            PreparedOrbVariant primary = null;
            for (PreparedOrbVariant variant : prepared) {
                if (variant.photometricVariant == PhotometricVariant.NORMALIZED) {
                    primary = variant;
                    break;
                }
            }
            if (primary == null && !prepared.isEmpty()) {
                primary = prepared.get(0);
            }
            keypoints = primary == null ? new KeyPoint[0] : primary.keypoints;
            descriptors = primary == null ? null : primary.descriptors;
        }

        PreparedReference copy(){
            return new PreparedReference(this);
        }
        private PreparedReference(PreparedReference source){
            spec=source.spec;imageWidth=source.imageWidth;imageHeight=source.imageHeight;
            List<PreparedOrbVariant> owned=new ArrayList<>();
            try{for(PreparedOrbVariant v:source.orbVariants)owned.add(new PreparedOrbVariant(v.photometricVariant,v.keypoints,v.descriptors));}
            catch(RuntimeException|Error failure){for(PreparedOrbVariant v:owned)v.release();throw failure;}
            orbVariants=Collections.unmodifiableList(owned);PreparedOrbVariant primary=null;
            for(PreparedOrbVariant v:owned)if(v.photometricVariant==PhotometricVariant.NORMALIZED){primary=v;break;}
            if(primary==null&&!owned.isEmpty())primary=owned.get(0);
            keypoints=primary==null?new KeyPoint[0]:primary.keypoints;descriptors=primary==null?null:primary.descriptors;
        }
        long estimatedBytes(){long total=0;for(PreparedOrbVariant v:orbVariants)total+=(long)v.descriptors.total()*v.descriptors.elemSize()+v.keypoints.length*96L;return total;}
        List<PreparedOrbVariant> orbVariants() {
            return orbVariants;
        }

        boolean hasOrbData() {
            for (PreparedOrbVariant variant : orbVariants) {
                if (variant.hasOrbData()) {
                    return true;
                }
            }
            return false;
        }

        void release() {
            for (PreparedOrbVariant variant : orbVariants) {
                variant.release();
            }
        }
    }
}
