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
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.support.v4.content.LocalBroadcastManager;
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

public class LocalVPNService extends VpnService
{
    private static final String TAG = LocalVPNService.class.getSimpleName();
    private static final String VPN_ADDRESS = "10.0.0.2"; // Only IPv4 support for now
    private static final String VPN_ROUTE = "0.0.0.0"; // Intercept everything

    public static final String BROADCAST_VPN_STATE = "xyz.hexene.localvpn.VPN_STATE";

    private static boolean isRunning = false;

    private ParcelFileDescriptor vpnInterface = null;
    private PendingIntent pendingIntent;

    private static final boolean isUseWeProxy = false;
    private boolean mWeProxyAvailability;
    private String mWeProxyHost;
    private int mWeProxyPort;

    private ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue;
    private ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue;
    private ConcurrentLinkedQueue<ByteBuffer> networkUDPToDeviceQueue;
    private ConcurrentLinkedQueue<ByteBuffer> networkTCPToDeviceQueue;

    private ExecutorService executorService;

    private Selector udpSelector;
    private Selector tcpSelector;

    @Override
    public void onCreate()
    {
        super.onCreate();
        isRunning = true;
        setupVPN();
        try
        {
            udpSelector = Selector.open();
            tcpSelector = Selector.open();
            deviceToNetworkUDPQueue = new ConcurrentLinkedQueue<>();
            deviceToNetworkTCPQueue = new ConcurrentLinkedQueue<>();
            networkUDPToDeviceQueue = new ConcurrentLinkedQueue<>();
            networkTCPToDeviceQueue = new ConcurrentLinkedQueue<>();

            int nThreads = 5;
            // TODO: 15-12-15 use weproxy config
            if (isUseWeProxy){
                nThreads++;
            }

            executorService = Executors.newFixedThreadPool(nThreads);

            if (isUseWeProxy) {
                executorService.submit(new WebEyeProxyManager(this));
            }
            executorService.submit(new UDPInput(networkUDPToDeviceQueue, udpSelector));
            executorService.submit(new UDPOutput(deviceToNetworkUDPQueue, udpSelector, this));
            executorService.submit(new TCPInput(networkTCPToDeviceQueue, tcpSelector));
            executorService.submit(new TCPOutput(deviceToNetworkTCPQueue, networkTCPToDeviceQueue, tcpSelector, this));
            executorService.submit(new VPNRunnable(vpnInterface.getFileDescriptor(),
                    deviceToNetworkUDPQueue, deviceToNetworkTCPQueue, networkUDPToDeviceQueue, networkTCPToDeviceQueue));
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(BROADCAST_VPN_STATE).putExtra("running", true));
            Log.i(TAG, "Started");
        }
        catch (IOException e)
        {
            // TODO: Here and elsewhere, we should explicitly notify the user of any errors
            // and suggest that they stop the service, since we can't do it ourselves
            Log.e(TAG, "Error starting service", e);
            cleanup();
        }
    }

    private void setupVPN()
    {
        if (vpnInterface == null)
        {
            Intent statusActivityIntent = new Intent(this, LocalVPN.class);
            pendingIntent = PendingIntent.getActivity(this, 0, statusActivityIntent, 0);

            Builder builder = new Builder();
            builder.addAddress(VPN_ADDRESS, 32);
            builder.addRoute(VPN_ROUTE, 0);
            vpnInterface = builder.setSession(getString(R.string.app_name)).setConfigureIntent(pendingIntent).establish();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        return START_STICKY;
    }

    public static boolean isRunning()
    {
        return isRunning;
    }

    @Override
    public void onDestroy()
    {
        super.onDestroy();
        isRunning = false;
        executorService.shutdownNow();
        cleanup();
        KLog.i(TAG, "Stopped");
    }

    private void cleanup()
    {
        KLog.i(TAG, "cleanup");
        deviceToNetworkTCPQueue = null;
        deviceToNetworkUDPQueue = null;
        networkUDPToDeviceQueue = null;
        networkTCPToDeviceQueue = null;
        ByteBufferPool.clear();
        closeResources(udpSelector, tcpSelector, vpnInterface);
    }

    // TODO: Move this to a "utils" class for reuse
    private static void closeResources(Closeable... resources)
    {
        for (Closeable resource : resources)
        {
            try
            {
                resource.close();
            }
            catch (IOException e)
            {
                // Ignore
            }
        }
    }

    public void setWeProxyAvailability(boolean proxyAvailability) { mWeProxyAvailability = proxyAvailability; }
    public boolean getWeProxyAvailability() { return mWeProxyAvailability; }

    public void setWeProxyHost(String host) { mWeProxyHost = host; }
    public String getWeProxyHost() { return mWeProxyHost; }

    public void setWeProxyPort(int port) { mWeProxyPort = port; }
    public int getWeProxyPort() { return mWeProxyPort; }

    private static class VPNRunnable implements Runnable
    {
        private static final String TAG = VPNRunnable.class.getSimpleName();

        private FileDescriptor vpnFileDescriptor;

        private ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue;
        private ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue;
        private ConcurrentLinkedQueue<ByteBuffer> networkUDPToDeviceQueue;
        private ConcurrentLinkedQueue<ByteBuffer> networkTCPToDeviceQueue;

        public VPNRunnable(FileDescriptor vpnFileDescriptor,
                           ConcurrentLinkedQueue<Packet> deviceToNetworkUDPQueue,
                           ConcurrentLinkedQueue<Packet> deviceToNetworkTCPQueue,
                           ConcurrentLinkedQueue<ByteBuffer> networkUDPToDeviceQueue,
                           ConcurrentLinkedQueue<ByteBuffer> networkTCPToDeviceQueue)
        {
            this.vpnFileDescriptor = vpnFileDescriptor;
            this.deviceToNetworkUDPQueue = deviceToNetworkUDPQueue;
            this.deviceToNetworkTCPQueue = deviceToNetworkTCPQueue;
            this.networkUDPToDeviceQueue = networkUDPToDeviceQueue;
            this.networkTCPToDeviceQueue = networkTCPToDeviceQueue;
        }

        @Override
        public void run()
        {
            KLog.i(TAG, "Started");

            FileChannel vpnInput = new FileInputStream(vpnFileDescriptor).getChannel();
            FileChannel vpnOutput = new FileOutputStream(vpnFileDescriptor).getChannel();

            try
            {
                ByteBuffer bufferToNetwork = null;
                boolean dataSent = true;
                boolean dataUDPReceived = false;
                boolean dataTCPReceived = false;
                while (!Thread.interrupted())
                {
                    if (dataSent)
                        bufferToNetwork = ByteBufferPool.acquire();
                    else
                        bufferToNetwork.clear();

                    //if (!vpnInput.isOpen()){
                    //    KLog.e(TAG, "vpnInput is close !!!");
                    //}
                    // TODO: Block when not connected
                    int readBytes = vpnInput.read(bufferToNetwork);
                    if (readBytes > 0)
                    {
                        dataSent = true;
                        bufferToNetwork.flip();
                        Packet packet = new Packet(bufferToNetwork);

                        //KLog.i(TAG, packet.toString());

                        if (packet.isUDP())
                        {
                            deviceToNetworkUDPQueue.offer(packet);
                        }
                        else if (packet.isTCP())
                        {
                            deviceToNetworkTCPQueue.offer(packet);
                        }
                        else
                        {
                            KLog.w(TAG, "Unknown packet = " + packet.ip4Header.toString());
                            dataSent = false;
                        }
                    }
                    else
                    {
                        dataSent = false;
                    }

                    //if (!vpnOutput.isOpen()){
                    //    KLog.e(TAG, "vpnOutput is close !!!");
                    //}

                    try {
                        ByteBuffer bufferFromNetwork = networkTCPToDeviceQueue.poll();
                        if (bufferFromNetwork != null && vpnOutput.isOpen()) {
                            bufferFromNetwork.flip();

                            //Packet packet = new Packet(bufferFromNetwork);
                            //KLog.i(TAG, "repo " + packet.toString());

                            while (bufferFromNetwork.hasRemaining()) {
                                vpnOutput.write(bufferFromNetwork);
                            }
                            dataTCPReceived = true;
                            ByteBufferPool.release(bufferFromNetwork);
                        } else {
                            dataTCPReceived = false;
                        }
                    }
                    catch (IOException e) {
                        Log.e(TAG, e.toString(), e);
                    }

                    try {
                        ByteBuffer bufferFromNetwork = networkUDPToDeviceQueue.poll();
                        if (bufferFromNetwork != null && vpnOutput.isOpen()) {
                            bufferFromNetwork.flip();
                            while (bufferFromNetwork.hasRemaining()) {
                                vpnOutput.write(bufferFromNetwork);
                            }
                            dataUDPReceived = true;

                            ByteBufferPool.release(bufferFromNetwork);
                        } else {
                            dataUDPReceived = false;
                        }
                    }
                    catch (IOException e) {
                        Log.e(TAG, e.toString(), e);
                    }

                    // TODO: Sleep-looping is not very battery-friendly, consider blocking instead
                    // Confirm if throughput with ConcurrentQueue is really higher compared to BlockingQueue
                    if (!dataSent && !dataUDPReceived && !dataTCPReceived) {
                        Thread.sleep(10);
                    }
                }
            }
            catch (InterruptedException e)
            {
                KLog.i(TAG, "Stopping");
            }
            catch (IOException e)
            {
                Log.e(TAG, e.toString(), e);
            }
            finally
            {
                closeResources(vpnInput, vpnOutput);
                KLog.i("stopped run");
            }
        }
    }
}
