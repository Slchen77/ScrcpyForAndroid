package org.client.scrcpy;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import org.client.scrcpy.utils.PreUtils;
import org.client.scrcpy.utils.Progress;
import org.client.scrcpy.utils.ThreadUtils;
import org.client.scrcpy.utils.Util;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

public class MainActivity extends Activity implements Scrcpy.ServiceCallbacks {

    public final static String START_REMOTE = "start_remote_headless";

    private boolean headlessMode = false;
    private int screenWidth;
    private int screenHeight;
    private boolean landscape = false;
    private boolean first_time = true;
    private boolean serviceBound = false;
    private boolean resumeScrcpy = false;

    private SendCommands sendCommands;
    private int videoBitrate;
    private int delayControl;
    private Context context;
    private String serverAdr = null;
    private SurfaceView surfaceView;
    private Surface surface;
    private Scrcpy scrcpy;
    private long timestamp = 0;
    private LinearLayout linearLayout;
    private int errorCount = 0;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            scrcpy = ((Scrcpy.MyServiceBinder) iBinder).getService();
            scrcpy.setServiceCallbacks(MainActivity.this);
            serviceBound = true;
            if (first_time) {
                if (!Progress.isShowing()) {
                    Progress.showDialog(MainActivity.this, getString(R.string.please_wait));
                }
                scrcpy.start(surface, Scrcpy.LOCAL_IP + ":" + Scrcpy.LOCAL_FORWART_PORT,
                        screenHeight, screenWidth, delayControl);
                ThreadUtils.workPost(() -> {
                    int count = 50;
                    while (count > 0 && !scrcpy.check_socket_connection()) {
                        count--;
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    int finalCount = count;
                    ThreadUtils.post(() -> {
                        Progress.closeDialog();
                        if (finalCount == 0) {
                            if (serviceBound) {
                                showMainView();
                            }
                            Toast.makeText(context, "Connection Timed out 2", Toast.LENGTH_SHORT).show();
                        } else {
                            first_time = false;
                            set_display_nd_touch();
                            connectSuccessExt();
                        }
                    });
                });
            } else {
                scrcpy.setParms(surface, screenWidth, screenHeight);
                set_display_nd_touch();
                connectSuccessExt();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            serviceBound = false;
        }
    };

    private void showMainView() {
        showMainView(false);
    }

    private void showMainView(boolean userDisconnect) {
        if (scrcpy != null) {
            scrcpy.StopService();
        }
        try {
            unbindService(serviceConnection);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (surface != null) {
            surface = null;
        }
        if (surfaceView != null) {
            surfaceView = null;
        }
        serviceBound = false;
        scrcpy_main();

        if (scrcpy != null) {
            scrcpy = null;
        }
        connectExitExt(userDisconnect);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.context = this;
        if (savedInstanceState != null) {
            first_time = savedInstanceState.getBoolean("first_time");
            landscape = savedInstanceState.getBoolean("landscape");
            headlessMode = savedInstanceState.getBoolean("headlessMode");
            resumeScrcpy = savedInstanceState.getBoolean("resumeScrcpy");
            screenHeight = savedInstanceState.getInt("screenHeight");
            screenWidth = savedInstanceState.getInt("screenWidth");
        }
        landscape = getApplication().getResources().getConfiguration().orientation
                != Configuration.ORIENTATION_PORTRAIT;
        if (first_time) {
            scrcpy_main();
        } else {
            Log.e("Scrcpy: ", "from onCreate");
            start_screen_copy_magic();
        }

        if (savedInstanceState != null) {
            Log.i("Scrcpy", "outState: " + savedInstanceState.getBoolean("from_save_instance"));
        }
        if (savedInstanceState == null || !savedInstanceState.getBoolean("from_save_instance", false)) {
            if (getIntent() != null && getIntent().getExtras() != null) {
                headlessMode = getIntent().getExtras().getBoolean(START_REMOTE, headlessMode);
            }
        }
        if (headlessMode && first_time) {
            getAttributes();
            connectScrcpyServer(PreUtils.get(this, Constant.CONTROL_REMOTE_ADDR, ""));
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        Log.i("Scrcpy", "enter onSaveInstanceState");
        outState.putBoolean("from_save_instance", true);
        outState.putBoolean("first_time", first_time);
        outState.putBoolean("landscape", landscape);
        outState.putBoolean("headlessMode", headlessMode);
        outState.putInt("screenHeight", screenHeight);
        outState.putInt("screenWidth", screenWidth);
    }

    @SuppressLint("SourceLockedOrientationActivity")
    public void scrcpy_main() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.getWindow().setStatusBarColor(getColor(R.color.status_bar));
        } else {
            this.getWindow().setStatusBarColor(getResources().getColor(R.color.status_bar));
        }
        final View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(View.VISIBLE);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        landscape = false;
        setContentView(R.layout.activity_main);

        Button connectButton = findViewById(R.id.button_connect_device);
//        TextView languageButton = findViewById(R.id.);

        sendCommands = new SendCommands();

        connectButton.setOnClickListener(v -> showConnectDialog());

//        languageButton.setOnClickListener(v -> switchLanguage());
    }

    private void showConnectDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_connect, null);
        builder.setView(dialogView);

        EditText editTextIp = dialogView.findViewById(R.id.editText_ip);
        Button confirmButton = dialogView.findViewById(R.id.button_confirm_connect);
        ImageView historyButton = dialogView.findViewById(R.id.button_history);

        String lastIp = PreUtils.get(context, Constant.CONTROL_REMOTE_ADDR, "");
        if (TextUtils.isEmpty(lastIp)) {
            String[] historyList = getHistoryList();
            if (historyList.length > 0) {
                editTextIp.setText(historyList[0]);
            }
        } else {
            editTextIp.setText(lastIp);
        }

        AlertDialog dialog = builder.create();

        confirmButton.setOnClickListener(v -> {
            String ipAddress = editTextIp.getText().toString().trim();
            if (!TextUtils.isEmpty(ipAddress)) {
                serverAdr = ipAddress;
                saveHistory(serverAdr);
                PreUtils.put(context, Constant.CONTROL_REMOTE_ADDR, serverAdr);
                getAttributes();
                connectScrcpyServer(serverAdr);
                dialog.dismiss();
            } else {
                Toast.makeText(context, R.string.ip_address_empty, Toast.LENGTH_SHORT).show();
            }
        });

        historyButton.setOnClickListener(v -> {
            editTextIp.clearFocus();
            showListPopupWindow(editTextIp);
        });

        dialog.show();
    }

    private void showListPopupWindow(EditText mEditText) {
        String[] list = getHistoryList();
        if (list.length == 0) {
            list = new String[]{"127.0.0.1"};
        }
        final ListPopupWindow listPopupWindow;
        listPopupWindow = new ListPopupWindow(this);
        listPopupWindow.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, list));
        listPopupWindow.setAnchorView(mEditText);
        listPopupWindow.setModal(true);
        listPopupWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        String[] finalList = list;
        listPopupWindow.setOnItemClickListener((adapterView, view, i, l) -> {
            mEditText.setText(finalList[i]);
            listPopupWindow.dismiss();
        });
        listPopupWindow.show();
    }

    private void switchLanguage() {
        Resources resources = getResources();
        Configuration config = resources.getConfiguration();
        DisplayMetrics dm = resources.getDisplayMetrics();
        if (config.locale.getLanguage().equals("zh")) {
            config.setLocale(Locale.ENGLISH);
        } else {
            config.setLocale(Locale.SIMPLIFIED_CHINESE);
        }
        resources.updateConfiguration(config, dm);
        recreate();
    }


    @SuppressLint("ClickableViewAccessibility")
    public void set_display_nd_touch() {
        DisplayMetrics metrics = new DisplayMetrics();
        if (ViewConfiguration.get(context).hasPermanentMenuKey()) {
            getWindowManager().getDefaultDisplay().getMetrics(metrics);
        } else {
            final Display display = getWindowManager().getDefaultDisplay();
            display.getRealMetrics(metrics);
        }

        float this_dev_height = linearLayout.getHeight();
        float this_dev_width = linearLayout.getWidth();

        int[] rem_res = scrcpy.get_remote_device_resolution();
        int remote_device_height = rem_res[1];
        int remote_device_width = rem_res[0];
        float remote_device_aspect_ratio = (float) remote_device_height / remote_device_width;

        if (!landscape) {
            float this_device_aspect_ratio = this_dev_height / this_dev_width;
            if (remote_device_aspect_ratio > this_device_aspect_ratio) {
                float wantWidth = this_dev_height / remote_device_aspect_ratio;
                int padding = (int) (this_dev_width - wantWidth) / 2;
                linearLayout.setPadding(padding, 0, padding, 0);
            } else if (remote_device_aspect_ratio < this_device_aspect_ratio) {
                linearLayout.setPadding(0, (int) 0, 0, (int) (((this_device_aspect_ratio - remote_device_aspect_ratio) / 2 * this_dev_width)));
            }

        } else {
            float this_device_aspect_ratio = this_dev_width / this_dev_height;
            if (remote_device_aspect_ratio > this_device_aspect_ratio) {
                float wantHeight = this_dev_width / remote_device_aspect_ratio;
                int padding = (int) (this_dev_height - wantHeight) / 2;
                linearLayout.setPadding(0, padding, 0, padding);
            } else if (remote_device_aspect_ratio < this_device_aspect_ratio) {
                linearLayout.setPadding(((int) (((this_device_aspect_ratio - remote_device_aspect_ratio) * this_dev_height)) / 2), 0, ((int) (((this_device_aspect_ratio - remote_device_aspect_ratio) * this_dev_height)) / 2), 0);
            }
        }
        surfaceView.setOnTouchListener((view, event) -> scrcpy.touchevent(event, landscape, surfaceView.getWidth(), surfaceView.getHeight()));
    }

    private void getAttributes() {
        // Default values
        screenHeight = 1280;
        screenWidth = 720;
        videoBitrate = 2 * 1024 * 1024; // 6Mbps
        delayControl = 0;
    }

    private String[] getHistoryList() {
        String historyList = PreUtils.get(context, Constant.HISTORY_LIST_KEY, "");
        if (TextUtils.isEmpty(historyList)) {
            return new String[]{};
        }
        try {
            JSONArray historyJson = new JSONArray(historyList);
            String[] retList = new String[historyJson.length()];
            for (int i = 0; i < historyJson.length(); i++) {
                retList[i] = historyJson.get(i).toString();
            }
            return retList;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new String[]{};
    }

    private boolean saveHistory(String device) {
        if (headlessMode) {
            return false;
        }
        JSONArray historyJson = new JSONArray();
        String[] historyList = getHistoryList();
        if (historyList.length == 0) {
            historyJson.put(device);
        } else {
            try {
                historyJson.put(0, device);
            } catch (JSONException e) {
                e.printStackTrace();
            }
            int count = Math.min(historyList.length, 30);
            for (int i = 0; i < count; i++) {
                if (!historyList[i].equals(device)) {
                    historyJson.put(historyList[i]);
                }
            }
        }
        try {
            return PreUtils.put(context, Constant.HISTORY_LIST_KEY, historyJson.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private void swapDimensions() {
        int temp = screenHeight;
        screenHeight = screenWidth;
        screenWidth = temp;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void start_screen_copy_magic() {
        setContentView(R.layout.surface);
        final View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        surfaceView = findViewById(R.id.decoder_surface);
        surface = surfaceView.getHolder().getSurface();
        linearLayout = findViewById(R.id.container1);

        ImageButton btnDisconnect = findViewById(R.id.btn_disconnect);
        btnDisconnect.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("断开连接")
                    .setMessage("确定要断开当前投屏连接吗？")
                    .setPositiveButton("断开", (dialog, which) -> {
                        // 核心断开逻辑
                        scrcpy.pause();
                        resumeScrcpy = true;
                        showMainView(true);
                        first_time = true;
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        start_Scrcpy_service();
    }

    private void start_Scrcpy_service() {
        Intent intent = new Intent(this, Scrcpy.class);
        startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @SuppressLint("SourceLockedOrientationActivity")
    @Override
    public void loadNewRotation() {
        if (first_time) {
            first_time = false;
        }
        try {
            unbindService(serviceConnection);
        } catch (Exception e) {
            e.printStackTrace();
        }
        serviceBound = false;
        landscape = !landscape;
        swapDimensions();
        if (landscape) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
        }
    }

    @Override
    public void errorDisconnect() {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.disconnect))
                .setMessage(getString(R.string.disconnect_ask))
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (serviceBound) {
                        showMainView();
                        first_time = true;
                    } else {
                        MainActivity.this.finish();
                    }
                })
                .setCancelable(false)
                .show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (serviceBound) {
            scrcpy.pause();
            resumeScrcpy = true;
            showMainView(true);
            first_time = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!first_time) {
            final View decorView = getWindow().getDecorView();
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            if (serviceBound) {
                linearLayout = findViewById(R.id.container1);
                scrcpy.resume();
            }
        }
        if (resumeScrcpy) {
            resumeScrcpy = false;
            connectScrcpyServer(PreUtils.get(context, Constant.CONTROL_REMOTE_ADDR, ""));
        }
    }

    @Override
    public void onBackPressed() {
        if (timestamp == 0) {
            if (serviceBound) {
                timestamp = SystemClock.uptimeMillis();
                Toast.makeText(context, "Press again to exit", Toast.LENGTH_SHORT).show();
            } else {
                finish();
            }
        } else {
            long now = SystemClock.uptimeMillis();
            if (now < timestamp + 1000) {
                timestamp = 0;
                if (serviceBound) {
                    showMainView(true);
                    first_time = true;
                    errorCount = 0;
                } else {
                    finish();
                }
            }
            timestamp = 0;
        }
    }

    private void connectScrcpyServer(String serverAdr) {
        if (!TextUtils.isEmpty(serverAdr)) {
            String[] serverInfo = Util.getServerHostAndPort(serverAdr);
            String serverHost = serverInfo[0];
            int serverPort = Integer.parseInt(serverInfo[1]);
            int localForwardPort = Scrcpy.LOCAL_FORWART_PORT;

            Progress.showDialog(MainActivity.this, getString(R.string.please_wait));
            ThreadUtils.workPost(() -> {
                AssetManager assetManager = getAssets();
                Log.d("Scrcpy", "File scrcpy-server.jar try write");
                try {
                    InputStream input_Stream = assetManager.open("scrcpy-server.jar");
                    byte[] buffer = new byte[input_Stream.available()];
                    input_Stream.read(buffer);
                    File scrcpyDir = context.getExternalFilesDir("scrcpy");
                    if (!scrcpyDir.exists()) {
                        scrcpyDir.mkdirs();
                    }
                    FileOutputStream outputStream = new FileOutputStream(new File(
                            context.getExternalFilesDir("scrcpy"), "scrcpy-server.jar"
                    ));
                    outputStream.write(buffer);
                    outputStream.flush();
                    outputStream.close();
                } catch (IOException e) {
                    Log.d("Scrcpy", "File scrcpy-server.jar write faild");
                }
                if (sendCommands.SendAdbCommands(context, serverHost,
                        serverPort,
                        localForwardPort,
                        Scrcpy.LOCAL_IP,
                        videoBitrate, Math.max(screenHeight, screenWidth)) == 0) {
                    ThreadUtils.post(() -> {
                        if (!MainActivity.this.isFinishing()) {
                            Log.e("Scrcpy: ", "from startButton");
                            start_screen_copy_magic();
                        }
                    });
                } else {
                    ThreadUtils.post(Progress::closeDialog);
                    Toast.makeText(context, "Network OR ADB connection failed", Toast.LENGTH_SHORT).show();
                    connectExitExt();
                }
            });
        } else {
            Toast.makeText(context, "Server Address Empty", Toast.LENGTH_SHORT).show();
            connectExitExt();
        }
    }

    protected void connectSuccessExt() {
        errorCount = 0;
    }

    protected void connectExitExt() {
        this.connectExitExt(false);
    }

    protected void connectExitExt(boolean userDisconnect) {
        if (!userDisconnect) {
            errorCount += 1;
            Log.i("Scrcpy", "连接错误次数: " + errorCount);
            if (errorCount >= 3) {
                App.startAdbServer();
            }
        }
        if (headlessMode && !resumeScrcpy) {
            if (!userDisconnect) {
                new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.connect_faild))
                        .setMessage(getString(R.string.connect_faild_ask))
                        .setPositiveButton(R.string.retry, (dialog, which) -> {
                            connectScrcpyServer(PreUtils.get(context, Constant.CONTROL_REMOTE_ADDR, ""));
                        })
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                            finishAndRemoveTask();
                        })
                        .show();
            } else {
                finishAndRemoveTask();
            }
        }
    }
}
