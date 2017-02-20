package xyz.hexene.localvpn;

import android.annotation.TargetApi;

import com.socks.library.KLog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

/**
 * Created by mangoking on 2017/2/13.
 */

class httpPacket {
    static final int HTTP_NULL = 0;
    static final int HTTP_HEAD = 1;
    static final int HTTP_BODY = 2;
    byte[] httpPacketBuffer = null;
    private byte[] httpHeaderBuffer = null;
    private byte[] httpBodyBuffer = null;
    private byte[] httpChunkBuffer = null;
    int httpPacketStatus = HTTP_NULL;
    private int httpContentLength = -1;
    private int httpHeaderLength = -1;
    private boolean isChunked = false;
    private int curChuckLength = -1;
    private int sumChunckLength = -1;

    int processClient(byte[] packet){
        if(!processHttp(packet))
            return 0;
        if (isChunked) {
            httpPacketBuffer = processUnzip(httpChunkBuffer);
            KLog.i("process client  finish: "+new String(httpPacketBuffer));
            return 1;
        }
        else {
            //httpPacketBuffer = httpHeaderBuffer;
            //httpPacketBuffer = appendByteArray(httpPacketBuffer,httpBodyBuffer);
            KLog.i("process client  finish: "+new String(httpPacketBuffer));
            httpPacketBuffer = Arrays.copyOfRange(httpPacketBuffer,0,httpHeaderLength+httpContentLength);
            return 1;
        }
    }

    int processServer(byte[] packet){
        if(!processHttp(packet))
            return 0;
        if (isChunked) {
            httpPacketBuffer = processUnzip(httpChunkBuffer);
            httpPacketBuffer = Arrays.copyOfRange(httpPacketBuffer,7,httpPacketBuffer.length);
            //httpPacketBuffer = httpChunkBuffer;
            KLog.i("chunk length: "+sumChunckLength+" "+httpPacketBuffer.length);
            KLog.i("process server finish chunk: "+new String(httpPacketBuffer));
            return 1;
        }
        else {
            //httpPacketBuffer = httpHeaderBuffer;
            //httpPacketBuffer = appendByteArray(httpPacketBuffer,httpBodyBuffer);
            httpPacketBuffer = Arrays.copyOfRange(httpPacketBuffer,0,httpHeaderLength+httpContentLength);
            KLog.i("process server finish plain: "+new String(httpPacketBuffer));
            return 1;
        }
    }

    private boolean processHttp(byte[] packet) {
        if( httpPacketStatus == httpPacket.HTTP_NULL){
            httpPacketBuffer = null;
            httpHeaderBuffer = null;
            httpBodyBuffer = null;
            httpChunkBuffer = null;
            httpPacketStatus = httpPacket.HTTP_HEAD;
            httpContentLength = -1;
            httpHeaderLength = -1;
            curChuckLength = -1;
            sumChunckLength = 0;
            isChunked = false;
        }
        if( httpPacketStatus == httpPacket.HTTP_HEAD) {
            httpPacketBuffer = appendByteArray(httpPacketBuffer,packet);
            if (httpHeaderLength == -1) {
                httpHeaderLength = getHeaderLength();
            }
            if (httpHeaderLength != -1) {
                httpPacketStatus = httpPacket.HTTP_BODY;
                isChunked = getChunkedInfo();
                if (!isChunked) {
                    httpContentLength = getContentLength();
                }
                setHttpHeaderBuffer();
                KLog.d("Header: "+new String(httpHeaderBuffer));
                if (httpContentLength != -1 || isChunked) {
                    if (isChunked){
                        httpPacketBuffer = Arrays.copyOfRange(httpPacketBuffer,httpHeaderLength,httpPacketBuffer.length);
                        return processChuck();
                    }
                    else {
                        if (httpContentLength+httpHeaderLength <= httpPacketBuffer.length)
                            return true;
                    }
                }
                else {
                    httpContentLength = 0;
                    return true;
                }
            }
        }
        else {
            httpPacketBuffer = appendByteArray(httpPacketBuffer,packet);
            if (isChunked){
                return processChuck();
            }
            else {
                if (httpContentLength+httpHeaderLength <= httpPacketBuffer.length)
                    return true;
            }
        }
        return false;
    }

    void clear(){
        httpPacketBuffer = null;
        httpPacketStatus = httpPacket.HTTP_NULL;
    }

    int getHeaderLength(){
        final byte[] pattern = new byte[]{0x0d,0x0a,0x0d,0x0a};
        for(int i = 0; i < httpPacketBuffer.length ; i++) {
            int j;
            for (j = 0; i + j < httpPacketBuffer.length && j < pattern.length; j++) {
                if (httpPacketBuffer[i + j] != pattern[j]) {
                    break;
                }
            }
            if (j == pattern.length) {
                return i+j;
            }
        }
        return -1;
    }

    @TargetApi(19)
    int getContentLength(){
        final byte[] pattern = "Content-Length: ".getBytes(StandardCharsets.US_ASCII);
        for(int i = 0; i < httpPacketBuffer.length ; i++) {
            int j;
            for (j = 0; i + j < httpPacketBuffer.length && j < pattern.length; j++) {
                if (httpPacketBuffer[i + j] != pattern[j]) {
                    break;
                }
            }
            if (j == pattern.length) {
                int k;
                for (k = 0; i + j + k < httpPacketBuffer.length; k++) {
                    if (httpPacketBuffer[i + j + k] == 0x0d) break;
                }
                if (i + j + k != httpPacketBuffer.length) {
                    return Integer.parseInt(new String(Arrays.copyOfRange(httpPacketBuffer, i + j, i + j + k)));
                }
            }
        }
        return -1;
    }

    private boolean getChunkedInfo(){
        final byte[] pattern = "Transfer-Encoding: chunked".getBytes();
        for( int i = 0 ; i < httpHeaderLength -  pattern.length; i++){
            int j;
            for( j = 0; j < pattern.length; j++ ){
                if (httpPacketBuffer[i+j] != pattern[j])
                    break;
            }
            if (j==pattern.length){
                return true;
            }
        }
        return false;
    }

    private void setHttpHeaderBuffer(){
        httpHeaderBuffer = Arrays.copyOfRange(httpPacketBuffer,0,httpHeaderLength);
    }

    private byte[] appendByteArray(byte[] dest, byte[] src){
        if (dest == null)
            dest = new byte[0];
        byte[] tmp = new byte[dest.length + src.length];
        System.arraycopy(dest, 0, tmp, 0, dest.length);
        System.arraycopy(src, 0, tmp, dest.length, src.length);
       return tmp;
    }

    private byte[] appendByteArray(byte[] dest, byte[] src, int len){
        if (dest == null)
            dest = new byte[0];
        byte[] tmp = new byte[dest.length + len];
        System.arraycopy(dest, 0, tmp, 0, dest.length);
        System.arraycopy(src, 0, tmp, dest.length, len);
        return tmp;
    }

    private boolean processChuck(){
        while (true){
            if (curChuckLength == -1){
                int i;
                for (i = 0;i < httpPacketBuffer.length-1; i++) {
                    if (httpPacketBuffer[i] == 0x0a) break;
                }
                if (i == httpPacketBuffer.length-1) {
                    break;
                }
                //KLog.d("http packet buffer: "+new String(httpPacketBuffer));
                curChuckLength = Integer.parseInt(new String(Arrays.copyOfRange(httpPacketBuffer, 0, i-1)), 16)+2;
                KLog.d("curChunkLength: "+curChuckLength);
                sumChunckLength += curChuckLength-2;
                httpPacketBuffer = Arrays.copyOfRange(httpPacketBuffer,i+1,httpPacketBuffer.length);
                if (curChuckLength == 2){
                    return true;
                }
            }
            KLog.d("curChuckLength: "+curChuckLength+" httpPacketBuffer.length "+httpPacketBuffer.length);
            if (httpPacketBuffer.length<curChuckLength){
                httpChunkBuffer = appendByteArray(httpChunkBuffer,httpPacketBuffer);
                curChuckLength -= httpPacketBuffer.length;
                httpPacketBuffer = new byte[0];
                return false;
            }
            else {
                httpChunkBuffer = appendByteArray(httpChunkBuffer,httpPacketBuffer,curChuckLength);
                httpPacketBuffer = Arrays.copyOfRange(httpPacketBuffer,curChuckLength,httpPacketBuffer.length);
                httpChunkBuffer = Arrays.copyOf(httpChunkBuffer,httpChunkBuffer.length-2);
                curChuckLength = -1;
            }
        }
        return false;
    }

    byte[] processUnzip(byte[] zipArray) {
        ByteArrayInputStream bytein;
        GZIPInputStream gzin;
        ByteArrayOutputStream byteout = new ByteArrayOutputStream();
        int res = 0;
        byte[] buffer = new byte[1024];
        try {
            bytein = new ByteArrayInputStream(zipArray);
            gzin = new GZIPInputStream(bytein);
            res = 0;
            while(res>=0&&gzin.available()>0){
                res = gzin.read(buffer);
                if(res>0){
                    byteout.write(buffer,0,res);
                }
            }
            return byteout.toByteArray();
        }
        catch (Exception e){
            KLog.e("Got "+e);
            if(res>0){
                byteout.write(buffer,0,res);
            }
            return byteout.toByteArray();
        }
    }

    public void processKancolleAPI(){

    }
}
