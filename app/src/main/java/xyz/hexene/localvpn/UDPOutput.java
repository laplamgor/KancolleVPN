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

public class UDPOutput implements Runnable
{
    private static final String TAG = UDPOutput.class.getSimpleName();

    private LocalVPNService vpnService;
    private ConcurrentLinkedQueue<Packet> inputQueue;
    private Selector selector;

    private static final int MAX_CACHE_SIZE = 500;
    private LRUCache<String, DatagramChannel> channelCache =
            new LRUCache<>(MAX_CACHE_SIZE, new LRUCache.CleanupCallback<String, DatagramChannel>()
            {
                @Override
                public void cleanup(Map.Entry<String, DatagramChannel> eldest)
                {
                    KLog.i(TAG, "channelCache cleanup ipAndPort = " + eldest.getKey());
                    closeChannel(eldest.getValue());
                }

                public boolean canCleanup(Map.Entry<String, DatagramChannel> eldest)
                {
                    return true;
                }
            });

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
            Thread currentThread = Thread.currentThread();
            while (true)
            {
                Packet currentPacket;
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

                InetAddress destinationAddress = currentPacket.ip4Header.destinationAddress;
                int destinationPort = currentPacket.udpHeader.destinationPort;
                int sourcePort = currentPacket.udpHeader.sourcePort;

                String ipAndPort = destinationAddress.getHostAddress() + ":" + destinationPort + ":" + sourcePort;
                //KLog.i(TAG, "ipAndPort = " + ipAndPort);

                DatagramChannel outputChannel = channelCache.get(ipAndPort);
                if (outputChannel == null) {
                    outputChannel = DatagramChannel.open();
                    vpnService.protect(outputChannel.socket());

                    try
                    {
                        outputChannel.connect(new InetSocketAddress(destinationAddress, destinationPort));
                    }
                    catch (IOException e)
                    {
                        KLog.e(TAG, "Connection error: " + ipAndPort + e.toString());
                        closeChannel(outputChannel);
                        ByteBufferPool.release(currentPacket.backingBuffer);
                        continue;
                    }

                    channelCache.put(ipAndPort, outputChannel);

                    outputChannel.configureBlocking(false);
                    currentPacket.swapSourceAndDestination();

                    selector.wakeup();
                    outputChannel.register(selector, SelectionKey.OP_READ, currentPacket);

                    KLog.i(TAG, "channelCache put ipAndPort = " + ipAndPort);
                }
                else {
                    KLog.i(TAG, "channelCache get ipAndPort = " + ipAndPort);
                }

                try
                {
                    ByteBuffer payloadBuffer = currentPacket.backingBuffer;
                    while (payloadBuffer.hasRemaining())
                        outputChannel.write(payloadBuffer);
                }
                catch (IOException e)
                {
                    KLog.e(TAG, "Network write error: " + ipAndPort + e.toString());
                    KLog.i(TAG, "channelCache remove ipAndPort = " + ipAndPort);
                    closeChannel(outputChannel);
                    channelCache.remove(ipAndPort);
                }
                ByteBufferPool.release(currentPacket.backingBuffer);
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
            closeAll();
        }
        KLog.i("stopped run");
    }

    private void closeAll()
    {
        KLog.i(TAG, "closeAll");
        int index = 0;
        Iterator<Map.Entry<String, DatagramChannel>> it = channelCache.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<String, DatagramChannel> item = it.next();
            KLog.i("close " + index++ +": "+ item.getKey());
            closeChannel(item.getValue());
            it.remove();
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
