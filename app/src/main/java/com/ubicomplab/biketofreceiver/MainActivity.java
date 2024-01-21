package com.ubicomplab.biketofreceiver;

import android.Manifest;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MainActivity extends AppCompatActivity {

    UUID MY_SERVICE_UUID = UUID.fromString("10336bc0-c8f9-4de7-b637-a68b7ef33fc9");
    UUID MY_CHARACTERISTIC_UUID = UUID.fromString("43336bc0-c8f9-4de7-b637-a68b7ef33fc9");
    // The fixed standard UUID for notifications.
    UUID YOUR_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");


    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothGatt mBluetoothGatt;
    private BluetoothLeScanner mBluetoothLeScanner;
    private List<BluetoothDevice> mDeviceList;  // List of devices to query using the clicked device name.
    private ArrayAdapter<String> mDeviceListAdapter;  // List of strings to display on the screen.
    private ListView mDeviceListView;
    private boolean currentlyScanning = false;
    private String formattedDateTime;
    //private ByteBuffer buffer = ByteBuffer.allocate(64 * 2); // Each integer is 2 bytes
    // Variables for controlling file writing for two output files.
    private ConcurrentLinkedQueue<Integer> rearSensorDataQueue = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<Integer> sideSensorDataQueue = new ConcurrentLinkedQueue<>();
    private Thread rearSensorFileWritingThread = null;
    private Thread sideSensorFileWritingThread = null;
    private File rearSensorOutputFile;
    private File sideSensorOutputFile;
    private String audioFilePath;
    private int restartCounter;

    private SensorManager mySensorManager;

    private Sensor rotationSensor;
    private SensorLogger rotationSensorLogger;

    private Sensor accelerometer;
    private SensorLogger accelerometerLogger;

    private Sensor gyroscope;
    private SensorLogger gyroscopeLogger;

    private Sensor magnetometer;
    private SensorLogger magnetometerLogger;

    private TextView textView;
    private volatile boolean keepRunning = true;
    DateTimeFormatter formatter;
    private int frame_mean = 0;

    private AudioRecordThread audioRecordThread;


    private static final int MULTIPLE_PERMISSIONS_REQUEST_CODE = 123;

    private void writeBufferToFile(ByteBuffer buffer, File sensorOutputFile) {
        StringBuilder csvLine = new StringBuilder();
        long timestamp = System.currentTimeMillis();
        csvLine.append(timestamp);
        frame_mean = 0;

        while (buffer.hasRemaining()) {
            short next = buffer.getShort();
            if (next == (byte) '\n') {
                csvLine.append("\n");
            } else {
                int value = next & 0xFFFF;
                frame_mean = frame_mean + value;
                csvLine.append(",").append(value);
            }
        }
        frame_mean = frame_mean / 64;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Update your TextView here
                textView.setText(frame_mean + "");
            }
        });

        try (BufferedWriter bw = new BufferedWriter(
                new FileWriter(sensorOutputFile, true))) {
            bw.write(csvLine.toString());
            bw.newLine();
        } catch (IOException e) {
            // Handle IOException
        }
    }

    private void writeLineToFile(String line, File outputFile) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(outputFile, true))) {
            bw.write(line);
            bw.newLine();
        } catch (IOException e) {
            // Handle IOException
        }
    }

    private synchronized boolean isFileWritingThreadRunning(Thread fileWritingThread) {
        return fileWritingThread != null && fileWritingThread.isAlive();
    }

    // Thread for writing bluetooth data only.
    private synchronized Thread startFileWritingThread(Thread thread,
                                                       ConcurrentLinkedQueue<Integer> queue,
                                                       File outputFile,
                                                       String threadName) {
        // if thread is already running just return it.
        if (isFileWritingThreadRunning(thread)) {
            return thread;
            //return; // The thread is already running
        }
        keepRunning = true;
        //Log.i("STARTING THREAD", "Starting thread: " + threadName);

        thread = new Thread(() -> {
            StringBuilder csvLine = new StringBuilder();
            while (keepRunning) {
                while (!queue.isEmpty()) {
                    //Log.i("ThreadName", threadName);
                    Integer polledValue = queue.poll();
                    int value = -999;
                    if (polledValue != null) {
                        value = polledValue.intValue();
                        // Process the value
                    } else {
                        Log.i("in thread", "Queue.poll() was null....");
                    }

                    if (value == Integer.MIN_VALUE) { // End of packet marker
                        // Write the current line to file and start a new line
                        writeLineToFile(csvLine.toString(), outputFile);
                        //Log.i("ThreadName", threadName + "has data!");
                        csvLine = new StringBuilder();
                        long timestamp = System.currentTimeMillis();
                        csvLine.append(timestamp).append(",");
                        continue;
                    }

                    // Append value to the CSV line
                    csvLine.append(value).append(",");
                }

                // Optional: Sleep a bit if queue is empty to reduce CPU usage
                try {
                    Thread.sleep(10); // Sleep for 10 milliseconds
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, threadName);
        thread.start();
        return thread;
    }

    private synchronized Thread stopFileWritingThread(Thread thread) {
        if (!isFileWritingThreadRunning(thread)) {
            return null; // The thread is not running
        }
        keepRunning = false;
        thread.interrupt();
        try {
            thread.join(); // Wait for the thread to finish
            Log.i("STOP THREAD", "thread joined.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.i("STOP THREAD", "Could not join thread.");
        }
        // only necessary if returns void using global thread variable.
        // thread = null; // Clear the thread reference
        return null;
    }

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i("BLE", "Connected to GATT server.");
                if (ActivityCompat.checkSelfPermission(MainActivity.this,
                        Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    handlePermissionsNotGranted(Manifest.permission.BLUETOOTH_CONNECT);
                    return;
                }

                // Request larger MTU size
                //gatt.requestMtu(260);

                // Create a new output file to write to each time you reconnection.
                LocalDateTime now = LocalDateTime.now();
                formattedDateTime = now.format(formatter);
                String rearFilename = getExternalFilesDir(null) + "/" + formattedDateTime + "_rear.csv";
                String sideFilename = getExternalFilesDir(null) + "/" + formattedDateTime + "_side.csv";
                Log.i("FILEPATH:", rearFilename + "");
                rearSensorOutputFile = new File(rearFilename);
                sideSensorOutputFile = new File(sideFilename);
                frame_mean = 0;

                restartCounter++;

                rotationSensorLogger.register(formattedDateTime);
                accelerometerLogger.register(formattedDateTime);
                gyroscopeLogger.register(formattedDateTime);
                magnetometerLogger.register(formattedDateTime);
                audioFilePath = getExternalFilesDir(null) + "/" + formattedDateTime + "_audio.pcm";

                // Register the side and rear ToF receivers.
                if (!isFileWritingThreadRunning(sideSensorFileWritingThread)) {
                    sideSensorFileWritingThread = startFileWritingThread(
                            sideSensorFileWritingThread, sideSensorDataQueue,
                            sideSensorOutputFile, "sideSensorThread" + restartCounter);
                }
                if (!isFileWritingThreadRunning(rearSensorFileWritingThread)) {
                    //rearSensorFileWritingThread = startFileWritingThread(rearSensorFileWritingThread);
                    rearSensorFileWritingThread = startFileWritingThread(
                            rearSensorFileWritingThread, rearSensorDataQueue,
                            rearSensorOutputFile, "rearSensorThread" + restartCounter);
                }

                if (ActivityCompat.checkSelfPermission(getApplicationContext(),
                        Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{Manifest.permission.RECORD_AUDIO}, 5);
                }
                int bufferSize = AudioRecord.getMinBufferSize(
                        44100,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                AudioRecord audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.UNPROCESSED,//MediaRecorder.AudioSource.MIC,
                        44100,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize);

                audioRecordThread = new AudioRecordThread(audioFilePath, audioRecord, bufferSize);
                audioRecordThread.startRecording();


                mBluetoothGatt.discoverServices();
                Log.i("BLE", "Attempting to start service discovery");

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i("BLE", "Disconnected from GATT server.");
                // Stop the file writing thread and reset the variable to null.
                rearSensorFileWritingThread = stopFileWritingThread(rearSensorFileWritingThread);
                rearSensorDataQueue.clear(); // Clear the data queue
                sideSensorFileWritingThread = stopFileWritingThread(sideSensorFileWritingThread);
                sideSensorDataQueue.clear(); // Clear the data queue

                rotationSensorLogger.close();
                accelerometerLogger.close();
                gyroscopeLogger.close();
                magnetometerLogger.close();

                audioRecordThread.stopRecording();

                closeGatt(); // Necessary to ensure only one bluetooth callback is registered at a time.

            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            super.onMtuChanged(gatt, mtu, status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("ON MTU CHANGE", "SUCCESS!!!!");
            } else {
                Log.i("ON MTU CHANGE", "FAILURE....");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i("BLE", "GATT SUCCESS, looking for correct service and characteristic.");
                Button button = findViewById(R.id.scanButton);
                button.setBackgroundColor(Color.parseColor("#00FF00")); // sets background color to red
                // Loop through available GATT Services and find your specific service & characteristic
                for (BluetoothGattService gattService : gatt.getServices()) {
                    if (gattService.getUuid().equals(MY_SERVICE_UUID)) {
                        BluetoothGattCharacteristic characteristic =
                                gattService.getCharacteristic(MY_CHARACTERISTIC_UUID);
                        // Enable local notifications
                        if (ActivityCompat.checkSelfPermission(
                                MainActivity.this,
                                Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                            handlePermissionsNotGranted(Manifest.permission.BLUETOOTH_CONNECT);
                            return;
                        }
                        // Clear buffer anytime the connection is remade.
                        //buffer.clear();
                        // Start thread to process received bluetooth data into a file.
                        //startFileWritingThread();
                        gatt.setCharacteristicNotification(characteristic, true);
                        // Enabled remote notifications
                        BluetoothGattDescriptor desc = characteristic.getDescriptor(
                                YOUR_DESCRIPTOR_UUID);
                        desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        gatt.writeDescriptor(desc);
                        Log.i("BLE", "Wrote descriptor to enable notifications.");
                    }
                }
            } else {
                Log.w("BLE", "onServicesDiscovered received: " + status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            if (MY_CHARACTERISTIC_UUID.equals(characteristic.getUuid())) {
                byte[] data = characteristic.getValue();
                long timestamp = System.currentTimeMillis();

                int sensorIndex = data[0] & 0xFF;
                int packetIndex = data[1] & 0xFF;
                //int sensorIndex = (data[0] & 0xF0) >> 4;
                //int packetIndex = data[0] & 0x0F;
                int readCount = 0;
                //int readCount = data[1] & 0xFF;
                int firstPayloadInt = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);

                Log.i("Received data", readCount + ": from sensor " + sensorIndex + " packet " + packetIndex + " at time " + timestamp + " first value was " + firstPayloadInt);
                // Add received data to the respective queue
                // Assume data.length is always even and > 2 for simplicity
                for (int i = 2; i < data.length; i += 2) {
                    int value = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
                    if (sensorIndex == 1) {
                        rearSensorDataQueue.offer(value);
                    } else if (sensorIndex == 2) {
                        sideSensorDataQueue.offer(value);
                    }
                }

                // Optionally, add a special marker to indicate packet end
                int endOfPacketMarker = Integer.MAX_VALUE; // or some other value that won't conflict with actual data
                int endOfReading = Integer.MIN_VALUE;
                if (sensorIndex == 1) {
                    rearSensorDataQueue.offer(endOfPacketMarker - packetIndex);
                    // TODO update this in the case that there are more than 2 packets per reading.
                    if (packetIndex == 1) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                // Update your TextView here
                                textView.setText(firstPayloadInt + "");
                            }
                        });
                        rearSensorDataQueue.offer(endOfReading);
                    }
                } else if (sensorIndex == 2) {
                    sideSensorDataQueue.offer(endOfPacketMarker - packetIndex);
                    if (packetIndex == 1) {
                        sideSensorDataQueue.offer(endOfReading);
                    }
                }
            }
        }
    };

    private boolean checkAndRequestPermissions() {
        String[] permissions = new String[]{
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
        };

        boolean allPermissionsGranted = true;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this,
                    permission) != PackageManager.PERMISSION_GRANTED) {
                allPermissionsGranted = false;
                Log.i("permissions", permission + "not granted.");
                break;
            }
        }

        if (!allPermissionsGranted) {
            ActivityCompat.requestPermissions(this, permissions,
                    MULTIPLE_PERMISSIONS_REQUEST_CODE);
            Log.i("permissions", "Permissions not granted!");
            return false;
        } else {
            Log.i("permissions", "Permissions granted!");
            return true;
        }
    }

    private void handlePermissionsNotGranted(String permission) {
        Log.i("Permissions",
                permission + "not granted. Discovered in permission check before function call.");
    }

    private void filter(String text) {
        List<BluetoothDevice> filteredList = new ArrayList<>();
        List<String> filteredListNames = new ArrayList<>();

        for (BluetoothDevice device : mDeviceList) {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                handlePermissionsNotGranted(Manifest.permission.BLUETOOTH_CONNECT);
                return;
            }
            if (device.getName() != null && device.getName().toLowerCase().contains(
                    text.toLowerCase())) {
                filteredList.add(device);
                filteredListNames.add(device.getName());
            }
        }

        mDeviceListAdapter.clear();
        mDeviceListAdapter.addAll(filteredListNames);
        mDeviceListAdapter.notifyDataSetChanged();
    }

    public class SensorEventWriter implements SensorEventListener {
        private BufferedWriter bufferedWriter;

        public SensorEventWriter(BufferedWriter bufferedWriter) {
            this.bufferedWriter = bufferedWriter;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            // Process sensor data (e.g., write to a file)
            writeSensorDataToFile(event);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Handle sensor accuracy changes if needed
        }

        private void writeSensorDataToFile(SensorEvent event) {
            try {
                // Prepare the data string in CSV format
                StringBuilder dataString = new StringBuilder();
                long eventTimeInMillis = (event.timestamp / 1000000L) + System.currentTimeMillis() - SystemClock.elapsedRealtime();

                dataString.append(eventTimeInMillis).append(","); // Timestamp

                // Append sensor values
                for (float value : event.values) {
                    dataString.append(value).append(",");
                }
                // Remove the last comma
                dataString.deleteCharAt(dataString.length() - 1);
                // Write to file and add a new line
                bufferedWriter.write(dataString.toString());
                bufferedWriter.newLine();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public class SensorLogger {
        private SensorManager sensorManager;
        private Sensor sensor;
        private String sensorType;

        private SensorEventListener sensorEventListener;
        private HandlerThread handlerThread;
        private BufferedWriter bufferedWriter;

        public SensorLogger(SensorManager sensorManager, Sensor sensor, String sensorType) {

            this.sensorManager = sensorManager;
            this.sensor = sensor;
            this.sensorType = sensorType;
        }

        public void register(String commonFileName) {
            if (this.sensor != null) {
                this.handlerThread = new HandlerThread(this.sensorType + "SensorThread");
                this.handlerThread.start();
                Handler sensorHandler = new Handler(this.handlerThread.getLooper());
                // Initialize BufferedWriter
                File file = new File(getExternalFilesDir(null), commonFileName + "_" + this.sensorType + ".csv");
                try {
                    this.bufferedWriter = new BufferedWriter(new FileWriter(file, true)); // 'true' to append
                } catch (IOException e) {
                    e.printStackTrace();
                }
                // Initialize your sensor event listener
                this.sensorEventListener = new SensorEventWriter(this.bufferedWriter);
                this.sensorManager.registerListener(this.sensorEventListener,
                        this.sensor, this.sensorManager.SENSOR_DELAY_NORMAL, sensorHandler);
            } else {
                Log.i("SENSOR!", sensorType + " NOT Available");
            }
        }

        public void close() {
            // Unregister the sensor listener
            if (this.sensorEventListener != null) {
                this.sensorManager.unregisterListener(this.sensorEventListener);
                this.sensorEventListener = null;
            }

            // Close the BufferedWriter
            try {
                if (this.bufferedWriter != null) {
                    this.bufferedWriter.close();
                    this.bufferedWriter = null;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            // Stop the HandlerThread
            if (this.handlerThread != null) {
                this.handlerThread.quitSafely();
                try {
                    this.handlerThread.join();
                    this.handlerThread = null;
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        restartCounter = 0;
        mDeviceList = new ArrayList<>();
        mDeviceListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        mDeviceListView = findViewById(R.id.deviceListView);
        mDeviceListView.setAdapter(mDeviceListAdapter);
        EditText searchEditText = findViewById(R.id.searchEditText);

        LocalDateTime now = LocalDateTime.now();
        // Format it to a human-readable string
        formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy_HH:mm:ss");
        textView = (TextView) findViewById(R.id.textView);

        // Initialize the IMU sensors.
        mySensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        rotationSensor = mySensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        rotationSensorLogger = new SensorLogger(
                mySensorManager, rotationSensor, "rotation");
        accelerometer = mySensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        accelerometerLogger = new SensorLogger(
                mySensorManager, accelerometer, "accelerometer");
        gyroscope = mySensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        gyroscopeLogger = new SensorLogger(
                mySensorManager, gyroscope, "gyroscope");
        magnetometer = mySensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        magnetometerLogger = new SensorLogger(
                mySensorManager, magnetometer, "magnetometer");

        // Set up the TextWatcher
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // Do nothing
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Do nothing
            }

            @Override
            public void afterTextChanged(Editable s) {
                filter(s.toString());
            }
        });

        /* Connect to a device when clicked form the list present in the UI */
        mDeviceListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                BluetoothDevice device = mDeviceList.get(position);
                if (ActivityCompat.checkSelfPermission(MainActivity.this,
                        Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    handlePermissionsNotGranted(Manifest.permission.BLUETOOTH_SCAN);
                    return;
                }
                mBluetoothLeScanner.stopScan(mScanCallback);
                connectToDevice(device);
            }
        });

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mBluetoothLeScanner = mBluetoothAdapter.getBluetoothLeScanner();

        // Find the button by its ID
        Button myButton = findViewById(R.id.scanButton);

        // Set the click listener
        myButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Check permissions and start scanning for BLE device.
                if (currentlyScanning) {
                    if (ActivityCompat.checkSelfPermission(MainActivity.this,
                            Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                        handlePermissionsNotGranted(Manifest.permission.BLUETOOTH_SCAN);
                        return;
                    }
                    mBluetoothLeScanner.stopScan(mScanCallback);
                    myButton.setText("Start Scanning");
                    currentlyScanning = false;

                } else {
                    if (checkAndRequestPermissions()) {
                        startScanning();
                        myButton.setText("Stop Scanning");
                        currentlyScanning = true;
                    }
                    Toast.makeText(MainActivity.this,
                            "Button Clicked: attempting to scan.", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private ScanCallback mScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            if (ActivityCompat.checkSelfPermission(
                    MainActivity.this,
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            // Add devices to the device list to populate the UI.
            if (device.getName() != null && !mDeviceList.contains(device)) {
                mDeviceList.add(device);
                mDeviceListAdapter.add(device.getName());
                // Not sure if this is necessary.
                mDeviceListAdapter.notifyDataSetChanged();

            }
        }
    };


    private void startScanning() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED) {
            mBluetoothLeScanner.startScan(mScanCallback);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH_ADMIN,
                            Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }


    private void connectToDevice(BluetoothDevice device) {
        if (ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            handlePermissionsNotGranted(android.Manifest.permission.BLUETOOTH_CONNECT);
            return;
        }
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
        // Further operations will be done in the BluetoothGattCallback

        // Request high priority connection to potentially reduce the connection interval
        mBluetoothGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
    }

    private void closeGatt() {
        if (mBluetoothGatt != null) {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                handlePermissionsNotGranted(Manifest.permission.BLUETOOTH_CONNECT);
                return;
            }
            mBluetoothGatt.close();
            Button button = findViewById(R.id.scanButton);
            button.setBackgroundColor(Color.parseColor("#FF0000")); // sets background color to red
            mBluetoothGatt = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeGatt();
    }

    public class AudioRecordThread extends Thread {
        private boolean isRecording;
        private AudioRecord audioRecord;
        private int bufferSize;
        private String outputFile;

        public AudioRecordThread(String outputFile, AudioRecord audioRecord, int bufferSize) {
            this.outputFile = outputFile;
            this.bufferSize = bufferSize;
            this.audioRecord = audioRecord;
            //Log.i("audio record thread", this.outputFile);
        }

        public void startRecording() {
            isRecording = true;
            audioRecord.startRecording();
            start();
            //Log.i("audio record thread", "started");
        }

        public void stopRecording() {
            isRecording = false;
            audioRecord.stop();
            audioRecord.release();
            //Log.i("audio record thread", "stopped");
        }

        @Override
        public void run() {
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(outputFile);
                byte[] audioData = new byte[bufferSize];

                while (isRecording) {
                    //Log.i("audio record thread", "running");
                    int read = audioRecord.read(audioData, 0, bufferSize);
                    if (read > 0) {
                        fos.write(audioData, 0, read);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try {
                    if (fos != null) {
                        fos.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}