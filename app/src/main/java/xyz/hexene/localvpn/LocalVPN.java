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

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.net.VpnService;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import com.socks.library.KLog;


public class LocalVPN extends ActionBarActivity {
    private static final int VPN_REQUEST_CODE = 0x0F;
    private static final String TAG = LocalVPNService.class.getSimpleName();

    private boolean waitingForVPNStart;
    private Intent localServiceIntent;

    private final BroadcastReceiver vpnStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (LocalVPNService.ACTION_VPN_RUNNING.equals(intent.getAction())) {
                KLog.i(TAG, "onReceive running = " + intent.getBooleanExtra("running", false));
                if (intent.getBooleanExtra("running", false)) {
                    waitingForVPNStart = false;
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        KLog.init(BuildConfig.LOG_DEBUG);
        KLog.i(TAG, "onCreate");

        setContentView(R.layout.activity_local_vpn);
        final Button vpnButton = (Button) findViewById(R.id.vpn);
        vpnButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (LocalVPNService.isRunning()) {
                    StopVpn();
                } else {
                    startVPN();
                }
            }
        });
        waitingForVPNStart = false;
        registerReceiver(vpnStateReceiver,
                new IntentFilter(LocalVPNService.ACTION_VPN_RUNNING));

        createFloatView();
    }

    @Override
    protected void onDestroy() {
        KLog.i(TAG, "onDestroy");

        //StopVpn();

        // Close webeye proxy.
        //AdblockWeProxyManager.getInstance().enableWebeyeProxy(false);

        unregisterReceiver(vpnStateReceiver);

        super.onDestroy();
    }

    private void startVPN() {
        KLog.i(TAG, "startVPN");
        Intent vpnIntent = VpnService.prepare(this);
        if (vpnIntent != null)
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
        else
            onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null);
    }

    private void StopVpn() {
        KLog.i(TAG, "StopVpn");
        //stopService(localServiceIntent);
        Intent intent = new Intent();
        intent.setAction(LocalVPNService.ACTION_CLOSE_VPN);
        sendBroadcast(intent);
        enableButton(true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        KLog.i(TAG, "onActivityResult");
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            waitingForVPNStart = true;
            localServiceIntent = new Intent(this, LocalVPNService.class);
            startService(localServiceIntent);
            enableButton(false);
        }
    }

    @Override
    protected void onResume() {
        KLog.i(TAG, "onResume waitingForVPNStart = " + waitingForVPNStart + " LocalVPNService.isRunning = " + LocalVPNService.isRunning());

        super.onResume();

        enableButton(!waitingForVPNStart && !LocalVPNService.isRunning());
    }

    private void enableButton(boolean enable) {
        final Button vpnButton = (Button) findViewById(R.id.vpn);
        vpnButton.setEnabled(true);
        if (enable) {
            vpnButton.setText(R.string.start_vpn);
        } else {
            vpnButton.setText(R.string.stop_vpn);
        }
    }

    @SuppressLint("NewApi")
    private void createFloatView() {
        final ImageView imageView = new ImageView(this);
        imageView.setImageResource(R.drawable.ic_launcher);

        final WindowManager windowManager = (WindowManager) getApplicationContext().getSystemService(
                Context.WINDOW_SERVICE);
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams();

// 设置window type 这是关键
        params.type = WindowManager.LayoutParams.TYPE_TOAST;
/* 如果设置为params.type = WindowManager.LayoutParams.TYPE_PHONE;
* 那么优先级会降低一些, 即拉下通知栏不可见^_^ */

        params.format = PixelFormat.RGBA_8888; // 设置图片格式，效果为背景透明

// 设置Window flag
        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;

// 设置悬浮窗的长得宽
        params.width = 1;
        params.height = 1;
        params.x = 10;
        params.y = 10;

// 设置悬浮窗的Touch监听
        imageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(LocalVPN.this, "Click", Toast.LENGTH_SHORT).show();
            }
        });
        windowManager.addView(imageView, params);
    }
}
