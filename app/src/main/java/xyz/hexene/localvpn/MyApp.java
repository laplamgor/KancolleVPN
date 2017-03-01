package xyz.hexene.localvpn;

import android.app.Application;
import android.content.Context;

/**
 * Created by Leo on 26/2/2017.
 */

public class MyApp extends Application {
    private static MyApp instance;

    public static MyApp getInstance() {
        return instance;
    }

    public static Context getContext(){
        return instance;
    }

    @Override
    public void onCreate() {
        instance = this;
        super.onCreate();
    }
}