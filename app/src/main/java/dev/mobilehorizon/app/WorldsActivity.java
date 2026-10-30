package dev.mobilehorizon.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Worlds and local backup screen for the active game instance. */
public final class WorldsActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private LinearLayout list;
    private TextView status;
    private ProgressBar progress;
    private Button refresh;
    private boolean busy;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff090911);
        getWindow().setNavigationBarColor(0xff090911);
        buildUi();
        refreshWorlds();
    }
    @Override protected void onDestroy(){io.shutdown();super.onDestroy();}

    private void buildUi(){
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xff090911);
        LinearLayout page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16),dp(18),dp(16),dp(28));
        scroll.addView(page);
        setContentView(scroll);
        page.addView(text("MOBILEHORIZON  /  BIBLIOTECA",11,0xff9e88ff,true),lp(-1,-2));
        page.addView(text("Mundos",27,0xfff0ecff,true),top(-1,-2,5));
        page.addView(text("Mundos da instância ativa. Crie backups locais e restaure cópias quando necessário.",13,0xffb9b3d1,false),top(-1,-2,6));
        LinearLayout actions=new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(actions,top(-1,-2,12));
        actions.addView(button("Voltar",false,v->finish()),new LinearLayout.LayoutParams(0,dp(40),1f));
        refresh=button("Atualizar lista",true,v->refreshWorlds());
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,dp(40),1f);
        rp.leftMargin=dp(7);
        actions.addView(refresh,rp);
        progress=new ProgressBar(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        page.addView(progress,top(-1,4,9));
        status=text("",12,0xffc7bce6,false);
        page.addView(status,top(-1,-2,8));
        list=new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        page.addView(list,top(-1,-2,5));
        LinearLayout note=card();
        note.addView(text("Armazenamento",14,0xffeee9ff,true),lp(-1,-2));
        note.addView(text("Backups ficam na pasta privada world-backups da instância ativa, fora de saves/ e sem envio para a nuvem.",12,0xffb9b3d1,false),top(-1,-2,5));
        page.addView(note,top(-1,-2,12));
    }

    private void refreshWorlds(){
        if(busy||isFinishing()||isDestroyed())return;
        setBusy(true);
        status.setText(UiLanguage.translate(this,"Lendo mundos da instância ativa…"));
        io.execute(()->{
            List<GameWorldManager.World> worlds=null;
            Exception failure=null;
            try{worlds=GameWorldManager.listWorlds(this);}catch(Exception e){failure=e;}
            final List<GameWorldManager.World> result=worlds;
            final Exception error=failure;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed())return;
                setBusy(false);
                list.removeAllViews();
                if(error!=null){
                    status.setText(UiLanguage.translate(this,"Não foi possível carregar os mundos: ")+message(error));
                    return;
                }
                if(result==null||result.isEmpty()){
                    status.setText(UiLanguage.translate(this,"Nenhum mundo foi encontrado em saves/ desta instância."));
                    return;
                }
                status.setText(UiLanguage.translate(this,"Mundos encontrados: ")+result.size());
                for(GameWorldManager.World world:result)list.addView(worldCard(world),top(-1,-2,8));
            });
        });
    }

    private View worldCard(GameWorldManager.World world){
        LinearLayout card=card();
        card.addView(text(world.displayName,16,0xfff0ecff,true),lp(-1,-2));
        card.addView(text(world.folderName+" • "+formatSize(world.sizeBytes),11,0xffa8a1c6,false),top(-1,-2,4));
        String date=new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.getDefault()).format(new Date(world.lastModified));
        card.addView(text(UiLanguage.translate(this,"Última alteração: ")+date,11,0xffa8a1c6,false),top(-1,-2,3));
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(row,top(-1,-2,9));
        row.addView(button("Criar backup",true,v->createBackup(world)),new LinearLayout.LayoutParams(0,dp(40),1f));
        Button backups=button("Backups",false,v->showBackups(world));
        LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,dp(40),1f);
        bp.leftMargin=dp(7);
        row.addView(backups,bp);
        card.addView(button("Excluir mundo",false,v->confirmDelete(world)),top(-1,36,7));
        return card;
    }

    private void createBackup(GameWorldManager.World world){
        if(busy)return;
        setBusy(true);
        status.setText(UiLanguage.translate(this,"Criando backup de ")+world.displayName+"…");
        io.execute(()->{
            GameWorldManager.Backup backup=null;Exception failure=null;
            try{backup=GameWorldManager.createBackup(this,world.folderName);}catch(Exception e){failure=e;}
            final GameWorldManager.Backup result=backup;final Exception error=failure;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed())return;
                setBusy(false);
                if(error!=null)status.setText(UiLanguage.translate(this,"Falha ao criar backup: ")+message(error));
                else status.setText(UiLanguage.translate(this,"Backup concluído: ")+result.fileName+" • "+formatSize(result.sizeBytes));
            });
        });
    }

    private void showBackups(GameWorldManager.World world){
        if(busy)return;
        setBusy(true);
        status.setText(UiLanguage.translate(this,"Lendo backups…"));
        io.execute(()->{
            List<GameWorldManager.Backup> all=null;Exception failure=null;
            try{all=GameWorldManager.listBackups(this);}catch(Exception e){failure=e;}
            final List<GameWorldManager.Backup> result=all;final Exception error=failure;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed())return;
                setBusy(false);
                if(error!=null){showError("Não foi possível listar backups",error);return;}
                java.util.ArrayList<GameWorldManager.Backup> matches=new java.util.ArrayList<>();
                if(result!=null)for(GameWorldManager.Backup b:result)if(world.folderName.equals(b.worldFolder))matches.add(b);
                if(matches.isEmpty()){status.setText(UiLanguage.translate(this,"Ainda não existem backups deste mundo."));return;}
                String[] labels=new String[matches.size()];
                for(int i=0;i<matches.size();i++){
                    GameWorldManager.Backup b=matches.get(i);
                    labels[i]=new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.getDefault()).format(new Date(b.createdAt))+" • "+formatSize(b.sizeBytes);
                }
                new AlertDialog.Builder(this)
                    .setTitle(UiLanguage.translate(this,"Backups de ")+world.displayName)
                    .setItems(labels,(dialog,which)->backupActions(world,matches.get(which)))
                    .setNegativeButton(UiLanguage.translate(this,"Fechar"),null).show();
            });
        });
    }

    private void backupActions(GameWorldManager.World world,GameWorldManager.Backup backup){
        String[] choices={UiLanguage.translate(this,"Restaurar este backup"),UiLanguage.translate(this,"Excluir este backup")};
        new AlertDialog.Builder(this).setTitle(UiLanguage.translate(this,"Backup de ")+world.displayName)
            .setItems(choices,(dialog,which)->{if(which==0)confirmRestore(backup);else confirmDeleteBackup(backup);})
            .setNegativeButton(UiLanguage.translate(this,"Cancelar"),null).show();
    }

    private void confirmRestore(GameWorldManager.Backup backup){
        new AlertDialog.Builder(this).setTitle(UiLanguage.translate(this,"Restaurar mundo?"))
            .setMessage(UiLanguage.translate(this,"A restauração substituirá o mundo atual. A pasta só será trocada depois da validação de todos os arquivos do ZIP."))
            .setNegativeButton(UiLanguage.translate(this,"Cancelar"),null)
            .setPositiveButton(UiLanguage.translate(this,"Restaurar"),(dialog,which)->
                runIo("Validando e restaurando backup…",()->GameWorldManager.restoreBackup(this,backup.fileName,true),"Backup restaurado. O mundo foi substituído."))
            .show();
    }

    private void confirmDeleteBackup(GameWorldManager.Backup backup){
        new AlertDialog.Builder(this).setTitle(UiLanguage.translate(this,"Excluir backup?"))
            .setMessage(UiLanguage.translate(this,"Esta ação remove apenas o arquivo de backup selecionado."))
            .setNegativeButton(UiLanguage.translate(this,"Cancelar"),null)
            .setPositiveButton(UiLanguage.translate(this,"Excluir"),(dialog,which)->
                runIo("Excluindo backup…",()->GameWorldManager.deleteBackup(this,backup.fileName),"Backup excluído."))
            .show();
    }

    private void confirmDelete(GameWorldManager.World world){
        new AlertDialog.Builder(this).setTitle(UiLanguage.translate(this,"Excluir mundo?"))
            .setMessage(UiLanguage.translate(this,"Isso remove permanentemente os arquivos deste mundo da instância ativa. Crie um backup antes de continuar."))
            .setNegativeButton(UiLanguage.translate(this,"Cancelar"),null)
            .setPositiveButton(UiLanguage.translate(this,"Excluir"),(dialog,which)->
                runIo("Excluindo mundo…",()->GameWorldManager.deleteWorld(this,world.folderName),"Mundo excluído."))
            .show();
    }

    private interface Operation{void run()throws Exception;}
    private void runIo(String label,Operation operation,String success){
        if(busy)return;
        setBusy(true);
        status.setText(UiLanguage.translate(this,label));
        io.execute(()->{
            Exception failure=null;try{operation.run();}catch(Exception e){failure=e;}
            final Exception error=failure;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed())return;
                setBusy(false);
                if(error==null)status.setText(UiLanguage.translate(this,success));
                else status.setText(UiLanguage.translate(this,"Falha na operação: ")+message(error));
                refreshWorlds();
            });
        });
    }

    private void setBusy(boolean value){
        busy=value;
        if(progress!=null)progress.setVisibility(value?View.VISIBLE:View.GONE);
        if(refresh!=null)refresh.setEnabled(!value);
        if(list!=null)for(int i=0;i<list.getChildCount();i++){
            View child=list.getChildAt(i);child.setAlpha(value?0.55f:1f);enableTree(child,!value);
        }
    }
    private void enableTree(View view,boolean enabled){
        view.setEnabled(enabled);
        if(view instanceof android.view.ViewGroup){
            android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)enableTree(group.getChildAt(i),enabled);
        }
    }
    private void showError(String title,Exception error){
        new AlertDialog.Builder(this).setTitle(UiLanguage.translate(this,title)).setMessage(message(error))
            .setPositiveButton(UiLanguage.translate(this,"Fechar"),null).show();
    }
    private String message(Exception error){return error.getMessage()==null?error.toString():error.getMessage();}
    private String formatSize(long bytes){
        if(bytes<1024)return bytes+" B";
        if(bytes<1048576)return new DecimalFormat("0.0").format(bytes/1024d)+" KB";
        if(bytes<1073741824)return new DecimalFormat("0.0").format(bytes/1048576d)+" MB";
        return new DecimalFormat("0.00").format(bytes/1073741824d)+" GB";
    }
    private LinearLayout card(){
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(13),dp(12),dp(13),dp(12));
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xff151321);bg.setCornerRadius(dp(7));bg.setStroke(dp(1),0xff37304f);card.setBackground(bg);return card;
    }
    private Button button(String label,boolean primary,View.OnClickListener click){
        Button b=new Button(this);b.setAllCaps(false);b.setText(UiLanguage.translate(this,label));
        b.setTextSize(12);b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);b.setMinHeight(0);b.setMinimumHeight(0);
        b.setPadding(dp(7),0,dp(7),0);
        android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();
        bg.setColor(primary?0xff5b3fd6:0xff2b2641);bg.setCornerRadius(dp(5));bg.setStroke(dp(1),primary?0xff8a74ff:0xff3d3560);
        b.setBackground(bg);b.setTextColor(android.graphics.Color.WHITE);b.setOnClickListener(click);return b;
    }
    private TextView text(String value,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(UiLanguage.translate(this,value));t.setTextSize(size);t.setTextColor(color);
        t.setTypeface(bold?android.graphics.Typeface.DEFAULT_BOLD:android.graphics.Typeface.DEFAULT);return t;
    }
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}
    private LinearLayout.LayoutParams top(int w,int h,int margin){LinearLayout.LayoutParams p=lp(w,h);p.topMargin=dp(margin);return p;}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
