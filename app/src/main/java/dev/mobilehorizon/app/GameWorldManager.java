package dev.mobilehorizon.app;

import android.content.Context;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.zip.*;

/** Discovers worlds and manages validated local backups inside the selected instance. */
public final class GameWorldManager {
    private static final String BACKUPS="world-backups", META="__mobilehorizon_backup.json";
    private static final int FORMAT=1, MAX_ENTRIES=150000, MAX_META=32768, MAX_NBT=16*1024*1024;
    private static final long MAX_BYTES=2L*1024*1024*1024;
    public static final class World {
        public final String folderName,displayName; public final long lastModified,sizeBytes;
        private World(String f,String n,long m,long s){folderName=f;displayName=n;lastModified=m;sizeBytes=s;}
    }
    public static final class Backup {
        public final String fileName,worldFolder,worldName; public final long createdAt,sizeBytes;
        private Backup(String f,String w,String n,long t,long s){fileName=f;worldFolder=w;worldName=n;createdAt=t;sizeBytes=s;}
    }
    private static final class Count {long entries,bytes;}
    private GameWorldManager(){}

    public static List<World> listWorlds(Context c)throws IOException{
        File saves=saves(c,false);if(!saves.isDirectory())return Collections.emptyList();
        File[] files=saves.listFiles();if(files==null)throw new IOException("Não foi possível listar os mundos.");
        ArrayList<World> out=new ArrayList<>();
        for(File dir:files){
            if(!dir.isDirectory()||Files.isSymbolicLink(dir.toPath())||!safeFolder(dir.getName()))continue;
            File level=new File(dir,"level.dat");if(!level.isFile()||Files.isSymbolicLink(level.toPath()))continue;
            String n=levelName(level);out.add(new World(dir.getName(),n==null||n.trim().isEmpty()?dir.getName():n.trim(),dir.lastModified(),sizeOf(dir)));
        }
        out.sort((a,b)->Long.compare(b.lastModified,a.lastModified));return Collections.unmodifiableList(out);
    }
    public static List<Backup> listBackups(Context c)throws IOException{
        File root=backups(c,false);if(!root.isDirectory())return Collections.emptyList();
        File[] files=root.listFiles();if(files==null)throw new IOException("Não foi possível listar backups.");
        ArrayList<Backup> out=new ArrayList<>();
        for(File f:files)if(f.isFile()&&!Files.isSymbolicLink(f.toPath())&&safeBackupName(f.getName())){
            Backup b=backupInfo(f);if(b!=null)out.add(b);
        }
        out.sort((a,b)->Long.compare(b.createdAt,a.createdAt));return Collections.unmodifiableList(out);
    }
    public static Backup createBackup(Context c,String folder)throws IOException{
        File world=worldDir(saves(c,false),folder);
        if(!world.isDirectory()||!new File(world,"level.dat").isFile())throw new IOException("O mundo não possui level.dat válido.");
        File root=backups(c,true);long now=System.currentTimeMillis();
        String file="world-backup-"+now+"-"+UUID.randomUUID().toString().replace("-","").substring(0,8)+".zip";
        File dest=new File(root,file),part=new File(root,file+".part");JSONObject meta=new JSONObject();
        try{meta.put("format",FORMAT);meta.put("world_folder",world.getName());String n=levelName(new File(world,"level.dat"));if(n!=null)meta.put("world_name",n);meta.put("created_at",now);}
        catch(JSONException e){throw new IOException("Metadados do backup inválidos.",e);}
        Count count=new Count();
        try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(part)))){
            zip.putNextEntry(new ZipEntry(META));zip.write(meta.toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            zipDir(world,world.getName(),"",zip,count);zip.finish();
        }catch(Exception e){part.delete();if(e instanceof IOException)throw(IOException)e;throw new IOException("Falha ao criar backup.",e);}
        move(part,dest);return new Backup(file,world.getName(),meta.optString("world_name",world.getName()),now,dest.length());
    }
    public static void restoreBackup(Context c,String file,boolean replace)throws IOException{
        if(!safeBackupName(file))throw new IOException("Nome de backup inválido.");
        File root=backups(c,false).getCanonicalFile(),archive=new File(root,file).getCanonicalFile();
        if(!root.equals(archive.getParentFile())||!archive.isFile()||Files.isSymbolicLink(archive.toPath())||archive.length()>MAX_BYTES)
            throw new IOException("Backup ausente, inseguro ou maior que 2 GiB.");
        try(ZipFile zip=new ZipFile(archive)){
            ZipEntry me=zip.getEntry(META);if(me==null||me.getSize()<0||me.getSize()>MAX_META)throw new IOException("Metadados de backup inválidos.");
            JSONObject meta;try(InputStream in=zip.getInputStream(me)){meta=new JSONObject(new String(readBounded(in,MAX_META),StandardCharsets.UTF_8));}
            catch(JSONException e){throw new IOException("Metadados de backup corrompidos.",e);}
            String folder=meta.optString("world_folder","");if(meta.optInt("format",-1)!=FORMAT||!safeFolder(folder))throw new IOException("Formato ou nome de mundo inválido.");
            restoreZip(zip,folder,saves(c,true),replace);
        }
    }
    private static void restoreZip(ZipFile zip,String folder,File saves,boolean replace)throws IOException{
        File stage=new File(saves,".mh-restore-"+UUID.randomUUID()),staged=new File(stage,folder);
        if(!staged.mkdirs())throw new IOException("Não foi possível preparar a restauração.");
        File target=worldDir(saves,folder),old=new File(saves,".mh-old-"+UUID.randomUUID());boolean oldMoved=false;
        try{
            Count count=new Count();Set<String> seen=new HashSet<>();Enumeration<? extends ZipEntry> es=zip.entries();String prefix=folder+"/";
            while(es.hasMoreElements()){
                ZipEntry e=es.nextElement();if(META.equals(e.getName()))continue;
                if(++count.entries>MAX_ENTRIES)throw new IOException("Backup contém arquivos demais.");
                String name=e.getName();if(!name.startsWith(prefix))throw new IOException("Backup contém dados fora do mundo.");
                String rel=name.substring(prefix.length());if(rel.isEmpty())continue;
                if(name.endsWith("/")&&rel.endsWith("/"))rel=rel.substring(0,rel.length()-1);
                String safe=archivePath(rel);if(!seen.add(safe))throw new IOException("Backup contém caminho repetido.");
                File out=child(staged,safe);
                if(e.isDirectory()||name.endsWith("/")){if(!out.isDirectory()&&!out.mkdirs())throw new IOException("Falha ao criar pasta restaurada.");continue;}
                File parent=out.getParentFile();if(parent==null||(!parent.isDirectory()&&!parent.mkdirs()))throw new IOException("Falha ao criar pasta de arquivo.");
                try(InputStream in=zip.getInputStream(e);FileOutputStream f=new FileOutputStream(out)){
                    byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1){count.bytes+=n;if(count.bytes>MAX_BYTES)throw new IOException("Backup descompactado excede 2 GiB.");f.write(b,0,n);}f.getFD().sync();
                }
            }
            if(!new File(staged,"level.dat").isFile())throw new IOException("Backup não contém level.dat.");
            if(target.exists()){
                if(!replace)throw new IOException("O mundo já existe; confirme a substituição.");
                if(!target.isDirectory()||Files.isSymbolicLink(target.toPath()))throw new IOException("Destino existente não é seguro.");
                move(target,old);oldMoved=true;
            }
            try{move(staged,target);}catch(IOException e){if(oldMoved){move(old,target);oldMoved=false;}throw e;}
            if(oldMoved){deleteTree(old,saves);oldMoved=false;}
        }finally{
            if(stage.exists())deleteTree(stage,saves);
            if(oldMoved&&old.exists()&&!target.exists())move(old,target);
        }
    }
    public static void deleteWorld(Context c,String folder)throws IOException{
        File root=saves(c,false),world=worldDir(root,folder);
        if(!world.isDirectory()||Files.isSymbolicLink(world.toPath()))throw new IOException("Mundo inexistente ou inseguro.");
        deleteTree(world,root);
    }
    public static void copyWorld(Context c, String sourceFolder, String requestedTargetFolder)
            throws IOException {
        File root = saves(c, false).getCanonicalFile();
        File source = worldDir(root, sourceFolder);
        if (!source.isDirectory() || !new File(source, "level.dat").isFile()
                || Files.isSymbolicLink(source.toPath())) {
            throw new IOException("Mundo de origem ausente ou inválido.");
        }
        String targetName = requestedTargetFolder == null ? "" : requestedTargetFolder.trim();
        if (!safeFolder(targetName)) throw new IOException("Nome da cópia inválido.");
        File target = worldDir(root, targetName);
        if (target.exists()) throw new IOException("Já existe um mundo com esse nome.");
        File stagingRoot = new File(root, ".mh-copy-" + UUID.randomUUID());
        File staged = new File(stagingRoot, targetName);
        if (!staged.mkdirs()) throw new IOException("Não foi possível preparar a cópia do mundo.");
        try {
            Count count = new Count();
            copyWorldTree(source, source.getCanonicalFile(), staged, count);
            if (!new File(staged, "level.dat").isFile()) {
                throw new IOException("A cópia não contém level.dat.");
            }
            if (target.exists()) throw new IOException("O nome do mundo já foi utilizado.");
            move(staged, target);
        } catch (Exception error) {
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Não foi possível duplicar o mundo.", error);
        } finally {
            if (stagingRoot.exists()) deleteTree(stagingRoot, root);
        }
    }

    private static void copyWorldTree(File source, File sourceRoot, File destination,
                                      Count count) throws IOException {
        if (Files.isSymbolicLink(source.toPath())) return;
        File canonical = source.getCanonicalFile();
        if (!canonical.equals(sourceRoot)
                && !canonical.getPath().startsWith(sourceRoot.getPath() + File.separator)) {
            throw new IOException("A cópia encontrou um caminho fora do mundo.");
        }
        String name = source.getName();
        if ("session.lock".equals(name) || name.startsWith(".mh-")
                || ".DS_Store".equals(name)) return;
        if (source.isDirectory()) {
            if (!destination.isDirectory() && !destination.mkdirs()) {
                throw new IOException("Não foi possível criar " + destination.getName());
            }
            File[] children = source.listFiles();
            if (children == null) throw new IOException("Diretório do mundo ilegível.");
            for (File child : children) {
                copyWorldTree(child, sourceRoot, new File(destination, child.getName()), count);
            }
            return;
        }
        if (!source.isFile()) return;
        if (++count.entries > MAX_ENTRIES || source.length() > MAX_BYTES
                || count.bytes + source.length() > MAX_BYTES) {
            throw new IOException("O mundo excede os limites de duplicação (2 GiB/150 mil arquivos).");
        }
        File parent = destination.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Não foi possível criar a pasta de destino.");
        try (InputStream input = new BufferedInputStream(new FileInputStream(source));
             FileOutputStream output = new FileOutputStream(destination, false)) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = input.read(buffer)) != -1) {
                count.bytes += read;
                if (count.bytes > MAX_BYTES) throw new IOException("O mundo excede 2 GiB.");
                output.write(buffer, 0, read);
            }
            output.flush();
            output.getFD().sync();
        }
    }

    public static void deleteBackup(Context c,String name)throws IOException{
        if(!safeBackupName(name))throw new IOException("Nome de backup inválido.");
        File root=backups(c,false).getCanonicalFile(),f=new File(root,name).getCanonicalFile();
        if(!root.equals(f.getParentFile())||!f.isFile()||Files.isSymbolicLink(f.toPath())||!f.delete())throw new IOException("Não foi possível excluir o backup.");
    }

    static boolean safeFolder(String s){return s!=null&&!s.trim().isEmpty()&&s.length()<=96&&!s.startsWith(".")&&!s.contains("..")&&!s.contains("/")&&!s.contains("\\")&&!s.contains(":")&&!controls(s);}
    static String archivePath(String s)throws IOException{
        if(s==null||s.isEmpty()||s.startsWith("/")||s.startsWith("\\")||s.contains("\\")||s.contains(":")||s.indexOf((char)0)>=0||controls(s))throw new IOException("Caminho de ZIP inválido.");
        StringBuilder out=new StringBuilder();for(String part:s.split("/",-1)){
            if(part.isEmpty()||".".equals(part)||"..".equals(part)||part.length()>128)throw new IOException("ZIP tenta sair da pasta permitida.");
            if(out.length()>0)out.append('/');out.append(part);
        }return out.toString();
    }
    private static String levelName(File f){
        if(!f.isFile()||f.length()>MAX_NBT)return null;
        try(PushbackInputStream probe=new PushbackInputStream(new BufferedInputStream(new FileInputStream(f)),2)){
            int a=probe.read(),b=probe.read();if(b>=0)probe.unread(new byte[]{(byte)a,(byte)b});else if(a>=0)probe.unread(a);
            InputStream decoded=a==0x1f&&b==0x8b?new GZIPInputStream(probe):probe;
            try(DataInputStream in=new DataInputStream(new Bounded(decoded,MAX_NBT))){if(in.readUnsignedByte()!=10)return null;in.readUTF();return rootData(in);}
        }catch(Exception ignored){return null;}
    }
    static String readLevelNameForTest(File f){return levelName(f);}
    private static String rootData(DataInputStream in)throws IOException{
        int count=0;while(true){int t=in.readUnsignedByte();if(t==0)return null;if(++count>1000000)throw new IOException("NBT grande.");String name=in.readUTF();if(t==10&&"Data".equals(name))return dataCompound(in,1);skipNbt(in,t,1);}
    }
    private static String dataCompound(DataInputStream in,int depth)throws IOException{
        if(depth>64)throw new IOException("NBT profundo.");int count=0;
        while(true){int t=in.readUnsignedByte();if(t==0)return null;if(++count>1000000)throw new IOException("NBT grande.");String n=in.readUTF();if(t==8&&"LevelName".equals(n))return in.readUTF();skipNbt(in,t,depth+1);}
    }
    private static void skipNbt(DataInputStream in,int t,int depth)throws IOException{
        if(depth>64)throw new IOException("NBT profundo.");
        switch(t){
            case 1:in.readByte();return;case 2:in.readShort();return;case 3:in.readInt();return;case 4:in.readLong();return;
            case 5:in.readFloat();return;case 6:in.readDouble();return;case 7:skip(in,checked(in.readInt()));return;case 8:in.readUTF();return;
            case 9:{int et=in.readUnsignedByte(),n=checked(in.readInt());for(int i=0;i<n;i++)skipNbt(in,et,depth+1);return;}
            case 10:{int n=0;while(true){int ct=in.readUnsignedByte();if(ct==0)return;if(++n>1000000)throw new IOException("NBT grande.");in.readUTF();skipNbt(in,ct,depth+1);}}
            case 11:skip(in,(long)checked(in.readInt())*4);return;case 12:skip(in,(long)checked(in.readInt())*8);return;
            default:throw new IOException("Tag NBT desconhecida.");
        }
    }
    private static int checked(int n)throws IOException{if(n<0||n>1000000)throw new IOException("Array NBT muito grande.");return n;}
    private static void skip(DataInputStream in,long n)throws IOException{byte[] b=new byte[8192];while(n>0){int r=in.read(b,0,(int)Math.min(n,b.length));if(r<0)throw new EOFException();n-=r;}}
    private static final class Bounded extends FilterInputStream{
        final long max;long used;Bounded(InputStream in,long m){super(in);max=m;}
        @Override public int read()throws IOException{if(used>=max){int x=super.read();if(x<0)return -1;throw new IOException("NBT excedeu o limite.");}int x=super.read();if(x>=0)used++;return x;}
        @Override public int read(byte[] b,int o,int l)throws IOException{if(l==0)return 0;if(used>=max){int x=super.read();if(x<0)return -1;throw new IOException("NBT excedeu o limite.");}int n=super.read(b,o,(int)Math.min(l,max-used));if(n>0)used+=n;return n;}
    }
    private static Backup backupInfo(File f){
        try(ZipFile zip=new ZipFile(f)){ZipEntry e=zip.getEntry(META);if(e==null||e.getSize()<0||e.getSize()>MAX_META)return null;
            JSONObject m;try(InputStream in=zip.getInputStream(e)){m=new JSONObject(new String(readBounded(in,MAX_META),StandardCharsets.UTF_8));}
            String folder=m.optString("world_folder","");if(m.optInt("format",-1)!=FORMAT||!safeFolder(folder))return null;
            return new Backup(f.getName(),folder,m.optString("world_name",folder),Math.max(0,m.optLong("created_at",f.lastModified())),f.length());
        }catch(Exception ignored){return null;}
    }
    private static byte[] readBounded(InputStream in,int max)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>max)throw new IOException("Metadados excessivos.");out.write(b,0,n);}return out.toByteArray();}
    private static void zipDir(File dir,String root,String relative,ZipOutputStream zip,Count c)throws IOException{
        File[] items=dir.listFiles();if(items==null)throw new IOException("Pasta do mundo ilegível.");Arrays.sort(items,Comparator.comparing(File::getName));
        for(File f:items){if(Files.isSymbolicLink(f.toPath()))continue;String rel=relative.isEmpty()?f.getName():relative+"/"+f.getName();String safe=archivePath(rel);
            if(f.isDirectory()){zipDir(f,root,safe,zip,c);continue;}if(!f.isFile())continue;if(++c.entries>MAX_ENTRIES)throw new IOException("Mundo possui arquivos demais.");
            zip.putNextEntry(new ZipEntry(root+"/"+safe));try(InputStream in=new BufferedInputStream(new FileInputStream(f))){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1){c.bytes+=n;if(c.bytes>MAX_BYTES)throw new IOException("Mundo excede 2 GiB.");zip.write(b,0,n);}}zip.closeEntry();
        }
    }
    private static File saves(Context c,boolean create)throws IOException{
        File home=GameInstanceManager.getSelectedInstanceHome(c).getCanonicalFile(),r=new File(home,"saves").getCanonicalFile();
        if(!home.equals(r.getParentFile()))throw new IOException("saves/ fora da instância.");if(create&&!r.isDirectory()&&!r.mkdirs())throw new IOException("Falha ao criar saves/.");return r;
    }
    private static File backups(Context c,boolean create)throws IOException{
        File home=GameInstanceManager.getSelectedInstanceHome(c).getCanonicalFile(),r=new File(home,BACKUPS).getCanonicalFile();
        if(!home.equals(r.getParentFile()))throw new IOException("Backups fora da instância.");if(create&&!r.isDirectory()&&!r.mkdirs())throw new IOException("Falha ao criar backups.");return r;
    }
    private static File worldDir(File saves,String name)throws IOException{
        if(!safeFolder(name))throw new IOException("Nome de mundo inválido.");File root=saves.getCanonicalFile(),f=new File(root,name).getCanonicalFile();if(!root.equals(f.getParentFile()))throw new IOException("Caminho saiu de saves/.");return f;
    }
    private static File child(File parent,String rel)throws IOException{File root=parent.getCanonicalFile(),f=new File(root,rel).getCanonicalFile();if(!f.getPath().startsWith(root.getPath()+File.separator))throw new IOException("Caminho saiu da restauração.");return f;}
    private static boolean safeBackupName(String s){return s!=null&&s.matches("world-backup-[0-9]{10,16}-[0-9a-f]{8}\\.zip");}
    private static boolean controls(String s){for(int i=0;i<s.length();i++)if(Character.isISOControl(s.charAt(i)))return true;return false;}
    private static long sizeOf(File root)throws IOException{long sum=0;ArrayList<File> todo=new ArrayList<>();todo.add(root);int seen=0;while(!todo.isEmpty()){File f=todo.remove(todo.size()-1);if(++seen>MAX_ENTRIES||Files.isSymbolicLink(f.toPath()))continue;File[] kids=f.listFiles();if(kids==null)continue;for(File k:kids){if(Files.isSymbolicLink(k.toPath()))continue;if(k.isDirectory())todo.add(k);else if(k.isFile())sum+=k.length();}}return sum;}
    private static void move(File a,File b)throws IOException{try{Files.move(a.toPath(),b.toPath(),StandardCopyOption.ATOMIC_MOVE);}catch(java.nio.file.AtomicMoveNotSupportedException e){Files.move(a.toPath(),b.toPath());}}
    private static void deleteTree(File f,File root)throws IOException{File r=root.getCanonicalFile(),c=f.getCanonicalFile();if(!c.equals(r)&&!c.getPath().startsWith(r.getPath()+File.separator))throw new IOException("Exclusão fora do diretório permitido.");if(Files.isSymbolicLink(f.toPath())){if(!f.delete())throw new IOException("Falha ao remover link.");return;}if(f.isDirectory()){File[] kids=f.listFiles();if(kids==null)throw new IOException("Diretório ilegível.");for(File k:kids)deleteTree(k,r);}if(f.exists()&&!f.delete())throw new IOException("Falha ao excluir "+f.getName());}
}
