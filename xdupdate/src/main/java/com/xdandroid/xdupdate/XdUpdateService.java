package com.xdandroid.xdupdate;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Created by XingDa on 2016/04/24.
 */
public class XdUpdateService extends Service {

    private Notification.Builder builder;
    private NotificationManager manager;
    private volatile int fileLength;
    private volatile int length;
    private DeleteReceiver deleteReceiver;
    private File file;
    private volatile boolean interrupted;

    private static final int TYPE_FINISHED = 0;
    private static final int TYPE_DOWNLOADING = 1;

    class DeleteReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            interrupted = true;
            handler.sendEmptyMessage(TYPE_FINISHED);
            manager.cancel(2);
            if (file != null && file.exists()) {
                file.delete();
            }
            stopSelf();
        }
    }

    @SuppressLint("HandlerLeak")
    private Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            if (hasMessages(TYPE_DOWNLOADING)) {
                removeMessages(TYPE_DOWNLOADING);
            }
            if (hasMessages(TYPE_FINISHED)) {
                removeMessages(TYPE_FINISHED);
            }
            switch (msg.what) {
                case TYPE_DOWNLOADING:
                    if (interrupted) {
                        manager.cancel(2);
                    } else {
                        manager.notify(2, builder.setContentText(XdUpdateUtils.formatToMegaBytes(length)+"M/"+ XdUpdateUtils.formatToMegaBytes(fileLength)+"M").setProgress(fileLength, length, false).getNotification());
                        sendEmptyMessageDelayed(TYPE_DOWNLOADING, 512);
                    }
                    break;
                case TYPE_FINISHED:
                    manager.cancel(2);
                    break;
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final XdUpdateBean xdUpdateBean = (XdUpdateBean) intent.getSerializableExtra("xdUpdateBean");
        int iconResId = intent.getIntExtra("appIcon", 0);
        if (xdUpdateBean == null || iconResId == 0) {
            stopSelf();
            return START_NOT_STICKY;
        }
        deleteReceiver = new DeleteReceiver();
        getApplicationContext().registerReceiver(deleteReceiver,new IntentFilter("com.xdandroid.xdupdate.DeleteUpdate"));
        builder = new Notification.Builder(XdUpdateService.this)
                .setProgress(0, 0, false)
                .setAutoCancel(false)
                .setTicker(XdUpdateUtils.getApplicationName(getApplicationContext())+ xdUpdateBean.getVersionName()+"正在下载")
                .setSmallIcon(iconResId)
                .setContentTitle(XdUpdateUtils.getApplicationName(getApplicationContext())+ xdUpdateBean.getVersionName()+"正在下载...")
                .setContentText("")
                .setDeleteIntent(PendingIntent.getBroadcast(getApplicationContext(),3,new Intent("com.xdandroid.xdupdate.DeleteUpdate"),PendingIntent.FLAG_CANCEL_CURRENT));
        manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                URL url;
                HttpURLConnection connection = null;
                InputStream is = null;
                FileOutputStream fos = null;
                try {
                    url = new URL(xdUpdateBean.getUrl());
                    connection = (HttpURLConnection) url.openConnection();
                    connection.connect();
                    is = connection.getInputStream();
                    fileLength = connection.getContentLength();
                    file = new File(getExternalCacheDir(),"download.apk");
                    if (file.exists()) {
                        file.delete();
                    }
                    fos = new FileOutputStream(file);
                    byte[] buffer = new byte[1024];
                    int hasRead;
                    handler.sendEmptyMessage(TYPE_DOWNLOADING);
                    interrupted = false;
                    while ((hasRead = is.read(buffer)) > 0) {
                        if (interrupted) {
                            return;
                        }
                        fos.write(buffer, 0 , hasRead);
                        length = length + hasRead;
                    }
                    handler.sendEmptyMessage(TYPE_FINISHED);
                    length = 0;
                    if (file.exists()) {
                        if (XdUpdateUtils.getMd5ByFile(file).equalsIgnoreCase(xdUpdateBean.getMd5())) {
                            Uri uri = Uri.fromFile(file);
                            Intent intent = new Intent(Intent.ACTION_VIEW);
                            intent.setDataAndType(uri, "application/vnd.android.package-archive");
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(intent);
                        } else {
                            file.delete();
                        }
                    }
                } catch (IOException e) {
                    sendBroadcast(new Intent("com.xdandroid.xdupdate.DeleteUpdate"));
                } finally {
                    if (fos != null) {
                        try {
                            fos.close();
                        } catch (IOException ignored) {
                        }
                    }
                    if (is != null) {
                        try {
                            is.close();
                        } catch (IOException ignored) {
                        }
                    }
                    if (connection != null) {
                        connection.disconnect();
                    }
                    stopSelf();
                }

            }
        }).start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (deleteReceiver != null) {
            getApplicationContext().unregisterReceiver(deleteReceiver);
        }
    }
}