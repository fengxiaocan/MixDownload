package com.x.down.proxy;

import com.x.down.core.XDownloadRequest;
import com.x.down.m3u8.M3U8Info;
import com.x.down.made.DownInfo;
import com.x.down.made.DownloaderBlock;
import com.x.down.tool.XDownUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

public final class SerializeProxy {
    private static final String BLOCK_NAME = "BLOCK";
    private static final String M3U8_INFO_NAME = "M3U8_BLOCK";

    /**
     * 保存下载的长度以及文件类型
     *
     * @param request
     * @param info
     */
    public static void writeDownloaderInfo(XDownloadRequest request, DownInfo info) {
        File recordFile = XDownUtils.getRecordCacheFile(request);
        ObjectOutputStream stream = null;
        try {
            stream = new ObjectOutputStream(new FileOutputStream(recordFile));
            stream.writeLong(info.getLength());
            stream.writeBoolean(info.isAccecp());
            stream.flush();
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            XDownUtils.closeIo(stream);
        }
    }

    /**
     * 获取下载的长度以及文件类型
     *
     * @param request
     */
    public static DownInfo readDownloaderInfo(XDownloadRequest request) {
        File recordFile = XDownUtils.getRecordCacheFile(request);
        if (recordFile.exists()) {
            ObjectInputStream stream = null;
            try {
                stream = new ObjectInputStream(new FileInputStream(recordFile));
                long readLong = stream.readLong();
                boolean readBoolean = stream.readBoolean();
                return new DownInfo(readLong, readBoolean);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            } finally {
                XDownUtils.closeIo(stream);
            }
        }
        return null;
    }

    /**
     * 保存多线程下载的数量配置
     *
     * @param request
     * @param block
     */
    public static void writeDownloaderBlock(XDownloadRequest request, DownloaderBlock block) {
        File cacheDir = XDownUtils.getTempCacheDir2(request);
        File file = new File(cacheDir, BLOCK_NAME);
        XDownUtils.writeObject(file, block);
    }

    /**
     * 获取多线程下载的数量配置
     */
    public static DownloaderBlock readDownloaderBlock(XDownloadRequest request) {
        File cacheDir = XDownUtils.getTempCacheDir(request);
        if (cacheDir.exists()) {
            File file = new File(cacheDir, BLOCK_NAME);
            return XDownUtils.readObject(file);
        }
        return null;
    }

    /**
     * 保存m3u8的信息
     */
    public static void deleteM3u8Info(XDownloadRequest request) {
        File tempCacheDir = XDownUtils.getTempCacheDir(request);
        new File(tempCacheDir, M3U8_INFO_NAME).delete();
    }

    /**
     * 保存m3u8的信息
     */
    public static void writeM3u8Info(XDownloadRequest request, M3U8Info block) {
        File tempCacheDir = XDownUtils.getTempCacheDir2(request);
        File file = new File(tempCacheDir, M3U8_INFO_NAME);
        XDownUtils.writeObject(file, block);
    }

    /**
     * 获取m3u8的信息
     */
    public static M3U8Info readM3u8Info(XDownloadRequest request) {
        File cacheDir = XDownUtils.getTempCacheDir(request);
        if (cacheDir.exists()) {
            File file = new File(cacheDir, M3U8_INFO_NAME);
            return XDownUtils.readObject(file);
        }
        return null;
    }
}
