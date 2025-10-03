package com.example.ecg.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.example.ecg.R;
import com.example.ecg.ai.MyTensor;
import com.example.ecg.model.ECGInfoResponse;
import com.example.ecg.model.ECGResponse;
import com.example.ecg.model.HRResponse;
import com.google.gson.Gson;
import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;
import com.movesense.mds.Mds;
import com.movesense.mds.MdsException;
import com.movesense.mds.MdsNotificationListener;
import com.movesense.mds.MdsResponseListener;
import com.movesense.mds.MdsSubscription;
import com.movesense.mds.internal.connectivity.MovesenseConnectedDevices;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ECGActivity extends AppCompatActivity implements CompoundButton.OnCheckedChangeListener {
    private static final int DEFAULT_SAMPLE_RATE = 125;
    static ECGActivity s_INSTANCE = null;
    private static final String LOG_TAG = ECGActivity.class.getSimpleName();

    public static final String SERIAL = "serial";
    String connectedSerial;

    private LineGraphSeries<DataPoint> mSeriesECG;
    private int mDataPointsAppended = 0;
    private MdsSubscription mECGSubscription;
    private MdsSubscription mHRSubscription;
    private MdsSubscription mAccSubscription;
    private MdsSubscription mGyroSubscription;
    private MdsSubscription mMagnSubscription;
    private MdsSubscription mIMUSubscription;

    // Response Models
    public class AccelerationResponse {
        public Body body;

        public class Body {
            public long timestamp;
            public ArrayData arrayAcc;

            public class ArrayData {
                public double[] x;
                public double[] y;
                public double[] z;
            }
        }
    }

    public class GyroscopeResponse {
        public Body body;

        public class Body {
            public long timestamp;
            public ArrayData arrayGyro;

            public class ArrayData {
                public double[] x;
                public double[] y;
                public double[] z;
            }
        }
    }

    public class MagnetometerResponse {
        public Body body;

        public class Body {
            public long timestamp;
            public ArrayData arrayMagn;

            public class ArrayData {
                public double[] x;
                public double[] y;
                public double[] z;
            }
        }
    }

    public class IMUResponse {
        public Body body;

        public class Body {
            public long timestamp;
            public ArrayData arrayAcc;
            public ArrayData arrayGyro;
            public ArrayData arrayMagn;

            public class ArrayData {
                public double[] x;
                public double[] y;
                public double[] z;
            }
        }
    }

    private int count = 0;
    private int[] intArr = new int[400];

    // Handler for delays
    private Handler handler = new Handler(Looper.getMainLooper());

    public static final String URI_EVENTLISTENER = "suunto://MDS/EventListener";
    public static final String SCHEME_PREFIX = "suunto://";

    public static final String URI_ECG_INFO = "/Meas/ECG/Info";
    public static final String URI_ECG_ROOT = "/Meas/ECG/";
    public static final String URI_MEAS_HR = "/Meas/HR";
    public static final String URI_LINEAR_ACCELERATION = "/Meas/Acc/";
    public static final String URI_GYROSCOPE = "/Meas/Gyro/";  // แก้ typo จาก /Maes/Gyro/
    public static final String URI_MAGNETOMETER = "/Meas/Magn/";
    public static final String URI_IMU = "/Meas/IMU/";

    Switch mSwitchECGEnabled;
    Spinner mSpinnerSampleRates;

    private ArrayAdapter<String> mSpinnerAdapter;
    private final List<String> mSpinnerRates = new ArrayList<>();

    private Mds getMDS() {
        return MainActivity.mMds;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        s_INSTANCE = this;

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ecg);

        mSwitchECGEnabled = (Switch) findViewById(R.id.switchECGEnabled);
        mSwitchECGEnabled.setOnCheckedChangeListener(this);

        mSpinnerSampleRates = (Spinner) findViewById(R.id.spinnerSampleRates);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Find serial in opening intent
        Intent intent = getIntent();
        connectedSerial = intent.getStringExtra(SERIAL);

        Log.i(LOG_TAG, "=== ECG Activity Started with Serial: " + connectedSerial + " ===");

        // Set SampleRate mSpinnerSampleRates
        mSpinnerAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_dropdown_item_1line, mSpinnerRates);
        mSpinnerSampleRates.setAdapter(mSpinnerAdapter);

        // Set ECG graph
        GraphView graph = (GraphView) findViewById(R.id.graphECG);
        mSeriesECG = new LineGraphSeries<DataPoint>();
        graph.addSeries(mSeriesECG);
        graph.getViewport().setXAxisBoundsManual(true);
        graph.getViewport().setMinX(0);
        graph.getViewport().setMaxX(500);

        graph.getViewport().setYAxisBoundsManual(true);
        graph.getViewport().setMinY(-2000);
        graph.getViewport().setMaxY(2000);

        // Start by getting ECG info
        fetchECGInfo();
    }

    @Override
    protected void onDestroy() {
        Log.d(LOG_TAG, "onDestroy()");
        unsubscribeAll();
        ECGActivity.s_INSTANCE = null;
        super.onDestroy();
    }

    private void fetchECGInfo() {
        String uri = SCHEME_PREFIX + connectedSerial + URI_ECG_INFO;
        Log.i(LOG_TAG, "Fetching ECG Info from: " + uri);

        getMDS().get(uri, null, new MdsResponseListener() {
            @Override
            public void onSuccess(String data) {
                Log.i(LOG_TAG, "ECG info successful: " + data);

                try {
                    ECGInfoResponse infoResponse = new Gson().fromJson(data, ECGInfoResponse.class);

                    // Fill sample rates to the spinner
                    mSpinnerRates.clear();
                    for (int sampleRate : infoResponse.content.availableSampleRates) {
                        mSpinnerRates.add("" + sampleRate);
                    }

                    mSpinnerAdapter.notifyDataSetChanged();
                    mSpinnerSampleRates.setSelection(mSpinnerAdapter.getPosition("" + DEFAULT_SAMPLE_RATE));

                    // Enable Subscription switch
                    mSwitchECGEnabled.setEnabled(true);

                    // Subscribe to sensors with delays to avoid conflicts
                    startSensorSubscriptions();

                } catch (Exception e) {
                    Log.e(LOG_TAG, "Error parsing ECG info response", e);
                }
            }

            @Override
            public void onError(MdsException e) {
                Log.e(LOG_TAG, "ECG info returned error: " + e.getMessage());
            }
        });
    }

    private void startSensorSubscriptions() {
        Log.i(LOG_TAG, "=== Starting Sensor Subscriptions ===");

        // Start with HR subscription
        enableHRSubscription();

        // Add delays between subscriptions to avoid conflicts
        handler.postDelayed(this::enableAccelerationSubscription, 1000);
        handler.postDelayed(this::enableGyroscopeSubscription, 2000);
        handler.postDelayed(this::enableMagnetometerSubscription, 3000);
        // เลือกใช้ IMU หรือ individual sensors (ไม่ใช้ทั้งสองแบบพร้อมกัน)
        // handler.postDelayed(this::enableIMUSubscription, 4000);
    }

    private void enableHRSubscription() {
        Log.i(LOG_TAG, "--- Enabling HR Subscription ---");
        unsubscribeHR();

        StringBuilder sb = new StringBuilder();
        String strContract = sb.append("{\"Uri\": \"").append(connectedSerial).append(URI_MEAS_HR).append("\"}").toString();
        Log.d(LOG_TAG, "HR Contract: " + strContract);

        try {
            mHRSubscription = getMDS().builder().build(this).subscribe(URI_EVENTLISTENER,
                    strContract, new MdsNotificationListener() {
                        @Override
                        public void onNotification(String data) {
                            Log.d(LOG_TAG, "HR Notification: " + data);

                            try {
                                HRResponse hrResponse = new Gson().fromJson(data, HRResponse.class);
                                if (hrResponse != null && hrResponse.body != null) {
                                    int hr = (int) hrResponse.body.average;
                                    Log.i(LOG_TAG, "Heart Rate: " + hr + " BPM");

                                    runOnUiThread(() -> {
                                        ((TextView) findViewById(R.id.textViewHR)).setText("" + hr);
                                        ((TextView) findViewById(R.id.textViewIBI)).setText(
                                                hrResponse.body.rrData.length > 0 ?
                                                        "" + hrResponse.body.rrData[hrResponse.body.rrData.length - 1] : "--");
                                    });
                                }
                            } catch (Exception e) {
                                Log.e(LOG_TAG, "Error parsing HR response", e);
                            }
                        }

                        @Override
                        public void onError(MdsException error) {
                            Log.e(LOG_TAG, "HR Subscription ERROR: " + error.getMessage());
                            unsubscribeHR();
                        }
                    });
            Log.i(LOG_TAG, "HR Subscription created successfully");
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to create HR subscription", e);
        }
    }

    private void enableAccelerationSubscription() {
        Log.i(LOG_TAG, "--- Enabling Acceleration Subscription ---");
        unsubscribeAcceleration();

        int sampleRate = 26;
        StringBuilder sb = new StringBuilder();
        String strContract = sb.append("{\"Uri\": \"")
                .append(connectedSerial)
                .append(URI_LINEAR_ACCELERATION)
                .append(sampleRate)
                .append("\"}")
                .toString();

        Log.d(LOG_TAG, "Acceleration Contract: " + strContract);

        try {
            mAccSubscription = getMDS().builder().build(this).subscribe(URI_EVENTLISTENER,
                    strContract, new MdsNotificationListener() {
                        @Override
                        public void onNotification(String data) {
                            // Log แบบย่อเพื่อไม่ให้ spam log
                            if (Math.random() < 0.1) { // Log เพียง 10% ของข้อมูล
                                Log.d(LOG_TAG, "Acc Data: " + data.substring(0, Math.min(100, data.length())));
                            }

                            try {
                                AccelerationResponse accResponse = new Gson().fromJson(data, AccelerationResponse.class);
                                if (accResponse != null && accResponse.body != null && accResponse.body.arrayAcc != null) {
                                    double[] xValues = accResponse.body.arrayAcc.x;
                                    double[] yValues = accResponse.body.arrayAcc.y;
                                    double[] zValues = accResponse.body.arrayAcc.z;

                                    if (xValues != null && xValues.length > 0) {
                                        // Log เฉพาะค่าแรกเพื่อไม่ให้ spam
                                        Log.i(LOG_TAG, String.format("Acceleration [0]: X=%.3f, Y=%.3f, Z=%.3f",
                                                xValues[0], yValues[0], zValues[0]));
                                    }
                                }
                            } catch (Exception e) {
                                Log.e(LOG_TAG, "Error parsing Acceleration response", e);
                            }
                        }

                        @Override
                        public void onError(MdsException error) {
                            Log.e(LOG_TAG, "Acceleration Subscription ERROR: " + error.getMessage());
                            unsubscribeAcceleration();
                        }
                    });
            Log.i(LOG_TAG, "Acceleration Subscription created successfully");
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to create Acceleration subscription", e);
        }
    }

    private void enableGyroscopeSubscription() {
        Log.i(LOG_TAG, "--- Enabling Gyroscope Subscription ---");
        unsubscribeGyroscope();

        int sampleRate = 26;
        StringBuilder sb = new StringBuilder();
        String strContract = sb.append("{\"Uri\": \"")
                .append(connectedSerial)
                .append(URI_GYROSCOPE)
                .append(sampleRate)
                .append("\"}")
                .toString();

        Log.d(LOG_TAG, "Gyroscope Contract: " + strContract);

        try {
            mGyroSubscription = getMDS().builder().build(this).subscribe(URI_EVENTLISTENER,
                    strContract, new MdsNotificationListener() {
                        @Override
                        public void onNotification(String data) {
                            if (Math.random() < 0.1) { // Log เพียง 10% ของข้อมูล
                                Log.d(LOG_TAG, "Gyro Data: " + data.substring(0, Math.min(100, data.length())));
                            }

                            try {
                                GyroscopeResponse gyroResponse = new Gson().fromJson(data, GyroscopeResponse.class);
                                if (gyroResponse != null && gyroResponse.body != null && gyroResponse.body.arrayGyro != null) {
                                    double[] xValues = gyroResponse.body.arrayGyro.x;
                                    double[] yValues = gyroResponse.body.arrayGyro.y;
                                    double[] zValues = gyroResponse.body.arrayGyro.z;

                                    if (xValues != null && xValues.length > 0) {
                                        Log.i(LOG_TAG, String.format("Gyroscope [0]: X=%.3f, Y=%.3f, Z=%.3f",
                                                xValues[0], yValues[0], zValues[0]));
                                    }
                                }
                            } catch (Exception e) {
                                Log.e(LOG_TAG, "Error parsing Gyroscope response", e);
                            }
                        }

                        @Override
                        public void onError(MdsException error) {
                            Log.e(LOG_TAG, "Gyroscope Subscription ERROR: " + error.getMessage());
                            unsubscribeGyroscope();
                        }
                    });
            Log.i(LOG_TAG, "Gyroscope Subscription created successfully");
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to create Gyroscope subscription", e);
        }
    }

    private void enableMagnetometerSubscription() {
        Log.i(LOG_TAG, "--- Enabling Magnetometer Subscription ---");
        unsubscribeMagnetometer();

        int sampleRate = 26;
        StringBuilder sb = new StringBuilder();
        String strContract = sb.append("{\"Uri\": \"")
                .append(connectedSerial)
                .append(URI_MAGNETOMETER)
                .append(sampleRate)
                .append("\"}")
                .toString();

        Log.d(LOG_TAG, "Magnetometer Contract: " + strContract);

        try {
            mMagnSubscription = getMDS().builder().build(this).subscribe(URI_EVENTLISTENER,
                    strContract, new MdsNotificationListener() {
                        @Override
                        public void onNotification(String data) {
                            if (Math.random() < 0.1) { // Log เพียง 10% ของข้อมูล
                                Log.d(LOG_TAG, "Magn Data: " + data.substring(0, Math.min(100, data.length())));
                            }

                            try {
                                MagnetometerResponse magnResponse = new Gson().fromJson(data, MagnetometerResponse.class);
                                if (magnResponse != null && magnResponse.body != null && magnResponse.body.arrayMagn != null) {
                                    double[] xValues = magnResponse.body.arrayMagn.x;
                                    double[] yValues = magnResponse.body.arrayMagn.y;
                                    double[] zValues = magnResponse.body.arrayMagn.z;

                                    if (xValues != null && xValues.length > 0) {
                                        Log.i(LOG_TAG, String.format("Magnetometer [0]: X=%.3f, Y=%.3f, Z=%.3f",
                                                xValues[0], yValues[0], zValues[0]));
                                    }
                                }
                            } catch (Exception e) {
                                Log.e(LOG_TAG, "Error parsing Magnetometer response", e);
                            }
                        }

                        @Override
                        public void onError(MdsException error) {
                            Log.e(LOG_TAG, "Magnetometer Subscription ERROR: " + error.getMessage());
                            unsubscribeMagnetometer();
                        }
                    });
            Log.i(LOG_TAG, "Magnetometer Subscription created successfully");
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to create Magnetometer subscription", e);
        }
    }

    private void enableIMUSubscription() {
        Log.i(LOG_TAG, "--- Enabling IMU Subscription ---");
        unsubscribeIMU();

        int sampleRate = 26;
        StringBuilder sb = new StringBuilder();
        String strContract = sb.append("{\"Uri\": \"")
                .append(connectedSerial)
                .append(URI_IMU)
                .append(sampleRate)
                .append("\"}")
                .toString();

        Log.d(LOG_TAG, "IMU Contract: " + strContract);

        try {
            mIMUSubscription = getMDS().builder().build(this).subscribe(URI_EVENTLISTENER,
                    strContract, new MdsNotificationListener() {
                        @Override
                        public void onNotification(String data) {
                            if (Math.random() < 0.05) { // Log เพียง 5% ของข้อมูล IMU (เพราะมีข้อมูลเยอะ)
                                Log.d(LOG_TAG, "IMU Data: " + data.substring(0, Math.min(150, data.length())));
                            }

                            try {
                                IMUResponse imuResponse = new Gson().fromJson(data, IMUResponse.class);
                                if (imuResponse != null && imuResponse.body != null) {
                                    // Log เฉพาะค่าแรกของแต่ละเซนเซอร์
                                    if (imuResponse.body.arrayAcc != null && imuResponse.body.arrayAcc.x != null
                                            && imuResponse.body.arrayAcc.x.length > 0) {
                                        Log.i(LOG_TAG, String.format("IMU Acc [0]: X=%.3f, Y=%.3f, Z=%.3f",
                                                imuResponse.body.arrayAcc.x[0],
                                                imuResponse.body.arrayAcc.y[0],
                                                imuResponse.body.arrayAcc.z[0]));
                                    }

                                    if (imuResponse.body.arrayGyro != null && imuResponse.body.arrayGyro.x != null
                                            && imuResponse.body.arrayGyro.x.length > 0) {
                                        Log.i(LOG_TAG, String.format("IMU Gyro [0]: X=%.3f, Y=%.3f, Z=%.3f",
                                                imuResponse.body.arrayGyro.x[0],
                                                imuResponse.body.arrayGyro.y[0],
                                                imuResponse.body.arrayGyro.z[0]));
                                    }

                                    if (imuResponse.body.arrayMagn != null && imuResponse.body.arrayMagn.x != null
                                            && imuResponse.body.arrayMagn.x.length > 0) {
                                        Log.i(LOG_TAG, String.format("IMU Magn [0]: X=%.3f, Y=%.3f, Z=%.3f",
                                                imuResponse.body.arrayMagn.x[0],
                                                imuResponse.body.arrayMagn.y[0],
                                                imuResponse.body.arrayMagn.z[0]));
                                    }
                                }
                            } catch (Exception e) {
                                Log.e(LOG_TAG, "Error parsing IMU response", e);
                            }
                        }

                        @Override
                        public void onError(MdsException error) {
                            Log.e(LOG_TAG, "IMU Subscription ERROR: " + error.getMessage());
                            unsubscribeIMU();
                        }
                    });
            Log.i(LOG_TAG, "IMU Subscription created successfully");
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to create IMU subscription", e);
        }
    }

    private void enableECGSubscription() {
        Log.i(LOG_TAG, "--- Enabling ECG Subscription ---");
        unsubscribeECG();

        StringBuilder sb = new StringBuilder();
        int sampleRate = Integer.parseInt("" + mSpinnerSampleRates.getSelectedItem());
        final int GRAPH_WINDOW_WIDTH = sampleRate * 3;
        String strContract = sb.append("{\"Uri\": \"").append(connectedSerial).append(URI_ECG_ROOT).append(sampleRate).append("\"}").toString();
        Log.d(LOG_TAG, "ECG Contract: " + strContract);

        // Clear graph
        mSeriesECG.resetData(new DataPoint[0]);
        final GraphView graph = (GraphView) findViewById(R.id.graphECG);
        graph.getViewport().setMaxX(GRAPH_WINDOW_WIDTH);
        mDataPointsAppended = 0;

        try {
            mECGSubscription = getMDS().builder().build(this).subscribe(URI_EVENTLISTENER,
                    strContract, new MdsNotificationListener() {
                        @Override
                        public void onNotification(String data) {
                            if (Math.random() < 0.05) { // Log เพียง 5% ของข้อมูล ECG
                                Log.d(LOG_TAG, "ECG Data: " + data.substring(0, Math.min(100, data.length())));
                            }

                            try {
                                ECGResponse ecgResponse = new Gson().fromJson(data, ECGResponse.class);
                                if (ecgResponse != null && ecgResponse.body != null && ecgResponse.body.samples != null) {
                                    for (int sample : ecgResponse.body.samples) {
                                        try {
                                            mSeriesECG.appendData(
                                                    new DataPoint(mDataPointsAppended, sample), true,
                                                    GRAPH_WINDOW_WIDTH);
                                        } catch (IllegalArgumentException e) {
                                            Log.e(LOG_TAG, "GraphView error ", e);
                                        }
                                        mDataPointsAppended++;

                                        if (count == 400) {
                                            count = 0;
                                            // predictECG(intArr); // ปิดไว้ก่อนเพื่อ test การเชื่อมต่อ
                                        }

                                        intArr[count] = sample;
                                        count++;
                                    }
                                }
                            } catch (Exception e) {
                                Log.e(LOG_TAG, "Error parsing ECG response", e);
                            }
                        }

                        @Override
                        public void onError(MdsException error) {
                            Log.e(LOG_TAG, "ECG Subscription ERROR: " + error.getMessage());
                            unsubscribeECG();
                        }
                    });
            Log.i(LOG_TAG, "ECG Subscription created successfully");
        } catch (Exception e) {
            Log.e(LOG_TAG, "Failed to create ECG subscription", e);
        }
    }

    // Unsubscribe methods
    private void unsubscribeECG() {
        if (mECGSubscription != null) {
            Log.d(LOG_TAG, "Unsubscribing ECG");
            mECGSubscription.unsubscribe();
            mECGSubscription = null;
        }
    }

    private void unsubscribeHR() {
        if (mHRSubscription != null) {
            Log.d(LOG_TAG, "Unsubscribing HR");
            mHRSubscription.unsubscribe();
            mHRSubscription = null;
        }
    }

    private void unsubscribeAcceleration() {
        if (mAccSubscription != null) {
            Log.d(LOG_TAG, "Unsubscribing Acceleration");
            mAccSubscription.unsubscribe();
            mAccSubscription = null;
        }
    }

    private void unsubscribeGyroscope() {
        if (mGyroSubscription != null) {
            Log.d(LOG_TAG, "Unsubscribing Gyroscope");
            mGyroSubscription.unsubscribe();
            mGyroSubscription = null;
        }
    }

    private void unsubscribeMagnetometer() {
        if (mMagnSubscription != null) {
            Log.d(LOG_TAG, "Unsubscribing Magnetometer");
            mMagnSubscription.unsubscribe();
            mMagnSubscription = null;
        }
    }

    private void unsubscribeIMU() {
        if (mIMUSubscription != null) {
            Log.d(LOG_TAG, "Unsubscribing IMU");
            mIMUSubscription.unsubscribe();
            mIMUSubscription = null;
        }
    }

    void unsubscribeAll() {
        Log.d(LOG_TAG, "=== Unsubscribing All Sensors ===");
        unsubscribeECG();
        unsubscribeHR();
        unsubscribeAcceleration();
        unsubscribeGyroscope();
        unsubscribeMagnetometer();
        unsubscribeIMU();
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (isChecked) {
            Log.i(LOG_TAG, "ECG Switch: ON");
            enableECGSubscription();
        } else {
            Log.i(LOG_TAG, "ECG Switch: OFF");
            unsubscribeECG();
        }
    }
}