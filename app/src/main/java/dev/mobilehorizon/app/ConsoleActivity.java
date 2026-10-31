package dev.mobilehorizon.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ConsoleActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable poll=()->refresh();
    private EditText filter;
    private TextView output;
    private TextView status;
    private boolean loading;
    private boolean resumed;
    private String latest="";

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        LauncherButtonStyle.install(this);
getWindow().setStatusBarColor(0xff090911);getWindow().setNavigationBarColor(0xff090911);
        buildUi();refresh();
    }
    @Override protected void onResume(){super.onResume();resumed=true;handler.removeCallbacks(poll);handler.postDelayed(poll,1800);}
    @Override protected void onPause(){resumed=false;handler.removeCallbacks(poll);super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacks(poll);io.shutdown();super.onDestroy();}

    private void buildUi(){
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(14),dp(16),dp(14),dp(16));page.setBackgroundColor(0xff090911);setContentView(page);
        page.addView(text("MOBILEHORIZON  /  SISTEMA",11,0xff9e88ff,true),lp(-1,-2));
        page.addView(text("Console e logs",25,0xfff0ecff,true),top(-1,-2,4));
        page.addView(text("Acompanhe logs do cliente da instância ativa. Atualização automática a cada poucos segundos.",12,0xffb9b3d1,false),top(-1,-2,5));
        page.addView(button("Voltar",false,v->finish()),top(-1,48,10));
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        filter=new EditText(this);filter.setSingleLine(true);filter.setTextColor(0xfff0ecff);filter.setHintTextColor(0xff827b9f);filter.setHint("Filtrar logs…");
        filter.setBackgroundColor(0xff151321);row.addView(filter,new LinearLayout.LayoutParams(0,dp(48),1f));
        Button refresh=button("Atualizar",true,v->refresh());LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(dp(112),dp(48));rp.leftMargin=dp(7);row.addView(refresh,rp);
        page.addView(row,top(-1,-2,8));
        status=text("",11,0xffb9b3d1,false);page.addView(status,top(-1,-2,6));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setBackgroundColor(0xff100f1a);
        output=text("Aguardando logs…",10,0xffe2dcf8,false);output.setTypeface(Typeface.MONOSPACE);output.setTextIsSelectable(true);output.setPadding(dp(10),dp(10),dp(10),dp(10));scroll.addView(output,lp(-1,-2));
        LinearLayout.LayoutParams sp=lp(-1,0);sp.weight=1f;sp.topMargin=dp(7);page.addView(scroll,sp);
        filter.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){render();}public void afterTextChanged(Editable e){}});
    }

    private void refresh(){
        if(loading||isFinishing()||isDestroyed())return;
        loading=true;
        io.execute(()->{
            String text="";String path="";Exception failure=null;
            try{
                File home=GameInstanceManager.getSelectedInstanceHome(this);
                File log=new File(new File(home,"logs"),"latest.log");path=log.getAbsolutePath();
                if(log.isFile()){
                    try(RandomAccessFile in=new RandomAccessFile(log,"r")){
                        long len=in.length();long start=Math.max(0,len-512L*1024L);in.seek(start);
                        byte[] data=new byte[(int)Math.min(512L*1024L,len-start)];in.readFully(data);
                        text=new String(data,StandardCharsets.UTF_8);
                        if(start>0)text="[trecho final do arquivo; tamanho "+len+" bytes]\n"+text;
                    }
                }else text="Ainda não existe logs/latest.log. Inicie o jogo para gerar o arquivo.";
            }catch(Exception e){failure=e;}
            final String result=text,source=path;final Exception error=failure;
            runOnUiThread(()->{
                loading=false;if(isFinishing()||isDestroyed())return;
                if(error!=null){status.setText("Falha ao ler o log: "+error.getMessage());return;}
                latest=result;status.setText(source.isEmpty()?"":source);render();
                if(resumed){handler.removeCallbacks(poll);handler.postDelayed(poll,2200);}
            });
        });
    }

    private void render(){
        if(output==null)return;
        String query=filter==null?"":filter.getText().toString().trim().toLowerCase(Locale.ROOT);
        if(query.isEmpty()){output.setText(limitLines(latest,1200));return;}
        StringBuilder match=new StringBuilder();String[] lines=latest.split("\\r?\\n");
        int found=0;
        for(String line:lines)if(line.toLowerCase(Locale.ROOT).contains(query)){
            match.append(line).append('\n');if(++found>=1200){match.append("[limite de resultados]\n");break;}
        }
        output.setText(match.length()==0?"Nenhuma linha corresponde ao filtro.":match.toString());
    }

    private String limitLines(String data,int max){
        String[] lines=data.split("\\r?\\n");if(lines.length<=max)return data;
        StringBuilder out=new StringBuilder("[exibindo as "+max+" linhas mais recentes]\n");
        for(int i=lines.length-max;i<lines.length;i++)out.append(lines[i]).append('\n');
        return out.toString();
    }
    private Button button(String s,boolean primary,View.OnClickListener action){Button b=new Button(this);b.setAllCaps(false);b.setText(UiLanguage.translate(this,s));b.setTextSize(12);b.setTextColor(Color.WHITE);b.setMinHeight(0);b.setMinimumHeight(0);GradientDrawable bg=new GradientDrawable();bg.setColor(primary?0xff5b3fd6:0xff2b2641);bg.setCornerRadius(dp(5));bg.setStroke(dp(1),primary?0xff8a74ff:0xff3d3560);b.setBackground(bg);b.setOnClickListener(action);return b;}
    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(UiLanguage.translate(this,s));t.setTextSize(size);t.setTextColor(color);t.setTypeface(bold?Typeface.DEFAULT_BOLD:Typeface.DEFAULT);return t;}
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}
    private LinearLayout.LayoutParams top(int w,int h,int m){LinearLayout.LayoutParams p=lp(w,h);p.topMargin=dp(m);return p;}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
