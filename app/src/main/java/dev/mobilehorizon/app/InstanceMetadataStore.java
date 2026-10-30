package dev.mobilehorizon.app;

import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** UI-only metadata for instances; it never changes existing game files or account data. */
final class InstanceMetadataStore {
    private static final String FILE_NAME="instance-ui.json";
    private static final int FORMAT=1;
    static final class Metadata {
        final String group,icon,pinnedVersion;
        final int color;
        final long totalPlayedMs,lastPlayedAt;
        Metadata(String group,String icon,int color,String version,long played,long lastPlayed){
            this.group=group;this.icon=icon;this.color=color;this.pinnedVersion=version;
            this.totalPlayedMs=played;this.lastPlayedAt=lastPlayed;
        }
        static Metadata defaults(){return new Metadata("","grass",0xff6551c8,"",0L,0L);}
    }
    private InstanceMetadataStore(){}

    static synchronized Metadata get(Context c,String id)throws IOException{
        requireId(id);
        JSONObject item=read(c).optJSONObject(id);
        if(item==null)return Metadata.defaults();
        return new Metadata(safeGroup(item.optString("group","")),
                safeIcon(item.optString("icon","grass")),
                safeColor(item.optInt("color",0xff6551c8)),
                safeVersion(item.optString("pinned_version","")),
                Math.max(0L,item.optLong("played_ms",0L)),
                Math.max(0L,item.optLong("last_played_at",0L)));
    }

    static synchronized void update(Context c,String id,String group,String icon,int color)throws IOException{
        requireId(id);
        JSONObject all=read(c),item=all.optJSONObject(id);
        if(item==null)item=new JSONObject();
        put(item,"group",safeGroup(group));put(item,"icon",safeIcon(icon));put(item,"color",safeColor(color));
        put(all,id,item);write(c,all);
    }

    static synchronized void setPinnedVersion(Context c,String id,String version)throws IOException{
        requireId(id);
        JSONObject all=read(c),item=all.optJSONObject(id);
        if(item==null)item=new JSONObject();
        put(item,"pinned_version",safeVersion(version));
        put(all,id,item);write(c,all);
    }

    static synchronized void markStarted(Context c,String id)throws IOException{
        requireId(id);
        JSONObject all=read(c),item=all.optJSONObject(id);
        if(item==null)item=new JSONObject();
        long now=System.currentTimeMillis();
        if(item.optLong("playing_since",0L)<=0L)put(item,"playing_since",now);
        put(item,"last_played_at",now);
        put(all,id,item);write(c,all);
    }

    static synchronized void markStopped(Context c,String id)throws IOException{
        requireId(id);
        JSONObject all=read(c),item=all.optJSONObject(id);
        if(item==null)return;
        long started=item.optLong("playing_since",0L);
        if(started<=0L)return;
        long elapsed=Math.max(0L,Math.min(24L*60L*60L*1000L,System.currentTimeMillis()-started));
        put(item,"played_ms",Math.max(0L,item.optLong("played_ms",0L))+elapsed);
        put(item,"playing_since",0L);
        put(all,id,item);write(c,all);
    }

    static synchronized void remove(Context c,String id)throws IOException{
        requireId(id);JSONObject all=read(c);all.remove(id);write(c,all);
    }

    static String safeGroup(String value){
        String group=value==null?"":value.trim();
        if(group.length()>28||hasControl(group))throw new IllegalArgumentException("Nome de grupo inválido.");
        return group;
    }
    static String safeIcon(String value){
        String icon=value==null?"grass":value.trim();
        if(icon.isEmpty())return "grass";
        if(icon.length()>48||hasControl(icon))throw new IllegalArgumentException("Ícone inválido.");
        return icon;
    }
    static int safeColor(int value){return 0xff000000|(value&0x00ffffff);}
    static String safeVersion(String value){
        String version=value==null?"":value.trim();
        if(version.isEmpty()||"latest".equalsIgnoreCase(version))return "";
        if(!version.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,79}"))
            throw new IllegalArgumentException("Tag da runtime inválida.");
        return version;
    }

    private static JSONObject read(Context c)throws IOException{
        File target=file(c);
        if(!target.isFile())return new JSONObject();
        if(Files.isSymbolicLink(target.toPath())||target.length()<=0||target.length()>512*1024)
            throw new IOException("Catálogo visual das instâncias inválido.");
        try{
            JSONObject root=new JSONObject(new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8));
            if(root.optInt("format",-1)!=FORMAT||root.optJSONObject("instances")==null)
                throw new IOException("Formato do catálogo visual não suportado.");
            return root.getJSONObject("instances");
        }catch(JSONException error){throw new IOException("Não foi possível ler os metadados das instâncias.",error);}
    }

    private static void write(Context c,JSONObject instances)throws IOException{
        File target=file(c),temporary=new File(target.getParentFile(),FILE_NAME+".tmp");
        if(Files.isSymbolicLink(target.toPath())||Files.isSymbolicLink(temporary.toPath()))
            throw new IOException("Metadados das instâncias não podem ser links simbólicos.");
        JSONObject root=new JSONObject();
        try{root.put("format",FORMAT);root.put("instances",instances);}
        catch(JSONException error){throw new IOException("Não foi possível montar o catálogo visual.",error);}
        final byte[] bytes;
        try{bytes=(root.toString(2)+"\n").getBytes(StandardCharsets.UTF_8);}
        catch(JSONException error){throw new IOException("Não foi possível serializar o catálogo visual.",error);}
        try(FileOutputStream out=new FileOutputStream(temporary,false)){
            out.write(bytes);out.flush();out.getFD().sync();
        }
        try{
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }catch(java.nio.file.AtomicMoveNotSupportedException unsupported){
            Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);
        }finally{if(temporary.exists())temporary.delete();}
    }

    private static File file(Context c)throws IOException{
        File files=c.getApplicationContext().getFilesDir().getCanonicalFile();
        File requested=new File(files,"instances");
        if(!requested.isDirectory()&&!requested.mkdirs())throw new IOException("Não foi possível criar o diretório de instâncias.");
        File root=requested.getCanonicalFile();
        if(!"instances".equals(root.getName())||!files.equals(root.getParentFile()))
            throw new IOException("Diretório de metadados fora do armazenamento privado.");
        return new File(root,FILE_NAME);
    }
    private static void requireId(String id)throws IOException{
        if(!GameInstanceManager.isValidInstanceId(id))throw new IOException("ID de instância inválido.");
    }
    private static boolean hasControl(String s){
        for(int i=0;i<s.length();i++)if(Character.isISOControl(s.charAt(i)))return true;
        return false;
    }
    private static void put(JSONObject object,String key,Object value)throws IOException{
        try{object.put(key,value);}catch(JSONException error){throw new IOException("Falha ao salvar metadados.",error);}
    }
}
