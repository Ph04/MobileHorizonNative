package dev.mobilehorizon.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public final class ScreenshotsActivity extends Activity {
    private static final int EXPORT_SCREENSHOT = 401;
    private static final int EXPORT_ALL_SCREENSHOTS = 402;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout list;
    private TextView status;
    private File pendingExport;
    private File[] pendingExportBatch;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state); getWindow().setStatusBarColor(0xff090911); getWindow().setNavigationBarColor(0xff090911);
        buildUi(); refresh();
    }

    @Override protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(0xff090911);
        LinearLayout page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setPadding(dp(16),dp(18),dp(16),dp(28));
        scroll.addView(page); setContentView(scroll);
        page.addView(text("MOBILEHORIZON  /  CAPTURAS",11,0xff9e88ff,true),lp(-1,-2));
        page.addView(text("Capturas de tela",26,0xfff0ecff,true),top(-1,-2,5));
        page.addView(text("Arquivos locais da pasta screenshots da instância selecionada.",13,0xffb9b3d1,false),top(-1,-2,6));
        page.addView(button("Voltar",false,v->finish()),top(-1,52,12));
        page.addView(button("Atualizar",true,v->refresh()),top(-1,48,8));
        page.addView(button("Exportar todas em ZIP",false,v->exportAll()),top(-1,48,6));
        status=text("",12,0xffc7bce6,false); page.addView(status,top(-1,-2,8));
        list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); page.addView(list,top(-1,-2,5));
    }

    private File[] scan() throws Exception {
        File home=GameInstanceManager.getSelectedInstanceHome(this);
        File folder=new File(home,"screenshots");
        if(!folder.isDirectory()) return new File[0];
        File[] files=folder.listFiles(f->f.isFile() && !java.nio.file.Files.isSymbolicLink(f.toPath())
                && (f.getName().toLowerCase(Locale.ROOT).endsWith(".png")
                || f.getName().toLowerCase(Locale.ROOT).endsWith(".jpg")
                || f.getName().toLowerCase(Locale.ROOT).endsWith(".jpeg")));
        if(files==null)return new File[0];
        Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
        return files;
    }

    private void refresh() {
        list.removeAllViews();
        try {
            File[] files=scan();
            status.setText(UiLanguage.translate(this,"Capturas encontradas: ")+files.length);
            if(files.length==0) {
                list.addView(text("Ainda não há screenshots nesta instância. As capturas salvas pelo jogo aparecerão aqui.",12,0xffb9b3d1,false),top(-1,-2,5));
                return;
            }
            for(File file:files) list.addView(card(file),top(-1,-2,8));
        } catch(Exception error) { status.setText(UiLanguage.translate(this,"Não foi possível ler screenshots: ")+error.getMessage()); }
    }

    private View card(File file) {
        LinearLayout card=cardBackground();
        ImageView preview=new ImageView(this); preview.setAdjustViewBounds(true); preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setBackgroundColor(0xff090911);
        try {
            BitmapFactory.Options options=new BitmapFactory.Options(); options.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(file.getAbsolutePath(),options);
            int sample=1; while(options.outWidth/sample>640 || options.outHeight/sample>420) sample*=2;
            options.inJustDecodeBounds=false; options.inSampleSize=sample;
            Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath(),options);
            if(bitmap!=null) preview.setImageBitmap(bitmap);
        } catch(Exception ignored) {}
        LinearLayout.LayoutParams imageParams=lp(-1,dp(150)); card.addView(preview,imageParams);
        preview.setOnClickListener(v->showPreview(file));
        card.addView(text(file.getName(),13,0xfff0ecff,true),top(-1,-2,7));
        String meta=new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.getDefault()).format(new Date(file.lastModified()))
                +" • "+formatSize(file.length());
        card.addView(text(meta,11,0xffaaa2c6,false),top(-1,-2,3));
        LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(row,top(-1,-2,8));
        row.addView(button("Visualizar",true,v->showPreview(file)),new LinearLayout.LayoutParams(0,dp(48),1f));
        Button export=button("Exportar",false,v->export(file));
        LinearLayout.LayoutParams ep=new LinearLayout.LayoutParams(0,dp(48),1f);ep.leftMargin=dp(6);row.addView(export,ep);
        Button delete=button("Excluir",false,v->confirmDelete(file));
        LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(0,dp(48),1f);dp.leftMargin=dp(6);row.addView(delete,dp);
        return card;
    }

    private void showPreview(File file) {
        try {
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
            BitmapFactory.decodeFile(file.getAbsolutePath(),options);
            int sample=1;while(options.outWidth/sample>1600||options.outHeight/sample>1200)sample*=2;
            options.inJustDecodeBounds=false;options.inSampleSize=sample;
            Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath(),options);
            if(bitmap==null)throw new IllegalStateException("A imagem não pôde ser decodificada.");
            ImageView image=new ImageView(this);image.setImageBitmap(bitmap);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            ScrollView frame=new ScrollView(this);frame.setBackgroundColor(0xff090911);frame.addView(image);
            new AlertDialog.Builder(this).setTitle(file.getName()).setView(frame)
                    .setPositiveButton(UiLanguage.translate(this,"Fechar"),null).show();
        } catch(Exception error) {
            new AlertDialog.Builder(this).setMessage(error.getMessage()==null?error.toString():error.getMessage())
                    .setPositiveButton(UiLanguage.translate(this,"Fechar"),null).show();
        }
    }

    private void export(File file) {
        pendingExport=file;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(file.getName().toLowerCase(Locale.ROOT).endsWith(".png")?"image/png":"image/jpeg");
        intent.putExtra(Intent.EXTRA_TITLE,file.getName());
        startActivityForResult(intent,EXPORT_SCREENSHOT);
    }

    private void exportAll() {
        try {
            File[] files = scan();
            if (files.length == 0) {
                status.setText(UiLanguage.translate(this, "Não há capturas para exportar."));
                return;
            }
            pendingExportBatch = files;
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            intent.putExtra(Intent.EXTRA_TITLE, "mobilehorizon-screenshots.zip");
            startActivityForResult(intent, EXPORT_ALL_SCREENSHOTS);
        } catch (Exception error) {
            status.setText(UiLanguage.translate(this, "Não foi possível preparar a exportação: ")
                    + error.getMessage());
        }
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request != EXPORT_SCREENSHOT && request != EXPORT_ALL_SCREENSHOTS) return;
        if (result != RESULT_OK || data == null || data.getData() == null) {
            pendingExport = null;
            pendingExportBatch = null;
            return;
        }
        Uri uri = data.getData();
        if (request == EXPORT_SCREENSHOT) {
            File source = pendingExport;
            pendingExport = null;
            if (source == null) return;
            io.execute(() -> copyScreenshot(source, uri));
        } else {
            File[] batch = pendingExportBatch;
            pendingExportBatch = null;
            if (batch == null) return;
            io.execute(() -> zipScreenshots(batch, uri));
        }
    }

    private void copyScreenshot(File source, Uri destination) {
        Exception failure = null;
        try {
            File root = new File(GameInstanceManager.getSelectedInstanceHome(this),
                    "screenshots").getCanonicalFile();
            File canonical = source.getCanonicalFile();
            if (!root.equals(canonical.getParentFile())
                    || java.nio.file.Files.isSymbolicLink(source.toPath())) {
                throw new java.io.IOException("Caminho da captura inválido.");
            }
            try (FileInputStream input = new FileInputStream(source);
                 OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new java.io.IOException("Destino de exportação indisponível.");
                byte[] buffer = new byte[32768];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush();
            }
        } catch (Exception error) { failure = error; }
        final Exception error = failure;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            status.setText(UiLanguage.translate(this, error == null
                    ? "Captura exportada: " + source.getName()
                    : "Falha ao exportar captura: " + error.getMessage()));
        });
    }

    private void zipScreenshots(File[] files, Uri destination) {
        Exception failure = null;
        int exported = 0;
        try {
            File root = new File(GameInstanceManager.getSelectedInstanceHome(this),
                    "screenshots").getCanonicalFile();
            try (OutputStream raw = getContentResolver().openOutputStream(destination, "w")) {
                if (raw == null) throw new java.io.IOException("Destino de exportação indisponível.");
                try (ZipOutputStream zip = new ZipOutputStream(raw)) {
                    byte[] buffer = new byte[32768];
                    for (File source : files) {
                        File canonical = source.getCanonicalFile();
                        if (!root.equals(canonical.getParentFile())
                                || java.nio.file.Files.isSymbolicLink(source.toPath())
                                || !source.isFile()) {
                            throw new java.io.IOException("Uma captura saiu da pasta segura de origem.");
                        }
                        String name = source.getName();
                        if (name.isEmpty() || name.contains("/") || name.contains("\\\\")
                                || name.equals(".") || name.equals("..")) {
                            throw new java.io.IOException("Nome de captura inválido no ZIP.");
                        }
                        zip.putNextEntry(new ZipEntry(name));
                        try (FileInputStream input = new FileInputStream(source)) {
                            int count;
                            while ((count = input.read(buffer)) != -1) zip.write(buffer, 0, count);
                        }
                        zip.closeEntry();
                        exported++;
                    }
                    zip.finish();
                }
            }
        } catch (Exception error) { failure = error; }
        final Exception error = failure;
        final int count = exported;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            status.setText(UiLanguage.translate(this, error == null
                    ? "Capturas exportadas: " + count
                    : "Falha ao criar ZIP de capturas: " + error.getMessage()));
        });
    }

    private static final class InputStreamCopy implements AutoCloseable {
        private final java.io.InputStream in; private final OutputStream out;
        InputStreamCopy(java.io.InputStream in,OutputStream out){this.in=in;this.out=out;}
        void transfer()throws Exception{if(out==null)throw new IllegalStateException("Destino de exportação indisponível.");byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.flush();}
        public void close()throws Exception{try{in.close();}finally{if(out!=null)out.close();}}
    }

    private void confirmDelete(File file) {
        new AlertDialog.Builder(this).setTitle(UiLanguage.translate(this,"Excluir captura?"))
                .setMessage(file.getName()).setNegativeButton(UiLanguage.translate(this,"Cancelar"),null)
                .setPositiveButton(UiLanguage.translate(this,"Excluir"),(d,w)->{
                    try { File root=new File(GameInstanceManager.getSelectedInstanceHome(this),"screenshots").getCanonicalFile();
                        File target=file.getCanonicalFile();
                        if(!root.equals(target.getParentFile())||java.nio.file.Files.isSymbolicLink(file.toPath()))
                            throw new java.io.IOException("Caminho da captura inválido.");
                        if(!file.delete())throw new java.io.IOException("Não foi possível excluir a captura.");
                        refresh();
                    } catch(Exception error) { status.setText(UiLanguage.translate(this,"Falha ao excluir: ")+error.getMessage()); }
                }).show();
    }

    private LinearLayout cardBackground(){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(10),dp(10),dp(10),dp(10));GradientDrawable bg=new GradientDrawable();bg.setColor(0xff151321);bg.setCornerRadius(dp(7));bg.setStroke(dp(1),0xff37304f);c.setBackground(bg);return c;}
    private Button button(String s,boolean primary,View.OnClickListener listener){Button b=new Button(this);b.setAllCaps(false);b.setText(UiLanguage.translate(this,s));b.setTextSize(12);b.setTextColor(Color.WHITE);b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);b.setMinHeight(0);b.setMinimumHeight(0);GradientDrawable g=new GradientDrawable();g.setColor(primary?0xff5b3fd6:0xff2b2641);g.setCornerRadius(dp(5));g.setStroke(dp(1),primary?0xff8a74ff:0xff3d3560);b.setBackground(g);b.setOnClickListener(listener);return b;}
    private TextView text(String s,int z,int c,boolean bold){TextView t=new TextView(this);t.setText(UiLanguage.translate(this,s));t.setTextSize(z);t.setTextColor(c);t.setTypeface(bold?android.graphics.Typeface.DEFAULT_BOLD:android.graphics.Typeface.DEFAULT);return t;}
    private String formatSize(long bytes){if(bytes<1024)return bytes+" B";if(bytes<1048576)return String.format(Locale.ROOT,"%.1f KB",bytes/1024d);if(bytes<1073741824)return String.format(Locale.ROOT,"%.1f MB",bytes/1048576d);return String.format(Locale.ROOT,"%.2f GB",bytes/1073741824d);}
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}
    private LinearLayout.LayoutParams top(int w,int h,int m){LinearLayout.LayoutParams p=lp(w,h);p.topMargin=dp(m);return p;}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
