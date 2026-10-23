package dev.chyken.assembl_ar;
import android.os.Bundle;import android.widget.FrameLayout;
public final class DevDiagnosticsActivity extends androidx.appcompat.app.AppCompatActivity {
    @Override protected void onCreate(Bundle b){super.onCreate(b);dev.chyken.assembl_ar.ui.UiUtils.configureStandardWindow(this);FrameLayout root=new FrameLayout(this);root.setId(R.id.diagnosticFragmentContainer);setContentView(root);dev.chyken.assembl_ar.ui.UiUtils.applyStandardInsets(this);if(b==null)getSupportFragmentManager().beginTransaction().replace(root.getId(),new DevDiagnosticsFragment()).commit();}
}
