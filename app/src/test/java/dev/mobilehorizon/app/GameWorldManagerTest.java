package dev.mobilehorizon.app;

import org.junit.Test;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.zip.GZIPOutputStream;
import static org.junit.Assert.*;

public final class GameWorldManagerTest {
    @Test public void acceptsOrdinaryWorldFolderNames(){
        assertTrue(GameWorldManager.safeFolder("Sobrevivencia 1"));
        assertTrue(GameWorldManager.safeFolder("World-2026"));
    }
    @Test public void rejectsWorldPathTraversal(){
        assertFalse(GameWorldManager.safeFolder("../outside"));
        assertFalse(GameWorldManager.safeFolder("world/other"));
        assertFalse(GameWorldManager.safeFolder(".hidden"));
    }
    @Test public void normalizesSafeArchivePaths()throws Exception{
        assertEquals("region/r.0.0.mca",GameWorldManager.archivePath("region/r.0.0.mca"));
    }
    @Test(expected=java.io.IOException.class) public void rejectsArchivePathTraversal()throws Exception{
        GameWorldManager.archivePath("region/../../outside");
    }
    @Test public void readsLevelNameFromGzipNbt()throws Exception{
        File f=File.createTempFile("mobilehorizon-level",".dat");
        try{
            try(DataOutputStream o=new DataOutputStream(new GZIPOutputStream(new FileOutputStream(f)))){
                o.writeByte(10);o.writeUTF("");o.writeByte(10);o.writeUTF("Data");
                o.writeByte(8);o.writeUTF("LevelName");o.writeUTF("Sobrevivência");o.writeByte(0);o.writeByte(0);
            }
            assertEquals("Sobrevivência",GameWorldManager.readLevelNameForTest(f));
        }finally{f.delete();}
    }
}
