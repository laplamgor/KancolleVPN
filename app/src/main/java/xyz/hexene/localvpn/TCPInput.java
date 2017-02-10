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
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import xyz.hexene.localvpn.TCB.TCBStatus;

class TCPInput implements Runnable {
    private static final String TAG = TCPInput.class.getSimpleName();
    private static final int HEADER_SIZE = Packet.IP4_HEADER_SIZE + Packet.TCP_HEADER_SIZE;

    private ConcurrentLinkedQueue<ByteBuffer> outputQueue;
    private Selector selector;

    public TCPInput(ConcurrentLinkedQueue<ByteBuffer> outputQueue, Selector selector) {
        this.outputQueue = outputQueue;
        this.selector = selector;
    }

    @Override
    public void run() {
        KLog.i(TAG, "Started");

        try {
            while (!Thread.interrupted()) {
                int readyChannels = selector.select();
                if (readyChannels == 0) {
                    Thread.sleep(10);
                    continue;
                }
                //KLog.d(TAG, "readyChannels = " + readyChannels);

                Set<SelectionKey> keys = selector.selectedKeys();
                Iterator<SelectionKey> keyIterator = keys.iterator();

                while (keyIterator.hasNext() && !Thread.interrupted()) {
                    SelectionKey key = keyIterator.next();
                    if (key.isValid()) {
                        if (key.isConnectable())
                            processConnect(key, keyIterator);
                        else if (key.isReadable())
                            processInput(key, keyIterator);
                        else
                            KLog.w(TAG, "unknow key type!!!");
                    }
                }
            }
        } catch (InterruptedException e) {
            KLog.w(TAG, "Stopping!!!");
        } catch (Exception e) {
            Log.e(TAG, e.toString(), e);
        } finally {
            KLog.i("stopped run");
        }
    }

    private void processConnect(SelectionKey key, Iterator<SelectionKey> keyIterator) {
        TCB tcb = (TCB) key.attachment();
        synchronized (tcb) {

            Packet referencePacket = tcb.referencePacket;
            try {
                if (tcb.channel.finishConnect()) {

                    //zhangjie add 2015.12.11
                    tcb.refreshDataEXTime();

                    keyIterator.remove();
                    tcb.status = TCBStatus.SYN_RECEIVED;

                    // TODO: Set MSS for receiving larger packets from the device
                    ByteBuffer responseBuffer = ByteBufferPool.acquire();
                    referencePacket.updateTCPBuffer(responseBuffer, (byte) (Packet.TCPHeader.SYN | Packet.TCPHeader.ACK),
                            tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);

                    KLog.d(TAG, tcb.ipAndPort + " TCP netToDevice SYN|ACK");

                    outputQueue.offer(responseBuffer);

                    tcb.mySequenceNum++; // SYN counts as a byte
                    key.interestOps(SelectionKey.OP_READ);
                } else {
                    KLog.w(TAG, tcb.ipAndPort + " not finishConnect!!!");
                }
            } catch (IOException e) {
                KLog.e(TAG, tcb.ipAndPort + " Connection error: " + e.toString());
                ByteBuffer responseBuffer = ByteBufferPool.acquire();
                referencePacket.updateTCPBuffer(responseBuffer, (byte) Packet.TCPHeader.RST, 0, tcb.myAcknowledgementNum, 0);

                KLog.w(TAG, tcb.ipAndPort + " TCP netToDevice RST");

                outputQueue.offer(responseBuffer);
                TCB.closeTCB(tcb);//maybe change zhangjie 2015.12.8
            }
        }
    }

    private void processInput(SelectionKey key, Iterator<SelectionKey> keyIterator) {
        keyIterator.remove();

        ByteBuffer receiveBuffer = ByteBufferPool.acquire();
        // Leave space for the header
        receiveBuffer.position(HEADER_SIZE);

        TCB tcb = (TCB) key.attachment();
        synchronized (tcb) {
            //KLog.d(TAG, tcb.ipAndPort + " st = " + tcb.status);

            //zhangjie add 2015.12.11
            tcb.refreshDataEXTime();

            Packet referencePacket = tcb.referencePacket;
            SocketChannel inputChannel = (SocketChannel) key.channel();
            int readBytes;
            try {
                readBytes = inputChannel.read(receiveBuffer);
            } catch (IOException e) {
                KLog.e(TAG, tcb.ipAndPort + " Network read error: " + e.toString());

                if (tcb.status == TCBStatus.CLOSE_WAIT || tcb.status == TCBStatus.LAST_ACK) {
                    KLog.w(TAG, tcb.ipAndPort + " closeTCB st = " + tcb.status);
                    ByteBufferPool.release(receiveBuffer);
                } else {
                    referencePacket.updateTCPBuffer(receiveBuffer, (byte) Packet.TCPHeader.RST, 0, tcb.myAcknowledgementNum, 0);
                    KLog.w(TAG, tcb.ipAndPort + " TCP netToDevice RST");
                    outputQueue.offer(receiveBuffer);
                }

                TCB.closeTCB(tcb);
                return;
            }

            if (readBytes == -1) {
                // End of stream, stop waiting until we push more data
                key.interestOps(0);
                tcb.waitingForNetworkData = false;

                if (tcb.status != TCBStatus.CLOSE_WAIT) {
                    if ((tcb.readDataTime > 0) && (System.currentTimeMillis() - tcb.readDataTime > 30 * 1000)) {
                        KLog.d(TAG, tcb.ipAndPort + " st = " + tcb.status + " readDataTime > 30*1000");
                        TCB.closeTCB(tcb);
                        return;
                    } else {
                        KLog.d(TAG, tcb.ipAndPort + " st = " + tcb.status + " release receiveBuffer");
                        //ByteBufferPool.release(receiveBuffer);
                        //return;
                        tcb.status = TCBStatus.LAST_ACK;
                        referencePacket.updateTCPBuffer(receiveBuffer, (byte)( Packet.TCPHeader.FIN | Packet.TCPHeader.ACK), tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
                        tcb.mySequenceNum++; // FIN counts as a byte
                        KLog.d(TAG, tcb.ipAndPort + " TCP netToDevice FIN");
                        outputQueue.offer(receiveBuffer);
                        return;
                    }
                }

                tcb.status = TCBStatus.LAST_ACK;
                referencePacket.updateTCPBuffer(receiveBuffer, (byte) Packet.TCPHeader.FIN, tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
                tcb.mySequenceNum++; // FIN counts as a byte
                KLog.d(TAG, tcb.ipAndPort + " TCP netToDevice FIN");
            } else {
                tcb.readDataTime = System.currentTimeMillis();
                tcb.readlen += readBytes;

                // XXX: We should ideally be splitting segments by MTU/MSS, but this seems to work without
                referencePacket.updateTCPBuffer(receiveBuffer, (byte) (Packet.TCPHeader.PSH | Packet.TCPHeader.ACK),
                        tcb.mySequenceNum, tcb.myAcknowledgementNum, readBytes);
                tcb.mySequenceNum += readBytes; // Next sequence number
                receiveBuffer.position(HEADER_SIZE + readBytes);
                KLog.d(TAG, tcb.ipAndPort + " TCP netToDevice PSH|ACK readBytes = " + readBytes);
            }
        }

        outputQueue.offer(receiveBuffer);
    }
}
