/*
** Copyright 2015, Mohamed Naufal
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package xyz.hexene.localvpn;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import android.util.Log;

import com.socks.library.KLog;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.Selector;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;

public class LocalVPNService extends VpnService {
    public static final String ACTION_VPN_RUNNING = "com.webeye.adbapp.vpn.VPN_STATE";
    public static final String ACTION_PROXY_CHANGED = "com.webeye.adbapp.vpn.PROXY_CHANGED";
    public static final String ACTION_CLOSE_VPN = "com.webeye.adbapp.vpn.CLOSE_VPN";

    public static final String EXTRA_PROXY_STATE = "proxy_state";
    public static final String EXTRA_PROXY_HOST = "proxy_host";
    public static final String EXTRA_PROXY_PORT = "proxy_port";
    private static final String TAG = LocalVPNService.class.getSimpleName();
    private static final String VPN_ADDRESS = "10.0.0.2"; // Only IPv4 support for now
    private static int SLEEP_TIME = 10;
    private static boolean isRunning = false;
    private static boolean mWeProxyAvailability;
    private ParcelFileDescriptor vpnInterface = null;
    private PendingIntent pendingIntent;

    private final BroadcastReceiver mCloseVpnListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            KLog.d(TAG, intent.getAction());
            isRunning = false;
            executorService.shutdownNow();
            cleanup();
            stopSelf();
        }
    };

    private ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue;
    private ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue;
    private ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue;
    private LinkedBlockingQueue<byte[]> kancolleAPIQueue;
    private ExecutorService executorService;
    private Selector udpSelector;
    private Selector tcpSelector;
    private Context context;

    public static boolean isRunning() {
        return isRunning;
    }

    // TODO: Move this to a "utils" class for reuse
    private static void closeResources(Closeable... resources) {
        for (Closeable resource : resources) {
            try {
                resource.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    public static boolean getWeProxyAvailability() {
        return mWeProxyAvailability;
    }

    public static void setWeProxyAvailability(boolean proxyAvailability) {
        mWeProxyAvailability = proxyAvailability;
    }

    @Override
    public void onCreate() {
        Log.i(TAG, "onCreate");

        super.onCreate();
        isRunning = true;
        setupVPN();

        try {
            udpSelector = Selector.open();
            tcpSelector = Selector.open();
            deviceToNetworkUDPQueue = new ConcurrentLinkedQueue<>();
            deviceToNetworkTCPQueue = new ConcurrentLinkedQueue<>();
            networkToDeviceQueue = new ConcurrentLinkedQueue<>();
            kancolleAPIQueue = new LinkedBlockingQueue<>();

            int nThreads = 6;
            // TODO: 15-12-15 use weproxy config
            executorService = Executors.newFixedThreadPool(nThreads);

            //executorService.submit(new UDPInput(networkToDeviceQueue, udpSelector));
            //executorService.submit(new UDPOutput(deviceToNetworkUDPQueue, udpSelector, this));
            executorService.submit(new TCPInput(networkToDeviceQueue,kancolleAPIQueue, tcpSelector));
            executorService.submit(new TCPOutput(deviceToNetworkTCPQueue, networkToDeviceQueue, kancolleAPIQueue, tcpSelector, this));

            executorService.submit(new VPNRunnable(vpnInterface.getFileDescriptor(), deviceToNetworkUDPQueue, deviceToNetworkTCPQueue, networkToDeviceQueue));
            executorService.submit(new VPNOutput(vpnInterface.getFileDescriptor(), networkToDeviceQueue));

            executorService.submit(new Kancolle(kancolleAPIQueue,this));

            sendBroadcast(new Intent(ACTION_VPN_RUNNING).putExtra("running", true));
            KLog.i(TAG, "sendBroadcast " + ACTION_VPN_RUNNING);
        } catch (IOException e) {
            // TODO: Here and elsewhere, we should explicitly notify the user of any errors
            // and suggest that they stop the service, since we can't do it ourselves
            Log.e(TAG, "Error starting service", e);
            cleanup();
        }
    }

    private void setupVPN() {
        if (vpnInterface == null) {
            Intent statusActivityIntent = new Intent(this, LocalVPN.class);
            pendingIntent = PendingIntent.getActivity(this, 0, statusActivityIntent, 0);

            Builder builder = new Builder();
            builder.addAddress(VPN_ADDRESS, 32);
            builder.addDnsServer("8.8.8.8");
            //builder.addRoute("0.0.0.0",0);
//            builder.addRoute("203.0.0.0",8);
//            builder.addRoute("202.0.0.0",8);
            builder.addRoute("125.6.189.7", 32);
/*
            for (int i = 1; i < 8; i++) {
                builder.addRoute(i + ".0.0.0", 8);
                i += 1;
            }
            builder.addRoute("8.0.0.0", 7);
            for (int i = 9; i < 127; i++) {
                builder.addRoute(i + ".0.0.0", 8);
                i += 1;
            }
            for (int i = 128; i < 224; i++) {
                builder.addRoute(i + ".0.0.0", 8);
                i += 1;
            }
*/
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                KLog.i(TAG, "allowFamily AF_INET");
                builder.allowFamily(OsConstants.AF_INET);
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    builder.addAllowedApplication("com.dmm.dmmlabo.kancolle");
                    //String selfPackage = getPackageName();
                    //KLog.d("filter package: " + selfPackage);
                    //builder.addDisallowedApplication(selfPackage);
                }
            } catch (PackageManager.NameNotFoundException e) {
                KLog.e(TAG, e.toString());
            }

            vpnInterface = builder.setSession(getApplicationInfo().name).setConfigureIntent(pendingIntent).establish();

            IntentFilter filter = new IntentFilter(ACTION_PROXY_CHANGED);
            filter = new IntentFilter(ACTION_CLOSE_VPN);
            registerReceiver(this.mCloseVpnListener, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        KLog.i(TAG, "onDestroy");
        super.onDestroy();
        isRunning = false;
        executorService.shutdownNow();
        cleanup();
        unregisterReceiver(mCloseVpnListener);
        KLog.i(TAG, "Stopped");
    }

    private void cleanup() {
        KLog.i(TAG, "cleanup");
        deviceToNetworkTCPQueue = null;
        deviceToNetworkUDPQueue = null;
        networkToDeviceQueue = null;
        ByteBufferPool.clear();
        closeResources(udpSelector, tcpSelector, vpnInterface);
    }

    private static class VPNRunnable implements Runnable {
        private static final String TAG = VPNRunnable.class.getSimpleName();

        private FileDescriptor vpnFileDescriptor;

        private ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue;
        private ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue;
        private ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue;

        public VPNRunnable(FileDescriptor vpnFileDescriptor,
                           ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue,
                           ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue,
                           ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue) {
            this.vpnFileDescriptor = vpnFileDescriptor;
            this.deviceToNetworkUDPQueue = deviceToNetworkUDPQueue;
            this.deviceToNetworkTCPQueue = deviceToNetworkTCPQueue;
            this.networkToDeviceQueue = networkToDeviceQueue;
        }

        @Override
        public void run() {
            KLog.i(TAG, "Started");

            FileChannel vpnInput = new FileInputStream(vpnFileDescriptor).getChannel();
            //FileChannel vpnOutput = new FileOutputStream(vpnFileDescriptor).getChannel();

            try {
                ByteBuffer bufferToNetwork = null;
                ByteBuffer bufferFromNetwork;
                boolean dataSent = true;
                boolean dataReceived = false;

                while (!Thread.interrupted()) {
                    if (dataSent)
                        bufferToNetwork = ByteBufferPool.acquire();
                    else
                        bufferToNetwork.clear();

                    // TODO: Block when not connected
                    int readBytes = vpnInput.read(bufferToNetwork);
                    if (readBytes > 0) {
                        dataSent = true;
                        bufferToNetwork.flip();
                        Packet packet = new Packet(bufferToNetwork);

                        if (packet.isUDP()) {
                            deviceToNetworkUDPQueue.offer(packet);
                        } else if (packet.isTCP()) {
                            deviceToNetworkTCPQueue.offer(packet);
                        } else {
                            KLog.w(TAG, "Unknown packet = " + packet.ip4Header.toString());
                            dataSent = false;
                        }
                    } else {
                        dataSent = false;
                    }
                    // TODO: Sleep-looping is not very battery-friendly, consider blocking instead
                    // Confirm if throughput with ConcurrentQueue is really higher compared to BlockingQueue
                    if (!dataSent) {
                        Thread.sleep(SLEEP_TIME);
                    }
                }
            } catch (InterruptedException e) {
                KLog.i(TAG, "Stopping");
            } catch (Exception e) {
                Log.e(TAG, e.toString(), e);
            } finally {
                closeResources(vpnInput);
                KLog.i("stopped run");
            }
        }
    }

    private static class VPNOutput implements Runnable {
        private static final String TAG = VPNOutput.class.getSimpleName();

        private FileDescriptor vpnFileDescriptor;
        private ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue;

        public VPNOutput(FileDescriptor vpnFileDescriptor,
                         ConcurrentLinkedQueue<ByteBuffer> networkToDeviceQueue) {
            this.vpnFileDescriptor = vpnFileDescriptor;
            this.networkToDeviceQueue = networkToDeviceQueue;
        }

        @Override
        public void run() {
            KLog.i(TAG, "Started");

            ByteBuffer bufferFromNetwork;
            boolean dataReceived = false;
            FileChannel vpnOutput = new FileOutputStream(vpnFileDescriptor).getChannel();

            try {
                while (!Thread.interrupted()) {
                    try {
                        bufferFromNetwork = networkToDeviceQueue.poll();
                        if (bufferFromNetwork != null) {
                            bufferFromNetwork.flip();
                            while (bufferFromNetwork.hasRemaining()) {
                                vpnOutput.write(bufferFromNetwork);
                            }
                            dataReceived = true;
                            ByteBufferPool.release(bufferFromNetwork);
                        } else {
                            dataReceived = false;
                        }
                    } catch (IOException e) {
                        Log.e(TAG, e.toString(), e);
                    }

                    if (!dataReceived) {
                        Thread.sleep(SLEEP_TIME);
                    }
                }
            } catch (InterruptedException e) {
                KLog.i(TAG, "Stopping");
            } catch (Exception e) {
                Log.e(TAG, e.toString(), e);
            } finally {
                closeResources(vpnOutput);
                KLog.i("stopped run");
            }
        }
    }
}
