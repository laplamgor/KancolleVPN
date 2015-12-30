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

import com.socks.library.KLog;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Map;

/**
 * Transmission Control Block
 */
public class TCB {
    private static final int MAX_CACHE_SIZE = 500; // XXX: Is this ideal?
    private static final long MAX_WAIT_ACK_TIME = 2 * 60 * 1000;//zhangjie add 2015.12.10
    public String ipAndPort;
    public long mySequenceNum, theirSequenceNum;
    public long myAcknowledgementNum, theirAcknowledgementNum;
    public TCBStatus status;
    public long readDataTime;
    public long readlen;
    public int curNum;
    public Packet referencePacket;

    public SocketChannel channel;
    public boolean waitingForNetworkData;
    public SelectionKey selectionKey;
    private long lastDataExTime;
    private static LRUCache<String, TCB> tcbCache =
            new LRUCache<>(MAX_CACHE_SIZE, new LRUCache.CleanupCallback<String, TCB>() {
                @Override
                public void cleanup(Map.Entry<String, TCB> eldest) {
                    KLog.d(eldest.getValue().curNum + " cleanup = " + eldest.getKey() + " st = " + eldest.getValue().status + " readLen = " + eldest.getValue().readlen);
                    eldest.getValue().closeChannel();
                }

                public boolean canCleanup(Map.Entry<String, TCB> eldest) {
                    boolean ret = false;
                    TCBStatus status = eldest.getValue().status;

                    if (status == TCBStatus.CLOSE_WAIT || status == TCBStatus.LAST_ACK) {
                        ret = true;
                        KLog.d(eldest.getKey() + " canCleanup st = " + status);
                    } else if (System.currentTimeMillis() - eldest.getValue().lastDataExTime > MAX_WAIT_ACK_TIME) {
                        //zhangjie add 2015.12.10
                        ret = true;
                        KLog.d(eldest.getKey() + " canCleanup lastDataExTime > " + MAX_WAIT_ACK_TIME);
                    }

                    return ret;
                }
            });

    public TCB(String ipAndPort, long mySequenceNum, long theirSequenceNum, long myAcknowledgementNum, long theirAcknowledgementNum,
               SocketChannel channel, Packet referencePacket) {
        this.ipAndPort = ipAndPort;

        this.mySequenceNum = mySequenceNum;
        this.theirSequenceNum = theirSequenceNum;
        this.myAcknowledgementNum = myAcknowledgementNum;
        this.theirAcknowledgementNum = theirAcknowledgementNum;

        this.channel = channel;
        this.referencePacket = referencePacket;
        this.lastDataExTime = System.currentTimeMillis();
    }

    public static TCB getTCB(String ipAndPort) {
        synchronized (tcbCache) {
            //KLog.d("key = " + ipAndPort);
            return tcbCache.get(ipAndPort);
        }
    }

    public static void putTCB(String ipAndPort, TCB tcb) {
        synchronized (tcbCache) {
            tcb.curNum = tcbCache.size();
            KLog.d(tcbCache.size() + " key = " + ipAndPort);
            tcbCache.put(ipAndPort, tcb);
        }
    }

    public static void closeTCB(TCB tcb) {
        KLog.d(tcb.curNum + " key = " + tcb.ipAndPort + " st = " + tcb.status + " readLen = " + tcb.readlen);
        tcb.closeChannel();
        synchronized (tcbCache) {
            tcbCache.remove(tcb.ipAndPort);
        }
    }

    public static void closeAll() {
        KLog.d("closeAll");
        synchronized (tcbCache) {
            int index = 0;
            Iterator<Map.Entry<String, TCB>> it = tcbCache.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, TCB> item = it.next();
                KLog.d("close " + ++index + ": " + item.getKey() + " st = " + item.getValue().status + " readLen = " + item.getValue().readlen);
                item.getValue().closeChannel();
                it.remove();
            }
        }
    }

    private void closeChannel() {
        try {
            channel.close();
        } catch (IOException e) {
            KLog.e("closeChannel ", e.toString());
        }
    }

    //zhangjie add 2015.12.11 for removeEldestEntry
    public void refreshDataEXTime() {
        this.lastDataExTime = System.currentTimeMillis();
    }

    // TCP has more states, but we need only these
    public enum TCBStatus {
        SYN_SENT,
        SYN_RECEIVED,
        ESTABLISHED,
        CLOSE_WAIT,
        LAST_ACK,
    }

}
