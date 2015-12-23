package xyz.hexene.localvpn;

/**
 * Created by root on 15-12-15.
 */

import com.socks.library.KLog;
import com.webeye.android.weproxy.AdblockWeProxyManager;


class WebEyeProxyManager implements Runnable {
    private static final String TAG = WebEyeProxyManager.class.getSimpleName();
    private LocalVPNService vpnService;

    public WebEyeProxyManager(LocalVPNService vpnService) {
        this.vpnService = vpnService;
        //for test
        /*
        vpnService.setWeProxyAvailability(true);
        vpnService.setWeProxyHost("120.25.148.196");
        vpnService.setWeProxyPort(443);
        */
    }

    @Override
    public void run() {
        KLog.i(TAG, "Started");
        try {
            // Init webeye proxy
            AdblockWeProxyManager.getInstance().init(vpnService, true, new AdblockWeProxyManager.ChangeProxyListener() {
                @Override
                public void onChangeProxy(String host, int port) {
                    KLog.d(TAG, "onChangeProxy " + host + ":" + port);
                    //vpnService.setWeProxyAvailability(true);
                    vpnService.setWeProxyHost(host);
                    vpnService.setWeProxyPort(port);
                }
            }, new AdblockWeProxyManager.HitADBRuleListener() {
                @Override
                public void onHitRule(String host) {

                }
            });

            Thread currentThread = Thread.currentThread();
            while (!currentThread.isInterrupted()) {
                Thread.sleep(20*1000);
            }
        } catch (InterruptedException e) {
            KLog.w(TAG, "Stopping");
        } finally {
            KLog.i("stopped run");
        }
    }
}
