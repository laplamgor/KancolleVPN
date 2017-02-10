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
import java.nio.channels.DatagramChannel;
import java.util.Iterator;
import java.util.Map;


/**
 * Transmission Control Block
 */
public class UDB {
    private static final int MAX_CACHE_SIZE = 500;
    private static final long MAX_WAIT_DATA_TIME = 60 * 1000;
    public String ipAndPort;
    public DatagramChannel channel;
    public Packet referencePacket;
    public long readlen;
    public long writelen;
    public int curNum;
    private long lastDataExTime;
    private static LRUCache<String, UDB> udbCache =
            new LRUCache<>(MAX_CACHE_SIZE, new LRUCache.CleanupCallback<String, UDB>() {
                @Override
                public void cleanup(Map.Entry<String, UDB> eldest) {
                    KLog.d(eldest.getValue().curNum + " cleanup = " + eldest.getKey() + " readLen = " + eldest.getValue().readlen + " writelen = " + eldest.getValue().writelen);
                    eldest.getValue().closeChannel();
                }

                public boolean canCleanup(Map.Entry<String, UDB> eldest) {
                    boolean ret = false;
                    if (System.currentTimeMillis() - eldest.getValue().lastDataExTime > MAX_WAIT_DATA_TIME) {
                        ret = true;
                        KLog.d(eldest.getKey() + " canCleanup lastDataExTime > " + MAX_WAIT_DATA_TIME);
                    }

                    return ret;
                }
            });

    public UDB(String ipAndPort, DatagramChannel channel, Packet referencePacket) {
        this.ipAndPort = ipAndPort;
        this.channel = channel;
        this.referencePacket = referencePacket;

        this.lastDataExTime = System.currentTimeMillis();
    }

    public static UDB getUDB(String ipAndPort) {
        synchronized (udbCache) {
            //KLog.d("key = " + ipAndPort);
            return udbCache.get(ipAndPort);
        }
    }

    public static void putUDB(String ipAndPort, UDB udb) {
        synchronized (udbCache) {
            udb.curNum = udbCache.size();
            KLog.d(udbCache.size() + " key = " + ipAndPort);
            udbCache.put(ipAndPort, udb);
        }
    }

    public static void closeUDB(UDB udb) {
        KLog.d(udb.curNum + " key = " + udb.ipAndPort + " readLen = " + udb.readlen + " writelen = " + udb.writelen);
        udb.closeChannel();
        synchronized (udbCache) {
            udbCache.remove(udb.ipAndPort);
        }
    }

    public static void closeAll() {
        KLog.d("closeAll");
        synchronized (udbCache) {
            int index = 0;
            Iterator<Map.Entry<String, UDB>> it = udbCache.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, UDB> item = it.next();
                KLog.d("close " + ++index + ": " + item.getKey() + " readLen = " + item.getValue().readlen + " writelen = " + item.getValue().writelen);
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

    public void refreshDataEXTime() {
        this.lastDataExTime = System.currentTimeMillis();
    }
}
