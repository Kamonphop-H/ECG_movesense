package com.example.ecg.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.example.ecg.R;
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

import java.util.ArrayList;
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

    private int count = 0;
    private int[] intArr = new int[400];

    // Handler for delays
    private Handler handler = new Handler(Looper.getMainLooper());

    public static final String URI_EVENTLISTENER = "suunto://MDS/EventListener";
    public static final String SCHEME_PREFIX = "suunto://";

    public static final String URI_ECG_INFO = "/Meas/ECG/Info";
    public static final String URI_ECG_ROOT = "/Meas/ECG/";
    public static final String URI_MEAS_HR = "/Meas/HR";

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

        enableHRSubscription();
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

    void unsubscribeAll() {
        Log.d(LOG_TAG, "=== Unsubscribing All Sensors ===");
        unsubscribeECG();
        unsubscribeHR();
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