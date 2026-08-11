package com.radolyn.ayugram.database;

import com.radolyn.ayugram.database.dao.DeletedMessageDao;
import com.radolyn.ayugram.database.dao.EditedMessageDao;
import com.radolyn.ayugram.database.dao.LastSeenDao;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Play build: Ayu/Spy Room database runtime is intentionally absent. */
public final class AyuData {
    public static long dbSize = 0L;
    public static long attachmentsSize = 0L;
    public static long totalSize = 0L;

    private AyuData() { }

    public static synchronized void create() { }
    public static AyuDatabase getDatabase() { return null; }
    public static EditedMessageDao getEditedMessageDao() { return null; }
    public static DeletedMessageDao getDeletedMessageDao() { return null; }
    public static LastSeenDao getLastSeenDao() { return null; }
    public static synchronized void clean() { }

    public static synchronized void exportDatabase(OutputStream outputStream) throws IOException {
        throw new IOException("Ayu database is unavailable in the Play build");
    }

    public static synchronized void importDatabase(InputStream inputStream) throws IOException {
        throw new IOException("Ayu database is unavailable in the Play build");
    }

    public static long getDatabaseSize() { return 0L; }
    public static long getAyuDatabaseSize() { return 0L; }
    public static long getAttachmentsDirSize() { return 0L; }

    public static void loadSizes(Runnable callback) {
        dbSize = 0L;
        attachmentsSize = 0L;
        totalSize = 0L;
        if (callback != null) callback.run();
    }
}
