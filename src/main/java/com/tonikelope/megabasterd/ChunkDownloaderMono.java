package com.tonikelope.megabasterd;

import static com.tonikelope.megabasterd.CryptTools.forwardMEGALinkKeyIV;
import static com.tonikelope.megabasterd.CryptTools.genDecrypter;
import static com.tonikelope.megabasterd.CryptTools.initMEGALinkKey;
import static com.tonikelope.megabasterd.CryptTools.initMEGALinkKeyIV;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import static com.tonikelope.megabasterd.MiscTools.*;
import static com.tonikelope.megabasterd.MainPanel.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import javax.crypto.CipherInputStream;

/**
 *
 * @author tonikelope
 */
public class ChunkDownloaderMono extends ChunkDownloader {

    public ChunkDownloaderMono(Download download) {
        super(1, download);
    }

    @Override
    public void run() {

        Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Worker [{1}]: let''s do some work!", new Object[]{Thread.currentThread().getName(), getId()});

        HttpURLConnection con = null;

        try {

            String worker_url = null;
            int conta_error = 0, error = 0;
            boolean chunk_error = false;
            long chunk_id = 0, bytes_written = getDownload().getProgress();
            byte[] byte_file_key = initMEGALinkKey(getDownload().getFile_key());
            byte[] byte_iv = initMEGALinkKeyIV(getDownload().getFile_key());

            getDownload().getView().set509Error(false);

            CipherInputStream cis = null;

            while (!isExit() && !getDownload().isStopped()) {

                int http_status = 0;

                if (worker_url == null || error == 403) {

                    worker_url = getDownload().getDownloadUrlForWorker();
                }

                chunk_id = getDownload().nextChunkId();

                long chunk_offset = ChunkManager.calculateChunkOffset(chunk_id, 1);

                long chunk_size = ChunkManager.calculateChunkSize(chunk_id, getDownload().getFile_size(), chunk_offset, 1);

                ChunkManager.checkChunkID(chunk_id, getDownload().getFile_size(), chunk_offset);

                long chunk_reads = 0;

                try {

                    if (con == null || chunk_error) {

                        URL url = new URL(worker_url + "/" + chunk_offset);

                        if (MainPanel.isUse_proxy()) {

                            con = (HttpURLConnection) url.openConnection(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(MainPanel.getProxy_host(), MainPanel.getProxy_port())));

                            if (MainPanel.getProxy_user() != null && !"".equals(MainPanel.getProxy_user())) {

                                con.setRequestProperty("Proxy-Authorization", "Basic " + MiscTools.Bin2BASE64((MainPanel.getProxy_user() + ":" + MainPanel.getProxy_pass()).getBytes()));
                            }
                        } else {

                            con = (HttpURLConnection) url.openConnection();
                        }

                        con.setConnectTimeout(Download.HTTP_TIMEOUT);

                        con.setReadTimeout(Download.HTTP_TIMEOUT);

                        con.setRequestProperty("User-Agent", MainPanel.DEFAULT_USER_AGENT);

                        http_status = con.getResponseCode();

                        cis = new CipherInputStream(new ThrottledInputStream(con.getInputStream(), getDownload().getMain_panel().getStream_supervisor()), genDecrypter("AES", "AES/CTR/NoPadding", byte_file_key, forwardMEGALinkKeyIV(byte_iv, bytes_written)));
                    }

                    chunk_error = true;

                    error = 0;

                    if (getDownload().isError509()) {
                        getDownload().getView().set509Error(false);
                    }

                    if (http_status != 200) {

                        Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Failed : HTTP error code : {1}", new Object[]{Thread.currentThread().getName(), http_status});

                        error = http_status;

                        if (error == 509) {

                            getDownload().getView().set509Error(true);

                        }

                        getDownload().rejectChunkId(chunk_id);

                        conta_error++;

                        if (!isExit() && error != 403) {

                            setError_wait(true);

                            Thread.sleep(getWaitTimeExpBackOff(conta_error) * 1000);

                            setError_wait(false);
                        }

                    } else {

                        if (!isExit() && !getDownload().isStopped() && cis != null) {

                            byte[] buffer = new byte[DEFAULT_BYTE_BUFFER_SIZE];

                            int reads;

                            while (!getDownload().isStopped() && chunk_reads < chunk_size && bytes_written + chunk_reads < getDownload().getFile_size() && (reads = cis.read(buffer, 0, Math.min(Math.min((int) (chunk_size - chunk_reads), (int) (getDownload().getFile_size() - (bytes_written + chunk_reads))), buffer.length))) != -1) {

                                getDownload().getOutput_stream().write(buffer, 0, reads);

                                chunk_reads += reads;

                                getDownload().getPartialProgressQueue().add(reads);

                                getDownload().getProgress_meter().secureNotify();

                                if (getDownload().isPaused() && !getDownload().isStopped()) {

                                    getDownload().pause_worker_mono();

                                    secureWait();

                                } else if (!getDownload().isPaused() && getDownload().getMain_panel().getDownload_manager().isPaused_all()) {

                                    getDownload().pause();

                                    getDownload().pause_worker_mono();

                                    secureWait();
                                }

                            }

                            if (chunk_reads == chunk_size) {

                                bytes_written += chunk_reads;

                                conta_error = 0;

                                chunk_error = false;

                                error = 0;

                            }
                        }

                    }

                } catch (IOException ex) {

                    Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);

                } catch (InterruptedException ex) {
                    Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, ex);
                } finally {

                    if (chunk_error) {

                        getDownload().rejectChunkId(chunk_id);

                        if (chunk_reads > 0) {
                            getDownload().getPartialProgressQueue().add(-1 * (int) chunk_reads);

                            getDownload().getProgress_meter().secureNotify();
                        }

                        if (!isExit() && !getDownload().isStopped() && error != 403) {

                            setError_wait(true);

                            Thread.sleep(getWaitTimeExpBackOff(++conta_error) * 1000);

                            setError_wait(false);
                        }

                        if (con != null) {
                            con.disconnect();
                            con = null;

                            if (cis != null) {
                                cis.close();
                            }
                        }

                    }

                }

            }

        } catch (ChunkInvalidException e) {

        } catch (OutOfMemoryError | Exception error) {
            getDownload().stopDownloader(error.getMessage());
            Logger.getLogger(getClass().getName()).log(Level.SEVERE, null, error.getMessage());
        }

        getDownload().stopThisSlot(this);

        getDownload().secureNotify();

        Logger.getLogger(getClass().getName()).log(Level.INFO, "{0} Worker [{1}]: bye bye", new Object[]{Thread.currentThread().getName(), getId()});

    }
}
