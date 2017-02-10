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
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

import xyz.hexene.localvpn.Packet.TCPHeader;
import xyz.hexene.localvpn.TCB.TCBStatus;

class TCPOutput implements Runnable {
    private static final String TAG = TCPOutput.class.getSimpleName();

    private static final int CONNECT_TIMEOUT = 10000 * 1000;

    private LocalVPNService vpnService;
    private ConcurrentLinkedQueue<Packet> inputQueue;
    private ConcurrentLinkedQueue<ByteBuffer> outputQueue;
    private Selector selector;

    private Random random = new Random();

    public TCPOutput(ConcurrentLinkedQueue<Packet> inputQueue, ConcurrentLinkedQueue<ByteBuffer> outputQueue,
                     Selector selector, LocalVPNService vpnService) {
        this.inputQueue = inputQueue;
        this.outputQueue = outputQueue;
        this.selector = selector;
        this.vpnService = vpnService;
    }

    @Override
    public void run() {
        KLog.i(TAG, "Started");

        try {
            Thread currentThread = Thread.currentThread();
            while (true) {
                Packet currentPacket;
                // TODO: Block when not connected
                do {
                    currentPacket = inputQueue.poll();
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
                ByteBuffer responseBuffer = ByteBufferPool.acquire();

                InetAddress destinationAddress = currentPacket.ip4Header.destinationAddress;

                TCPHeader tcpHeader = currentPacket.tcpHeader;
                int destinationPort = tcpHeader.destinationPort;
                int sourcePort = tcpHeader.sourcePort;

                String ipAndPort = destinationAddress.getHostAddress() + ":" +
                        destinationPort + ":" + sourcePort;

                TCB tcb = TCB.getTCB(ipAndPort);

                //zhangjie add 2015.12.11
                if (tcb != null) {
                    tcb.refreshDataEXTime();
                }

                if (tcb == null) {
                    initializeConnection(ipAndPort, destinationAddress, destinationPort,
                            currentPacket, tcpHeader, responseBuffer);
                } else if (tcpHeader.isSYN()) {
                    processDuplicateSYN(tcb, tcpHeader, responseBuffer);
                } else if (tcpHeader.isRST()) {
                    KLog.i(TAG, tcb.ipAndPort + " isRST" + " st = " + tcb.status + " readLen = " + tcb.readlen);
                    //closeCleanly(tcb, responseBuffer);
                    TCB.closeTCB(tcb);
                } else if (tcpHeader.isFIN()) {
                    processFIN(tcb, tcpHeader, responseBuffer);
                } else if (tcpHeader.isACK()) {
                    processACK(tcb, tcpHeader, payloadBuffer, responseBuffer);
                } else {
                    KLog.w("ipAndPort = " + ipAndPort + "->unknow type!!!");
                }
                // XXX: cleanup later
                if (responseBuffer.position() == 0) {
                    ByteBufferPool.release(responseBuffer);
                }

                ByteBufferPool.release(payloadBuffer);
            }
        } catch (InterruptedException e) {
            KLog.w(TAG, "Stopping");
        } catch (Exception e) {
            Log.e(TAG, e.toString(), e);
        } finally {
            TCB.closeAll();
            KLog.i("stopped run");
        }
    }

    private void initializeConnection(String ipAndPort, InetAddress destinationAddress, int destinationPort,
                                      Packet currentPacket, TCPHeader tcpHeader, ByteBuffer responseBuffer)
            throws IOException {
        KLog.i("initializeConnection "+tcpHeader.isSYN()+tcpHeader.isRST()+tcpHeader.isFIN()+tcpHeader.isACK());
        currentPacket.swapSourceAndDestination();
        if (tcpHeader.isSYN()) {
            SocketChannel outputChannel = SocketChannel.open();
            vpnService.protect(outputChannel.socket());
            outputChannel.configureBlocking(false);

            TCB tcb = new TCB(ipAndPort, random.nextInt(Short.MAX_VALUE + 1), tcpHeader.sequenceNumber, tcpHeader.sequenceNumber + 1,
                    tcpHeader.acknowledgementNumber, outputChannel, currentPacket);
            TCB.putTCB(ipAndPort, tcb);

            try {
                outputChannel.socket().setReceiveBufferSize(65535);
                outputChannel.socket().setSendBufferSize(65535);
                if (destinationPort == 80 && vpnService.getWeProxyAvailability()) {
                    KLog.d(TAG, ipAndPort + " use proxy " + vpnService.getWeProxyHost() + ":" + vpnService.getWeProxyPort());
                    outputChannel.connect(new InetSocketAddress(vpnService.getWeProxyHost(), vpnService.getWeProxyPort()));
                } else {
                    outputChannel.connect(new InetSocketAddress(destinationAddress, destinationPort));
                }

                tcb.status = TCBStatus.SYN_SENT;
                selector.wakeup();
                tcb.selectionKey = outputChannel.register(selector, SelectionKey.OP_CONNECT, tcb);
                //return;
            } catch (IOException e) {
                KLog.e(TAG, ipAndPort + " Connection error: " + e.toString());
                currentPacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.RST, 0, tcb.myAcknowledgementNum, 0);

                KLog.w(TAG, ipAndPort + " Connection netToDevice RST");
                outputQueue.offer(responseBuffer);
                TCB.closeTCB(tcb);//maybe change zhangjie 2015.12.8
                //return;
            }
        } else {
            //KLog.w(TAG, ipAndPort + " " + tcpHeader.toString());
            /* zhangjie 2015.12.9
            currentPacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.RST,
                    0, tcpHeader.sequenceNumber + 1, 0);
            KLog.w(TAG, ipAndPort + " TCP netToDevice RST");
            */
            //return;
        }

        //outputQueue.offer(responseBuffer);
    }

    private void processDuplicateSYN(TCB tcb, TCPHeader tcpHeader, ByteBuffer responseBuffer) {
        synchronized (tcb) {
            if (tcb.status == TCBStatus.SYN_SENT) {
                tcb.myAcknowledgementNum = tcpHeader.sequenceNumber + 1;
                return;
            }
        }
        sendRST(tcb, 1, responseBuffer);
    }

    private void processFIN(TCB tcb, TCPHeader tcpHeader, ByteBuffer responseBuffer) {
        synchronized (tcb) {
            KLog.d(TAG, tcb.ipAndPort + " FIN");
            Packet referencePacket = tcb.referencePacket;
            tcb.myAcknowledgementNum = tcpHeader.sequenceNumber + 1;
            tcb.theirAcknowledgementNum = tcpHeader.acknowledgementNumber;

            if (true) {
                tcb.status = TCBStatus.LAST_ACK;
                referencePacket.updateTCPBuffer(responseBuffer, (byte) (TCPHeader.FIN | TCPHeader.ACK),
                        tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
                tcb.mySequenceNum++; // FIN counts as a byte
                KLog.d(TAG, tcb.ipAndPort + " FIN netToDevice FIN|ACK");
                outputQueue.offer(responseBuffer);
                return;
            }

            if (tcb.waitingForNetworkData/* && tcb.readlen == 0*/) {
                tcb.status = TCBStatus.CLOSE_WAIT;
                referencePacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.ACK,
                        tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
                KLog.d(TAG, tcb.ipAndPort + " FIN netToDevice ACK");
            } else {
                tcb.status = TCBStatus.LAST_ACK;
                referencePacket.updateTCPBuffer(responseBuffer, (byte) (TCPHeader.FIN | TCPHeader.ACK),
                        tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
                tcb.mySequenceNum++; // FIN counts as a byte
                KLog.d(TAG, tcb.ipAndPort + " FIN netToDevice FIN|ACK");
            }
        }

        outputQueue.offer(responseBuffer);
    }

    private void processACK(TCB tcb, TCPHeader tcpHeader, ByteBuffer payloadBuffer, ByteBuffer responseBuffer) throws IOException {
        int payloadSize = payloadBuffer.limit() - payloadBuffer.position();

        synchronized (tcb) {
            SocketChannel outputChannel = tcb.channel;

            KLog.d(TAG, tcb.ipAndPort + " st = " + tcb.status + "; waitData = " + tcb.waitingForNetworkData + "; payload = " + payloadSize);

            switch (tcb.status) {
                case SYN_SENT:{
                    //connect还没有成功
                    sendRST(tcb, payloadSize, responseBuffer);
                }
                return;

                case SYN_RECEIVED: {
                    tcb.status = TCBStatus.ESTABLISHED;
                    //selector.wakeup();
                    //tcb.selectionKey = outputChannel.register(selector, SelectionKey.OP_READ, tcb);
                    tcb.waitingForNetworkData = true;
                }
                break;

                case LAST_ACK: {
                    //closeCleanly(tcb, responseBuffer);
                    TCB.closeTCB(tcb);
                    //KLog.d(TAG, tcb.ipAndPort + " ACK closeTCB st = " + tcb.status + " payloadSize = " + payloadSize);
                }
                return;

                default:
                    break;
            }

            if (payloadSize == 0) {
                return; // Empty ACK, ignore
            }

            if (!tcb.waitingForNetworkData) {
                //KLog.d(TAG, "st = " + tcb.status);
                //selector.wakeup();
                tcb.selectionKey.interestOps(SelectionKey.OP_READ);
                tcb.waitingForNetworkData = true;
            }

            selector.wakeup();
/*
            if (payloadSize == 0) {
                return; // Empty ACK, ignore
            }
*/
            // Forward to remote server
            try {
                while (payloadBuffer.hasRemaining()) {
                    outputChannel.write(payloadBuffer);
                }
            } catch (Exception e) {
                KLog.e(TAG, tcb.ipAndPort + " Network write error: " + e.toString());
                sendRST(tcb, payloadSize, responseBuffer);
                return;
            }

            // TODO: We don't expect out-of-order packets, but verify
            tcb.myAcknowledgementNum = tcpHeader.sequenceNumber + payloadSize;
            tcb.theirAcknowledgementNum = tcpHeader.acknowledgementNumber;
            Packet referencePacket = tcb.referencePacket;
            referencePacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.ACK, tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
            KLog.d(TAG, tcb.ipAndPort + " ACK netToDevice ACK st = " + tcb.status);
        }

        outputQueue.offer(responseBuffer);
    }

    private void sendRST(TCB tcb, int prevPayloadSize, ByteBuffer buffer) {
        synchronized (tcb) {
            tcb.referencePacket.updateTCPBuffer(buffer, (byte) TCPHeader.RST, 0, tcb.myAcknowledgementNum + prevPayloadSize, 0);

            KLog.d(TAG, tcb.ipAndPort + " RST netToDevice RST");

            outputQueue.offer(buffer);
            TCB.closeTCB(tcb);
        }
    }
}
