package dev.mobilehorizon.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
    private static final int REQUEST_SAVE_DIAGNOSTICS = 7314;
    private File pendingDiagnosticsZip;

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
        page.addView(button("Exportar diagnóstico completo (ZIP)",true,v->exportDiagnostics()),
                top(-1,48,8));
        page.addView(text("Inclui memória Android/ART/nativa, histórico de encerramento do Android, GC/heap da JVM, erros fatais e latest.log da instância. Revise antes de compartilhar: logs do jogo podem conter endereços de servidores e mensagens.",10,0xffb9b3d1,false),top(-1,-2,4));
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

    private void exportDiagnostics(){
        if(loading || isFinishing() || isDestroyed()) return;
        status.setText("Preparando pacote de diagnóstico…");
        io.execute(()->{
            File zipFile=new File(getCacheDir(),"mobilehorizon-diagnostics-"+System.currentTimeMillis()+".zip");
            Exception failure=null;
            try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(zipFile))){
                // Capture Android's historical :game process exit reason before copying logs.
                // A force-killed NativeActivity cannot run onDestroy(), so this is the
                // reliable point to persist LOW_MEMORY/CRASH_NATIVE/ANR and its trace.
                GameMemoryGuard.captureHistoricalExitsForDiagnostics(this,getFilesDir());
                File appLogs=new File(getFilesDir(),"logs");
                addDiagnosticDirectory(zip,appLogs,"app-logs/");
                addDiagnosticFile(zip,new File(getFilesDir(),"horizon-memory-pressure.level"),
                        "app-state/horizon-memory-pressure.level",256*1024L);
                File home=GameInstanceManager.getSelectedInstanceHome(this);
                addDiagnosticFile(zip,new File(new File(home,"logs"),"latest.log"),
                        "instance/latest.log",8L*1024L*1024L);
                String device="createdAt="+new Date()+"\n"
                        +"package="+getPackageName()+"\n"
                        +"manufacturer="+Build.MANUFACTURER+"\n"
                        +"model="+Build.MODEL+"\n"
                        +"androidRelease="+Build.VERSION.RELEASE+"\n"
                        +"sdk="+Build.VERSION.SDK_INT+"\n"
                        +"supportedAbis="+java.util.Arrays.toString(Build.SUPPORTED_ABIS)+"\n";
                zip.putNextEntry(new ZipEntry("device-summary.txt"));
                zip.write(device.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }catch(Exception error){failure=error;}
            final Exception error=failure;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed())return;
                if(error!=null){
                    status.setText("Falha ao preparar diagnóstico: "+error.getMessage());
                    return;
                }
                pendingDiagnosticsZip=zipFile;
                Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/zip");
                intent.putExtra(Intent.EXTRA_TITLE,"MobileHorizon-diagnostics.zip");
                try{
                    startActivityForResult(intent,REQUEST_SAVE_DIAGNOSTICS);
                }catch(Exception launchError){
                    status.setText("Não foi possível abrir o seletor de arquivos: "+launchError.getMessage());
                }
            });
        });
    }

    private void addDiagnosticDirectory(ZipOutputStream zip,File directory,String prefix)throws Exception{
        File[] files=directory==null?null:directory.listFiles();
        if(files==null)return;
        java.util.Arrays.sort(files,(a,b)->a.getName().compareToIgnoreCase(b.getName()));
        for(File file:files){
            if(file.isDirectory()){
                if(file.getName().equals("diagnostic-export"))continue;
                addDiagnosticDirectory(zip,file,prefix+file.getName()+"/");
                continue;
            }
            String name=file.getName().toLowerCase(Locale.ROOT);
            boolean diagnostic=name.endsWith(".log")||name.endsWith(".trace")
                    ||name.endsWith(".txt")||name.contains(".log.")
                    ||name.startsWith("java-gc.log.")||name.startsWith("hs_err_pid");
            if(diagnostic)addDiagnosticFile(zip,file,prefix+file.getName(),5L*1024L*1024L);
        }
    }

    private void addDiagnosticFile(ZipOutputStream zip,File file,String entryName,long maxBytes)throws Exception{
        if(file==null||!file.isFile()||file.length()<=0)return;
        zip.putNextEntry(new ZipEntry(entryName));
        try(RandomAccessFile input=new RandomAccessFile(file,"r")){
            long length=input.length();
            long start=Math.max(0L,length-maxBytes);
            if(start>0){
                byte[] header=("[truncated: showing last "+maxBytes+" of "+length+" bytes]\n")
                        .getBytes(StandardCharsets.UTF_8);
                zip.write(header);
            }
            input.seek(start);
            byte[] buffer=new byte[16384];
            long remaining=length-start;
            while(remaining>0){
                int count=input.read(buffer,0,(int)Math.min(buffer.length,remaining));
                if(count<0)break;
                zip.write(buffer,0,count);
                remaining-=count;
            }
        }
        zip.closeEntry();
    }

    @Override
    protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=REQUEST_SAVE_DIAGNOSTICS)return;
        File source=pendingDiagnosticsZip;
        pendingDiagnosticsZip=null;
        Uri destination=data==null?null:data.getData();
        if(resultCode!=RESULT_OK||destination==null){
            status.setText("Exportação cancelada. Os logs continuam salvos no app.");
            return;
        }
        if(source==null||!source.isFile()){
            status.setText("O pacote temporário de diagnóstico não está disponível.");
            return;
        }
        status.setText("Salvando pacote de diagnóstico…");
        io.execute(()->{
            Exception failure=null;
            try(FileInputStream input=new FileInputStream(source);
                OutputStream output=getContentResolver().openOutputStream(destination,"w")){
                if(output==null)throw new java.io.IOException("Não foi possível abrir o destino escolhido.");
                byte[] buffer=new byte[16384];
                int count;
                while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
                output.flush();
            }catch(Exception error){failure=error;}
            final Exception error=failure;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed())return;
                status.setText(error==null
                        ?"Diagnóstico exportado com sucesso. Envie o ZIP para análise."
                        :"Falha ao salvar o diagnóstico: "+error.getMessage());
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
