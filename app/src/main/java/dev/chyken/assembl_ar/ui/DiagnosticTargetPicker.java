package dev.chyken.assembl_ar.ui;

import android.app.Activity;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import dev.chyken.assembl_ar.camera.DiagnosticTargetCatalog.Target;
import java.util.*;
import java.util.function.Consumer;

/** Recycled text rows, search and categories; never decodes thumbnails or models. */
public final class DiagnosticTargetPicker {
    private DiagnosticTargetPicker() { }
    public static void show(Activity a,List<Target> targets,Consumer<Target> selected){
        LinearLayout body=new LinearLayout(a);body.setOrientation(LinearLayout.VERTICAL);int pad=UiUtils.dp(a,16);body.setPadding(pad,pad,pad,0);
        EditText search=new EditText(a);search.setSingleLine(true);search.setHint("Search component or phase");body.addView(search);
        Spinner category=new Spinner(a);category.setAdapter(new ArrayAdapter<>(a,android.R.layout.simple_spinner_dropdown_item,new String[]{"All targets","Individual components","Hardware groups","Assembly verification"}));body.addView(category);
        ListView list=new ListView(a);body.addView(list,new LinearLayout.LayoutParams(-1,UiUtils.dp(a,340)));
        ArrayAdapter<Target> rows=new ArrayAdapter<Target>(a,android.R.layout.simple_list_item_2,android.R.id.text1,new ArrayList<>()){
            @Override public View getView(int i,View reuse,ViewGroup parent){View row=super.getView(i,reuse,parent);Target t=getItem(i);((TextView)row.findViewById(android.R.id.text1)).setText(t.title);((TextView)row.findViewById(android.R.id.text2)).setText(t.summary());row.setMinimumHeight(UiUtils.dp(a,64));return row;}
        };
        list.setAdapter(rows);
        Runnable filter=()->{String query=search.getText().toString();int group=category.getSelectedItemPosition();List<Target> shown=new ArrayList<>();for(Target t:targets)if(t.matches(query)&&(group==0||t.category.equals(group==1?"Individual components":group==2?"Hardware groups":"Assembly verification")))shown.add(t);rows.clear();rows.addAll(shown);rows.notifyDataSetChanged();};
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int st,int before,int count){filter.run();}});
        category.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int i,long id){filter.run();}});
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("Choose a library target").setView(body).setNegativeButton("Cancel",null).create();
        list.setOnItemClickListener((parent,view,index,id)->{Target target=rows.getItem(index);dialog.dismiss();selected.accept(target);});filter.run();dialog.show();
    }
}
