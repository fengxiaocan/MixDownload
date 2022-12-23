package com.x.down.task;


import com.x.down.XDownload;
import com.x.down.base.IConnectRequest;
import com.x.down.base.IDownloadRequest;
import com.x.down.core.XDownloadRequest;
import com.x.down.m3u8.M3U8Info;
import com.x.down.m3u8.M3U8Ts;
import com.x.down.made.AutoRetryRecorder;
import com.x.down.proxy.SerializeProxy;
import com.x.down.tool.XDownUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

final class MultiDownloadM3u8Task extends HttpDownloadRequest implements MultiDownloadTask, IDownloadRequest, IConnectRequest {
    private final MultiM3u8Disposer multiDisposer;
    private final XDownloadRequest request;
    private final M3U8Ts m3U8Ts;
    private final M3U8Info m3U8Info;
    private final String saveFile;
    private final File tempFile;
    private final AtomicLong sofarSeg = new AtomicLong(0);
    private final AtomicLong sofar = new AtomicLong(0);
    private final ReentrantLock lock;
    private volatile Future taskFuture;

    public MultiDownloadM3u8Task(
            XDownloadRequest request,
            String saveFile,
            M3U8Info m3U8Info,
            File tempDir,
            AutoRetryRecorder recorder,
            final int index,
            MultiM3u8Disposer listener,
            ReentrantLock lock) {
        super(recorder, request.getBufferedSize());
        this.request = request;
        this.saveFile = saveFile;
        this.m3U8Info = m3U8Info;
        this.m3U8Ts = m3U8Info.getTsList().get(index);
        this.tempFile = m3U8Ts.getTsFile(tempDir);
        this.multiDisposer = listener;
        this.lock = lock;
    }

    public void setTaskFuture(Future taskFuture) {
        this.taskFuture = taskFuture;
    }

    @Override
    protected void completeRun() {
        XDownload.get().removeMultiDownload(tag(), this);
        taskFuture = null;
    }

    @Override
    protected void onExecute() throws Throwable {
        sofarSeg.set(0);
        sofar.set(0);

        if (m3U8Ts.hasKey()) {
            //下载Key信息
            File keyFile = m3U8Ts.getKeyFile(tempFile.getParentFile());
            checkIsCancel();
            downloadTs(keyFile, m3U8Ts.getKeyUri(), 0,false);
        }

        if (m3U8Ts.hasInitSegment()) {
            //下载MAP片段信息
            File tsSegFile = m3U8Ts.getInitSegmentFile(tempFile.getParentFile());

            //先获取保存的长度
            long length = m3U8Ts.getInitSegmentLength();
            if (length <= 0) {
                length = downloadLong(m3U8Ts.getInitSegmentUri());
                m3U8Ts.setInitSegmentLength(length);
                //更新长度保存到本地
                lock.lock();
                try {
                    SerializeProxy.writeM3u8Info(request, m3U8Info);
                } finally {
                    lock.unlock();
                }
            }
            downloadTs(tsSegFile, m3U8Ts.getInitSegmentUri(), length, true) ;
        }
        //先获取保存的长度
        long length = m3U8Ts.getTsSize();
        if (length <= 0) {
            length = downloadLong(m3U8Ts.getUrl());
            m3U8Ts.setTsSize(length);
            //更新长度保存到本地
            lock.lock();
            try {
                SerializeProxy.writeM3u8Info(request, m3U8Info);
            } finally {
                lock.unlock();
            }
        }
        downloadTs(tempFile, m3U8Ts.getUrl(), length, false);
    }

    private void downloadTs(File file, String url, long length, final boolean isSeg) throws Exception {
        long start = 0;
        if (file.exists()) {
            if (file.length() == length) {
                multiDisposer.removeTask(this);
                multiDisposer.onComplete(this);
                if (isSeg) {
                    sofarSeg.set(length);
                } else {
                    sofar.set(length);
                }
                return;
            } else if (file.length() > length) {
                file.delete();
                start = 0;
                if (isSeg) {
                    sofarSeg.set(0);
                } else {
                    sofar.set(0);
                }
            } else {
                start = file.length();
                if (isSeg) {
                    sofarSeg.set(file.length());
                } else {
                    sofar.set(file.length());
                }
            }
        } else {
            file.getParentFile().mkdirs();
        }

        checkIsCancel();

        HttpURLConnection http = request.buildConnect(url);

        if (start > 0) {
            http.setRequestProperty("Range", XDownUtils.jsonString("bytes=", start, "-", length));
        }

        http.connect();
        multiDisposer.onConnecting(this, getHeaders(http));

        checkIsCancel();

        //获取响应code
        int responseCode = http.getResponseCode();
        boolean acceptRanges = isAcceptRanges(http);

        //判断是否成功
        if (isSuccess(responseCode)) {
            if (!acceptRanges) {
                if (isSeg) {
                    sofarSeg.set(0);
                } else {
                    sofar.set(0);
                }
            }
            FileOutputStream os = new FileOutputStream(file, acceptRanges);
            readInputStream(http.getInputStream(), os, isSeg);

            if (isSeg) {
                sofarSeg.set(length);
            } else {
                sofar.set(length);
            }
            multiDisposer.removeTask(this);
            multiDisposer.onComplete(this);
            XDownUtils.disconnectHttp(http);
        } else {
            //尝试获取错误信息
            String stream = readStringStream(http.getErrorStream(), XDownUtils.getInputCharset(http));
            multiDisposer.onRequestError(this, responseCode, stream);
            XDownUtils.disconnectHttp(http);
            //失败重试
            tryToRetry(responseCode, stream);
        }
    }

    /**
     * 获取片段的长度
     *
     * @return
     * @throws Exception
     */
    private long downloadLong(String url) throws Exception {
        HttpURLConnection http = request.buildConnect(url);
        int responseCode = http.getResponseCode();

        while (isNeedRedirects(responseCode)) {
            http = redirectsConnect(http, request);
            responseCode = http.getResponseCode();
        }

        //优先获取文件长度再回调
        long contentLength = XDownUtils.getContentLength(http);
        multiDisposer.onConnecting(this, getHeaders(http));

        //连接中
        if (contentLength <= 0) {
            //长度获取不到的时候重新连接 获取不到长度则要求http请求不要gzip压缩
            XDownUtils.disconnectHttp(http);
            http = request.buildConnect();
            http.setRequestProperty("Accept-Encoding", "identity");
            http.connect();

            multiDisposer.onConnecting(this, getHeaders(http));

            contentLength = XDownUtils.getContentLength(http);
            //连接中
        }
        XDownUtils.disconnectHttp(http);
        return contentLength;
    }

    /**
     * @param is
     * @param os
     * @param isSeg
     * @return true 为完成操作,false为取消操作,需要退出循环
     * @throws IOException
     */
    protected void readInputStream(InputStream is, OutputStream os, final boolean isSeg) throws IOException {
        try {
            byte[] bytes = new byte[byteArraySize];
            int length;
            while ((length = is.read(bytes)) > 0) {
                checkIsCancel();
                os.write(bytes, 0, length);
                os.flush();
                onProgress(length, isSeg);
            }
        } finally {
            XDownUtils.closeIo(is);
            XDownUtils.closeIo(os);
        }
    }


    @Override
    protected void onRetry() {
        multiDisposer.onRetry(this);
    }

    @Override
    protected void onError(Throwable e) {
        multiDisposer.onFailure(this, e);
    }

    @Override
    protected void onCancel() {
        multiDisposer.onCancel(this);
    }

    protected void onProgress(int length, boolean isSeg) {
        if (isSeg) {
            sofarSeg.addAndGet(length);
        } else {
            sofar.addAndGet(length);
        }
        multiDisposer.onProgress(this, length);
    }

    @Override
    public String getFilePath() {
        return saveFile;
    }

    @Override
    public String tag() {
        return request.getTag();
    }

    @Override
    public String url() {
        return request.getConnectUrl();
    }

    @Override
    public boolean cancel() {
        cancelTask();
        if (taskFuture != null) {
            return taskFuture.cancel(true);
        }
        return false;
    }

    @Override
    public int retryCount() {
        return autoRetryRecorder.getRetryCount();
    }

    @Override
    public XDownloadRequest request() {
        return request;
    }

    @Override
    public long blockSofar() {
        return sofarSeg.get() + sofar.get();
    }

    @Override
    public File blockFile() {
        return tempFile;
    }
}
