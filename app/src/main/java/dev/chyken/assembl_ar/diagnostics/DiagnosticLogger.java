package dev.chyken.assembl_ar.diagnostics;

import android.app.Activity;
import android.content.*;
import android.os.Environment;
import androidx.core.content.FileProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import dev.chyken.assembl_ar.vision.KeypointInventoryManager;
import dev.chyken.assembl_ar.workflow.PartTestSampleArchive;

/** One serialized session writer. No filesystem or share operation runs on a camera/UI thread. */
public final class DiagnosticLogger {
    private static volatile DiagnosticLogger instance;
    private final Context context;
    private final ThreadPoolExecutor io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(256),r->new Thread(r,"diagnostic-log"),new ThreadPoolExecutor.AbortPolicy());
    private final String session=new SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.US).format(new Date());
    private volatile boolean enabled;private File csv,overlay;private final java.util.concurrent.atomic.AtomicLong dropped=new java.util.concurrent.atomic.AtomicLong();private long rows,overlayRows;
    private DiagnosticLogger(Context c){context=c.getApplicationContext();enabled=PreferencesManager.logging(c);}
    public static DiagnosticLogger get(Context c){if(instance==null)synchronized(DiagnosticLogger.class){if(instance==null)instance=new DiagnosticLogger(c);}return instance;}
    public void setEnabled(boolean value){enabled=value;}
    public boolean enabled(){return enabled;}
    private File folder()throws IOException{File root=context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);if(root==null)throw new IOException("Documents storage unavailable");File d=new File(root,"diagnostics");if(!d.isDirectory()&&!d.mkdirs())throw new IOException("Cannot create diagnostics folder");return d;}
    private void ensure()throws IOException{if(csv!=null)return;csv=new File(folder(),"session-"+session+".csv");try(Writer w=new OutputStreamWriter(new FileOutputStream(csv),StandardCharsets.UTF_8)){w.write(DiagnosticSample.HEADER+"\n");}}
    public void record(DiagnosticSample s){if(!enabled)return;enqueue(()->{ensure();if(rows++<100000)try(Writer w=new OutputStreamWriter(new FileOutputStream(csv,true),StandardCharsets.UTF_8)){w.write(s.csv()+"\n");}});}
    public void recordOverlay(long time,boolean computed,boolean predicted,double pixels,double millimeters,String motion){
        if(!enabled)return;enqueue(()->{if(overlay==null){overlay=new File(folder(),"overlay-"+session+".csv");try(Writer w=new OutputStreamWriter(new FileOutputStream(overlay),StandardCharsets.UTF_8)){w.write("frame_ns,computed_pose,predicted_hold,error_px,static_offset_mm,motion\n");}}
            if(overlayRows++<200000)try(Writer w=new OutputStreamWriter(new FileOutputStream(overlay,true),StandardCharsets.UTF_8)){w.write(time+","+computed+","+predicted+","+(Double.isFinite(pixels)?pixels:"")+","+(Double.isFinite(millimeters)?millimeters:"")+",\""+motion.replace("\"","\"\"")+"\"\n");}
        });
    }
    /** Bounded frame recording uses the existing replayable .arpart.zip schema. */
    public void recordFrame(PartTestSampleArchive frame){enqueue(()->{File f=new File(folder(),"frame-"+session+"-"+frame.getFrameTimestampNanos()+".arpart.zip");try(OutputStream out=new FileOutputStream(f)){frame.writeTo(out);}});}
    private interface Work{void run()throws Exception;}
    private void enqueue(Work w){try{io.execute(()->{try{w.run();}catch(Exception e){android.util.Log.e("DiagnosticLogger","Diagnostic operation failed",e);}});}catch(RejectedExecutionException full){dropped.incrementAndGet();}}
    public void export(Activity activity,String format,String report){
        try{io.execute(()->{try{
            ensure();String stamp=new SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.US).format(new Date());File out=new File(folder(),"diagnostics-"+stamp+"."+format);
            String summary=BuildStamp.details()+"\nDropped log rows: "+dropped.get()+"\n"+report;
            if(format.equals("zip")){try(ZipOutputStream z=new ZipOutputStream(new FileOutputStream(out))){put(z,"report.txt",summary.getBytes(StandardCharsets.UTF_8));if(overlay!=null){z.putNextEntry(new ZipEntry(overlay.getName()));java.nio.file.Files.copy(overlay.toPath(),z);z.closeEntry();}put(z,"keypoint-inventory.csv",KeypointInventoryManager.INSTANCE.csv().getBytes(StandardCharsets.UTF_8));z.putNextEntry(new ZipEntry(csv.getName()));java.nio.file.Files.copy(csv.toPath(),z);z.closeEntry();File[] frames=folder().listFiles((d,n)->n.startsWith("frame-"+session)&&n.endsWith(".arpart.zip"));if(frames!=null)for(File f:frames){z.putNextEntry(new ZipEntry(f.getName()));java.nio.file.Files.copy(f.toPath(),z);z.closeEntry();}}}
            else if(format.equals("csv"))java.nio.file.Files.copy(csv.toPath(),out.toPath());
            else try(Writer w=new OutputStreamWriter(new FileOutputStream(out),StandardCharsets.UTF_8)){w.write(summary+"\n\nSCAN HISTORY (CSV)\n");try(Reader input=new InputStreamReader(new FileInputStream(csv),StandardCharsets.UTF_8)){char[] buffer=new char[8192];int n;while((n=input.read(buffer))!=-1)w.write(buffer,0,n);}}
            activity.runOnUiThread(()->{if(activity.isFinishing())return;android.net.Uri uri=FileProvider.getUriForFile(activity,activity.getPackageName()+".triallogs",out);Intent send=new Intent(Intent.ACTION_SEND).setType(format.equals("zip")?"application/zip":format.equals("csv")?"text/csv":"text/plain").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);send.setClipData(ClipData.newRawUri("Diagnostic logs",uri));activity.startActivity(Intent.createChooser(send,"Share diagnostic logs"));});
        }catch(Exception e){android.util.Log.e("DiagnosticLogger","Export failed",e);activity.runOnUiThread(()->android.widget.Toast.makeText(activity,"Export failed: "+e.getMessage(),android.widget.Toast.LENGTH_LONG).show());}});}catch(RejectedExecutionException full){android.widget.Toast.makeText(activity,"Log writer busy. Try export again shortly.",android.widget.Toast.LENGTH_LONG).show();}
    }
    private static void put(ZipOutputStream z,String name,byte[] bytes)throws IOException{z.putNextEntry(new ZipEntry(name));z.write(bytes);z.closeEntry();}
    public void chooseExport(Activity a,String report){new androidx.appcompat.app.AlertDialog.Builder(a).setTitle("Download Diagnostic Logs").setItems(new String[]{"Text report (.txt)","Scan data (.csv)","Session + inventory + recorded frames (.zip)"},(d,i)->export(a,i==0?"txt":i==1?"csv":"zip",report)).show();}
}
