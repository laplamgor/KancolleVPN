package xyz.hexene.localvpn;

/**
 * Created by root on 15-12-15.
 */

import com.socks.library.KLog;

import android.content.Context;
import android.util.Log;

//import com.webeye.android.weproxy.WeProxyManager;

import java.io.IOException;


public class WebEyeProxyManager implements Runnable {
    private static final String TAG = WebEyeProxyManager.class.getSimpleName();
    private LocalVPNService vpnService;

    public WebEyeProxyManager(LocalVPNService vpnService) {
        this.vpnService = vpnService;
        //for test
        vpnService.setWeProxyAvailability(true);
        vpnService.setWeProxyHost("120.25.148.196");
        vpnService.setWeProxyPort(443);
    }


    /**
     * WebEyeProxyManager
     * Call from Application.onTerminate(), which is not guaranteed to ever be called.
     */
    public void onTerminate() {
        vpnService.setWeProxyAvailability(false);
        //WeProxyManager.getInstance().enableWebeyeProxy(false);
    }

    /*
        // Webeye proxy changed listener.
        private WeProxyManager.WebeyeProxyAvailabilityChangedListener mWeProxyChangedListener =
                new WeProxyManager.WebeyeProxyAvailabilityChangedListener() {

                    @Override
                    public void onAvailabilityChanged(boolean enabled, String host, int port) {
                        KLog.d(TAG, "onAvailabilityChanged, enabled: " + enabled + ", host:" + host + ", port:" + port);

                        vpnService.setWeProxyAvailability(enabled);

                        if (enabled) {
                            vpnService.setWeProxyHost(host);
                            vpnService.setWeProxyPort(port);
                        }
                    }
                };
    */
    @Override
    public void run() {
        KLog.i(TAG, "Started");
        try {
            // Init webeye proxy
            //WeProxyManager.getInstance().init(vpnService, true, mWeProxyChangedListener, null);
            //WeProxyManager.getInstance().enableWebeyeProxy(true);

            Thread currentThread = Thread.currentThread();
            while (!currentThread.isInterrupted()) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            KLog.w(TAG, "Stopping");
        } finally {
            onTerminate();
        }

        KLog.i("stopped run");
    }
}
