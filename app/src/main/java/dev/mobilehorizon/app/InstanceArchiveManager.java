package dev.mobilehorizon.app;

import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Safe portable instance archives; credentials, sessions, logs and screenshots are excluded. */
final class InstanceArchiveManager {
    private static final long MAX_TOTAL=2L*1024L*1024L*1024L,MAX_FILE=512L*1024L*1024L;
    private static final int MAX_ENTRIES=20000;
    private static final String MANIFEST="mobilehorizon-instance.json";
    private static final String[] ROOTS={"saves","resourcepacks","shaderpacks","config",
            "horizon-mods","options.txt","servers.dat","servers.dat_old",
            "horizon-graphics.txt","optionsshaders.txt"};
    private InstanceArchiveManager(){}

    static GameInstanceManager.Instance copyInstance(Context c,String sourceId,String name)throws IOException{
        File source=GameInstanceManager.getInstanceHome(c,sourceId);
        GameInstanceManager.Instance target=GameInstanceManager.createInstance(c,name);
        long[] totals={0L,0L};
        try{
            for(String root:ROOTS){
                File item=new File(source,root);
                if(item.exists())copyTree(item,new File(target.gameHome,root),source,totals);
            }
            return target;
        }catch(Exception error){
            try{GameInstanceManager.deleteInstance(c,target.id);}catch(Exception ignored){}
            if(error instanceof IOException)throw(IOException)error;
            throw new IOException("Não foi possível duplicar a instância.",error);
        }
    }

    static void exportZip(Context c,String id,OutputStream destination)throws IOException{
        if(destination==null)throw new IOException("Destino de exportação indisponível.");
        File home=GameInstanceManager.getInstanceHome(c,id).getCanonicalFile();
        try(ZipOutputStream zip=new ZipOutputStream(destination)){
            JSONObject manifest=new JSONObject();
            try{
                manifest.put("format",1);
                manifest.put("instance_name",instanceName(c,id));
                InstanceMetadataStore.Metadata m=InstanceMetadataStore.get(c,id);
                manifest.put("group",m.group);manifest.put("icon",m.icon);manifest.put("color",m.color);
                byte[] manifestBytes = (manifest.toString(2)+"\n").getBytes(StandardCharsets.UTF_8);
                zip.putNextEntry(new ZipEntry(MANIFEST));
                zip.write(manifestBytes);
            }catch(JSONException error){throw new IOException("Manifesto de instância inválido.",error);}
            zip.closeEntry();
            long[] totals={0L,0L};
            for(String root:ROOTS){
                File item=new File(home,root);
                if(item.exists())zipTree(zip,home,item,totals);
            }
            zip.finish();
        }
    }

    static GameInstanceManager.Instance importZip(Context c,InputStream source,String name)throws IOException{
        if(source==null)throw new IOException("Arquivo de instância indisponível.");
        GameInstanceManager.Instance created=GameInstanceManager.createInstance(c,name);
        File home=created.gameHome.getCanonicalFile();
        Set<String> seen=new HashSet<>();
        long[] totals={0L,0L};int count=0;
        JSONObject manifest=null;
        try(ZipInputStream zip=new ZipInputStream(source)){
            ZipEntry entry;
            while((entry=zip.getNextEntry())!=null){
                if(++count>MAX_ENTRIES)throw new IOException("Arquivo contém entradas demais.");
                String path=normalize(entry.getName());
                if(MANIFEST.equals(path)){
                    manifest=readManifest(zip);
                    zip.closeEntry();
                    continue;
                }
                requireRoot(path);
                if(entry.isDirectory()){
                    File dir=safeChild(home,path);
                    if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Não foi possível criar "+path);
                    zip.closeEntry();
                    continue;
                }
                if(!seen.add(path))throw new IOException("Arquivo contém caminho duplicado: "+path);
                File targetFile=safeChild(home,path),parent=targetFile.getParentFile();
                if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Não foi possível criar "+path);
                File part=new File(parent,targetFile.getName()+".part");
                long fileBytes=0L;
                try(FileOutputStream out=new FileOutputStream(part,false)){
                    byte[] buffer=new byte[32768];int n;
                    while((n=zip.read(buffer))!=-1){
                        fileBytes+=n;totals[0]+=n;
                        if(fileBytes>MAX_FILE||totals[0]>MAX_TOTAL)throw new IOException("Conteúdo descompactado excede 2 GiB.");
                        out.write(buffer,0,n);
                    }
                    out.flush();out.getFD().sync();
                }catch(Exception error){
                    part.delete();
                    if(error instanceof IOException)throw(IOException)error;
                    throw new IOException("Falha ao extrair "+path,error);
                }
                if(Files.isSymbolicLink(targetFile.toPath())){part.delete();throw new IOException("Destino é um link simbólico.");}
                if(!part.renameTo(targetFile)){part.delete();throw new IOException("Não foi possível finalizar "+path);}
                zip.closeEntry();
            }
        }catch(Exception error){
            try{GameInstanceManager.deleteInstance(c,created.id);}catch(Exception ignored){}
            if(error instanceof IOException)throw(IOException)error;
            throw new IOException("O arquivo de instância não pôde ser importado.",error);
        }
        if(manifest!=null){
            try{
                InstanceMetadataStore.update(c,created.id,manifest.optString("group",""),
                        manifest.optString("icon","grass"),manifest.optInt("color",0xff6551c8));
            }catch(Exception ignored){}
        }
        return created;
    }

    static String normalize(String raw)throws IOException{
        if(raw==null||raw.isEmpty()||raw.length()>512||raw.startsWith("/")
                ||raw.indexOf('\\')>=0||raw.indexOf(':')>=0)throw new IOException("Caminho de arquivo inválido.");
        StringBuilder out=new StringBuilder();
        for(String part:raw.split("/",-1)){
            if(part.isEmpty()||".".equals(part)||"..".equals(part)||part.length()>180||hasControl(part))
                throw new IOException("Arquivo contém caminho inseguro.");
            if(out.length()>0)out.append('/');
            out.append(part);
        }
        return out.toString();
    }

    private static void copyTree(File source,File target,File root,long[] totals)throws IOException{
        if(Files.isSymbolicLink(source.toPath()))return;
        File canonical=source.getCanonicalFile();
        if(!canonical.equals(root.getCanonicalFile())
                &&!canonical.getPath().startsWith(root.getCanonicalPath()+File.separator))
            throw new IOException("Cópia encontrou caminho fora da instância.");
        if(source.isDirectory()){
            if(!target.isDirectory()&&!target.mkdirs())throw new IOException("Falha ao criar "+target.getName());
            File[] children=source.listFiles();
            if(children==null)throw new IOException("Não foi possível listar "+source.getName());
            for(File child:children){if(skip(child.getName()))continue;copyTree(child,new File(target,child.getName()),root,totals);}
            return;
        }
        if(!source.isFile())return;
        if(++totals[1]>MAX_ENTRIES||source.length()>MAX_FILE||totals[0]+source.length()>MAX_TOTAL)
            throw new IOException("Instância excede os limites de cópia.");
        File parent=target.getParentFile();
        if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Falha ao criar destino.");
        try(FileInputStream in=new FileInputStream(source);FileOutputStream out=new FileOutputStream(target,false)){
            byte[] buffer=new byte[32768];int n;
            while((n=in.read(buffer))!=-1){totals[0]+=n;if(totals[0]>MAX_TOTAL)throw new IOException("Instância excede 2 GiB.");out.write(buffer,0,n);}
            out.flush();out.getFD().sync();
        }
    }

    private static void zipTree(ZipOutputStream zip,File root,File source,long[] totals)throws IOException{
        if(Files.isSymbolicLink(source.toPath()))return;
        if(source.isDirectory()){
            File[] children=source.listFiles();if(children==null)throw new IOException("Diretório ilegível.");
            for(File child:children){if(skip(child.getName()))continue;zipTree(zip,root,child,totals);}
            return;
        }
        if(!source.isFile())return;
        if(++totals[1]>MAX_ENTRIES||source.length()>MAX_FILE||totals[0]+source.length()>MAX_TOTAL)
            throw new IOException("Instância excede os limites de exportação.");
        String path=normalize(root.toURI().relativize(source.toURI()).getPath());
        zip.putNextEntry(new ZipEntry(path));
        try(FileInputStream in=new FileInputStream(source)){
            byte[] buffer=new byte[32768];int n;
            while((n=in.read(buffer))!=-1){totals[0]+=n;if(totals[0]>MAX_TOTAL)throw new IOException("Instância excede 2 GiB.");zip.write(buffer,0,n);}
        }
        zip.closeEntry();
    }

    private static JSONObject readManifest(InputStream in)throws IOException{
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        byte[] buffer=new byte[4096];int n;
        while((n=in.read(buffer))!=-1){if(out.size()+n>32768)throw new IOException("Manifesto grande demais.");out.write(buffer,0,n);}
        try{
            JSONObject obj=new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
            if(obj.optInt("format",-1)!=1)throw new IOException("Formato do manifesto não suportado.");
            return obj;
        }catch(JSONException error){throw new IOException("Manifesto inválido.",error);}
    }

    private static File safeChild(File root,String relative)throws IOException{
        File base=root.getCanonicalFile(),target=new File(base,relative).getCanonicalFile();
        if(!target.getPath().startsWith(base.getPath()+File.separator))throw new IOException("Caminho saiu da instância.");
        return target;
    }
    private static void requireRoot(String path)throws IOException{
        String root=path.split("/",2)[0];
        boolean allowed=false;for(String item:ROOTS)if(root.equals(item)){allowed=true;break;}
        if(!allowed||MANIFEST.equals(path))throw new IOException("Conteúdo não permitido na instância: "+path);
    }
    private static boolean skip(String name){
        return "logs".equalsIgnoreCase(name)||"screenshots".equalsIgnoreCase(name)
                ||"world-backups".equalsIgnoreCase(name)||"backups".equalsIgnoreCase(name)
                ||"crash-reports".equalsIgnoreCase(name)||".cache".equalsIgnoreCase(name)
                ||name.endsWith(".part")||name.endsWith(".tmp");
    }
    private static boolean hasControl(String value){
        for(int i=0;i<value.length();i++)if(Character.isISOControl(value.charAt(i)))return true;
        return false;
    }
    private static String instanceName(Context c,String id)throws IOException{
        for(GameInstanceManager.Instance i:GameInstanceManager.listInstances(c))if(i.id.equals(id))return i.name;
        return "Instância";
    }
}
