package dev.chyken.assembl_ar.vision;

import android.content.res.AssetManager;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Content/version provenance for compiled photos. ZIP CRCs avoid rereading every JPEG on reuse. */
public final class ReferenceCacheIndex {
    private static final Map<AssetManager,ReferenceCacheIndex> ACTIVE=Collections.synchronizedMap(new WeakHashMap<>());
    private final File directory,file;private final Properties entries=new Properties();private final Map<String,String> stamps=new HashMap<>();private final String algorithm;
    public ReferenceCacheIndex(File directory,String apk,String algorithm)throws IOException{
        this.directory=directory;this.algorithm=algorithm;if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Reference cache is unavailable");file=new File(directory,"compiled-index-v1.properties");
        if(file.isFile())try(InputStream in=new FileInputStream(file)){entries.load(in);}catch(IOException|IllegalArgumentException corrupt){entries.clear();}
        if(apk!=null)try(ZipFile zip=new ZipFile(apk)){Enumeration<? extends ZipEntry> all=zip.entries();while(all.hasMoreElements()){ZipEntry e=all.nextElement();if(e.getName().startsWith("assets/"))stamps.put(e.getName().substring(7),e.getCrc()+":"+e.getSize());}}
    }
    public static void install(AssetManager assets,ReferenceCacheIndex index){ACTIVE.put(assets,index);}
    static ReferenceCacheIndex active(AssetManager assets,File directory){ReferenceCacheIndex index=ACTIVE.get(assets);return index!=null&&directory!=null&&index.directory.getAbsoluteFile().equals(directory.getAbsoluteFile())?index:null;}
    private String key(ComponentReferenceSpec s,VisionConfig config){String path=s.getJpegAssetPath(),stamp=stamps.get(path);
        if(path!=null&&stamp==null)stamp="missing";
        if(path==null){String local=s.getLocalImageFilePath();if(local!=null){File f=new File(local);stamp=f.length()+":"+f.lastModified();path=local;}else{byte[] blob=s.getAdminDescriptorBlob();stamp="blob:"+Arrays.hashCode(blob);path="admin";}}
        String text=algorithm+":"+AdminDescriptorBlobCodec.configFingerprint(config)+":"+path+":"+stamp+":"+s.getReferenceId()+":"+s.getComponentId()+":"+s.getPlanarCornersNormalized()+":"+s.getFeatureRegionsNormalized()+":"+s.getPhysicalWidthMm()+":"+s.getPhysicalHeightMm()+":"+s.getShapeHint()+":"+s.getMinimumCircleRadiusRatio()+":"+s.getMaximumCircleRadiusRatio()+":"+s.hasVerifiedPolarityAnchors()+":"+Arrays.hashCode(s.getAdminDescriptorBlob())+":"+s.getAssemblyStepNumber()+":"+s.getMaximumImageDimension()+":"+s.isDescriptorRegionNormalizationEnabled()+":"+s.isAutomaticBoardGeometry()+":"+s.isBottomFace()+":"+s.isInstalledBracketView();
        if(Phase11DescriptorSampler.installed(s))text+=":oledDetail:"+Phase11DescriptorSampler.VERSION;
        try{byte[] hash=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder result=new StringBuilder();for(byte b:hash){result.append("0123456789abcdef".charAt((b&255)>>>4));result.append("0123456789abcdef".charAt(b&15));}return result.toString();}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    public synchronized boolean terminal(ComponentReferenceSpec s,VisionConfig config){String key=key(s,config),value=key==null?null:entries.getProperty(key);return value!=null&&(value.equals("admin")||value.equals("skip")||safeFile(value)!=null&&safeFile(value).isFile());}
    synchronized File compiled(ComponentReferenceSpec s,VisionConfig config)throws IOException{String key=key(s,config),value=key==null?null:entries.getProperty(key);if("skip".equals(value))throw new IOException("Reference previously failed quality preparation; replace its source photo to retry");return value==null?null:safeFile(value);}
    public synchronized void remember(ComponentReferenceSpec s,VisionConfig config,File blob)throws IOException{String key=key(s,config);if(key!=null&&blob!=null&&blob.isFile()){entries.setProperty(key,blob.getName());save();}}
    synchronized void skipped(ComponentReferenceSpec s,VisionConfig config)throws IOException{String key=key(s,config);if(key!=null){entries.setProperty(key,"skip");save();}}
    synchronized void administrator(ComponentReferenceSpec s,VisionConfig config)throws IOException{String key=key(s,config);if(key!=null){entries.setProperty(key,"admin");save();}}
    private File safeFile(String name){if(!name.endsWith(".asor")||name.contains("/")||name.contains("\\")||name.contains(".."))return null;return new File(directory,name);}
    private void save()throws IOException{File temp=new File(file.getPath()+".tmp");try(OutputStream out=new FileOutputStream(temp)){entries.store(out,"Compiled reference provenance");}try{Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException fallback){Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}}
}
