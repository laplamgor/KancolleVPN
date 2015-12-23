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

import android.util.Log;

import com.socks.library.KLog;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

class UDPOutput implements Runnable
{
    private static final String TAG = UDPOutput.class.getSimpleName();

    private LocalVPNService vpnService;
    private ConcurrentLinkedQueue<Packet> inputQueue;
    private Selector selector;

    public UDPOutput(ConcurrentLinkedQueue<Packet> inputQueue, Selector selector, LocalVPNService vpnService)
    {
        this.inputQueue = inputQueue;
        this.selector = selector;
        this.vpnService = vpnService;
    }

    @Override
    public void run()
    {
        KLog.i(TAG, "Started");
        try
        {
            Packet currentPacket;
            UDB udb;
            DatagramChannel outputChannel;
            Thread currentThread = Thread.currentThread();
            while (true)
            {
                // TODO: Block when not connected
                do
                {
                    currentPacket = inputQueue.poll();
                    //KLog.i(TAG, "looptest udpoutput");
                    if (currentPacket != null)
                        break;
                    Thread.sleep(10);
                } while (!currentThread.isInterrupted());

                if (currentThread.isInterrupted()) {
                    KLog.w(TAG, "currentThread.isInterrupted !!!");
                    break;
                }

                ByteBuffer payloadBuffer = currentPacket.backingBuffer;
                currentPacket.backingBuffer = null;

                InetAddress destinationAddress = currentPacket.ip4Header.destinationAddress;
                int destinationPort = currentPacket.udpHeader.destinationPort;
                int sourcePort = currentPacket.udpHeader.sourcePort;

                String ipAndPort = destinationAddress.getHostAddress() + ":" + destinationPort + ":" + sourcePort;
                //KLog.i(TAG, "ipAndPort = " + ipAndPort);

                udb = UDB.getUDB(ipAndPort);
                if (udb == null) {
                    outputChannel = DatagramChannel.open();
                    vpnService.protect(outputChannel.socket());

                    try
                    {
                        outputChannel.socket().setReceiveBufferSize(65535);
                        outputChannel.socket().setSendBufferSize(65535);
                        if (destinationPort == 80 && vpnService.getWeProxyAvailability()) {
                            KLog.d(TAG, ipAndPort + " use proxy " + vpnService.getWeProxyHost() + ":" + vpnService.getWeProxyPort());
                            outputChannel.connect(new InetSocketAddress(vpnService.getWeProxyHost(), vpnService.getWeProxyPort()));
                        } else {
                            outputChannel.connect(new InetSocketAddress(destinationAddress, destinationPort));
                        }
                    }
                    catch (IOException e)
                    {
                        KLog.e(TAG, ipAndPort + " Connection error: " + e.toString());
                        closeChannel(outputChannel);
                        ByteBufferPool.release(payloadBuffer);
                        continue;
                    }

                    if (!outputChannel.isConnected()){
                        KLog.e(TAG, ipAndPort + " isConnected fail!!!");
                        closeChannel(outputChannel);
                        ByteBufferPool.release(payloadBuffer);
                        continue;
                    }

                    currentPacket.swapSourceAndDestination();

                    udb = new UDB(ipAndPort, outputChannel, currentPacket);
                    UDB.putUDB(ipAndPort, udb);

                    outputChannel.configureBlocking(false);
                    selector.wakeup();
                    outputChannel.register(selector, SelectionKey.OP_READ, udb);
                }

                synchronized (udb){
                    outputChannel = udb.channel;
                    try
                    {
                        while (payloadBuffer.hasRemaining())
                            outputChannel.write(payloadBuffer);
                    }
                    catch (IOException e)
                    {
                        KLog.e(TAG, ipAndPort + " write error: " + e.toString());

                        ByteBufferPool.release(payloadBuffer);
                        UDB.closeUDB(udb);
                        continue;
                    }
                    udb.refreshDataEXTime();

                    udb.writelen += payloadBuffer.position();
                }
                //KLog.d(TAG, ipAndPort + " writeBytes = " + payloadBuffer.position());
                ByteBufferPool.release(payloadBuffer);
            }
        }
        catch (InterruptedException e)
        {
            KLog.w(TAG, e.toString());
        }
        catch (IOException e)
        {
            Log.e(TAG, e.toString(), e);
        }
        finally
        {
            UDB.closeAll();
            KLog.i("stopped run");
        }
    }

    private void closeChannel(DatagramChannel channel)
    {
        //KLog.i(TAG, "closeChannel");
        try
        {
            channel.close();
        }
        catch (IOException e)
        {
            KLog.e(TAG, "closeChannel " + e.toString());
        }
    }
}
