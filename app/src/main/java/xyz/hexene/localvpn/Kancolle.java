package xyz.hexene.localvpn;

import android.os.Environment;
import android.util.Pair;

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
        port
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
        KLog.i("Kancolle thread: Unknown API");
        return false;
    }

    boolean parseResponse(byte[] raw_response){
        response = new String(raw_response);
        return true;
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