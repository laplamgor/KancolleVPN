package xyz.hexene.localvpn;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.util.Pair;
import android.app.*;

import android.support.v4.app.NotificationCompat;
import android.widget.Toast;

import com.google.gson.Gson;
import com.socks.library.KLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Created by mangoking on 2017/2/14.
 */

public class Kancolle implements Runnable {
    private LinkedBlockingQueue<byte[]> APIqueue;
    private LocalVPNService vpnService;

    final String storage_path;
    final File start2_file;
    final static byte[] post_start2 = "POST /kcsapi/api_start2".getBytes();
    final static byte[] post_ship_deck = "POST /kcsapi/api_get_member/ship_deck".getBytes();
    final static byte[] post_port = "POST /kcsapi/api_port/port".getBytes();

    enum API {
        api_null,
        start2,
        ship_deck,
        port,

        text_returnTime
    }

    private API currentAPI;
    private String response;
    private ArrayList<Pair<API,byte[]>> APIList = new ArrayList<Pair<API, byte[]>>(Arrays.asList(
            Pair.create(API.start2,post_start2),
            Pair.create(API.ship_deck,post_ship_deck),
            Pair.create(API.port,post_port)
    ));

    public Kancolle(LinkedBlockingQueue<byte[]> APIQueue, LocalVPNService vpnService){
        this.APIqueue = APIQueue;
        this.vpnService = vpnService;
        this.storage_path = Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator  + "Android" + File.separator + "data" + File.separator + vpnService.getPackageName() + File.separator;
        this.start2_file = new File(storage_path+"start2.json");
        currentAPI = API.api_null;
    }

    @Override
    public void run(){
        try {
            while (!Thread.interrupted()) {
                currentAPI = API.api_null;
                byte[] request = APIqueue.take();
                KLog.d("Kancolle thread: "+new String(request));
                if(!parseRequest(request)) continue;
                byte[] raw_response = APIqueue.take();
                KLog.d("Kancolle thread: "+new String(raw_response));
                if(!parseResponse(raw_response)) continue;
                switch (currentAPI){
                    case start2:
                        action_start2();
                        break;
                    case ship_deck:
                        break;
                    case port:
                        action_port();
                        break;
                    case text_returnTime:
                        action_completeTime(request);
                        break;
                    default:
                        break;
                }

            }
        }
        catch (Exception e){

        }
    }

    boolean parseRequest(byte[] request){
        for (Pair<API, byte[]> api:
        APIList){
            if( isEqual(request,api.second,api.second.length)){
                KLog.i("Kancolle thread: got API "+new String(api.second));
                currentAPI = api.first;
                return true;
            }
        }

        String requestString = new String(request);
        if (requestString.contains("api_complatetime")) {
            currentAPI = API.text_returnTime;
            return true;
        }



        KLog.i("Kancolle thread: Unknown API: " + requestString);


        return false;
    }

    boolean parseResponse(byte[] raw_response){
        response = new String(raw_response);
        return true;
    }

    void action_completeTime(byte[] request){
        String requestString = new String(request);
        int start = requestString.indexOf("\"api_complatetime\":") + "\"api_complatetime\":".length();
        int end = requestString.indexOf(",\"", start);

        long endTime = Long.parseLong(requestString.substring(start, end));
        long currentTime = System.currentTimeMillis();
        int seconds = (int) (endTime - currentTime)/1000;
        countDownNotification(endTime);
        KLog.i("Kancolle thread: completeTime: " + seconds );


    }

    int uid = 1;













    void countDownNotification(final long endTime){

        final int id = uid++;
        final NotificationManager mNotifyManager =
                (NotificationManager) MyApp.getContext().getSystemService(MyApp.getContext().NOTIFICATION_SERVICE);
        final NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(MyApp.getInstance());
        long secondLeft = (int) (endTime - System.currentTimeMillis()) / 1000;
        mBuilder.setContentTitle("遠征")
                .setContentText(String.format("%02d:%02d:%02d",secondLeft / 3600 ,  (secondLeft % 3600) / 60 , secondLeft % 60))
                .setSmallIcon(R.drawable.ic_launcher);

        // Get the kancolle app packet
        Context context = MyApp.getContext();
        PackageManager pm = context.getPackageManager();
        Intent LaunchIntent = null;
        String kancollePackageName = "com.dmm.dmmlabo.kancolle";
        try {
            if (pm != null) {
                ApplicationInfo app = context.getPackageManager().getApplicationInfo(kancollePackageName, 0);
                LaunchIntent = pm.getLaunchIntentForPackage(kancollePackageName);
            }
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        final PendingIntent pIntent = PendingIntent.getActivity(context, 0, LaunchIntent, PendingIntent.FLAG_UPDATE_CURRENT);


        // Start a lengthy operation in a background thread
        new Thread(
                new Runnable() {
                    @Override
                    public void run() {
                        long startTime = System.currentTimeMillis();
                        // Do the "lengthy" operation 20 times
                        while (System.currentTimeMillis() <= endTime) {
                            long secondLeft = (int) (endTime - System.currentTimeMillis()) / 1000;
                            mBuilder.setContentText(String.format("%02d:%02d:%02d",secondLeft / 3600 , (secondLeft % 3600) / 60 , secondLeft % 60))
                                    .setProgress((int) (endTime - startTime) / 1000, (int) (System.currentTimeMillis() - startTime) / 1000, false)
                                    .setOngoing(true);
                            // Displays the progress bar for the first time.
                            mNotifyManager.notify(id, mBuilder.build());

                            // Sleeps the thread, for each second
                            try {
                                Thread.sleep(1*1000);
                            } catch (InterruptedException e) {

                            }
                        }

                        // When the loop is finished, updates the notification
                        mBuilder.setContentText("遠征完成")
                                // Removes the progress bar
                                .setProgress(0,0,false)
                                // Vibrate twice
                                .setVibrate(new long[]{ 0, 100, 200, 100})
                                // Click the notification to launch Kancolle
                               .setContentIntent(pIntent)
                               .setOngoing(false).setAutoCancel(true);

                        mNotifyManager.notify(id, mBuilder.build());
                    }
                }
        ).start();
    }

    void action_start2(){
        FileOutputStream outputStream;
        try {
            start2_file.getParentFile().mkdirs();
            outputStream = new FileOutputStream( start2_file);
            outputStream.write(response.getBytes());
            outputStream.close();
            KLog.i("Kancolle thread: update start2 "+start2_file);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void action_port(){

        // For debug only
        // countDownNotification(System.currentTimeMillis() + 10000);
    }

    void load_start2(){

    }

    boolean isEqual(byte[] a1, byte[] a2, int size) {
        for(int i=0;i<size;i++)
            if (a1[i] != a2[i])
                return false;
        return true;
    }

}