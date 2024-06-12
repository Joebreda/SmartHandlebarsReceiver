package com.ubicomplab.biketofreceiver;

import android.Manifest;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.app.Activity;
import android.app.PendingIntent;
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
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
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
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.google.android.gms.common.api.ResolvableApiException;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.LocationSettingsRequest;
import com.google.android.gms.location.LocationSettingsResponse;
import com.google.android.gms.location.Priority;
import com.google.android.gms.location.SettingsClient;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;

// requires 'https://jitpack.io' in settings.gradle.
import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

public class MainActivity extends AppCompatActivity {

    // Serial logic variables.
    public static final String ACTION_USB_PERMISSION = "com.ubicomplab.biketofreceiver.USB_PERMISSION";
    static UsbSerialDevice serialPort;
    UsbDeviceConnection connection;
    static Button serialLoggingButton;
    static Button bleScanButton;
    boolean serialLogging;

    // BLE logic variables.
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

    // BLE reconnect attempt code.
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000; // 1 second
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private long reconnectDelay = INITIAL_RECONNECT_DELAY_MS;
    private int reconnectAttempts = 0;

    private boolean hasAttemptedReconnect = false;
    private Handler reconnectionHandler = new Handler(Looper.getMainLooper());
    private static final long RECONNECTION_TIMEOUT_MS = 5000; // 5 seconds
    private Runnable reconnectionTimeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (hasAttemptedReconnect) {
                // Reconnection attempt timed out
                closeGatt();
            }
        }
    };

    // Variables for controlling file writing for non-sensorManager streams.
    private ConcurrentLinkedQueue<Integer> rearSensorDataQueueBLE = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<Integer> sideSensorDataQueueBLE = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<String> rearSensorDataQueueSerial = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<String> sideSensorDataQueueSerial = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<String> locationQueue = new ConcurrentLinkedQueue<>();
    private Thread rearSensorFileWritingThread = null;
    private Thread sideSensorFileWritingThread = null;
    private Thread locationFileWritingThread = null;
    private File rearSensorOutputFile;
    private File sideSensorOutputFile;
    private File locationOutputFile;
    private String audioFilePath;
    private int restartCounter;

    // Variables for controlling file writing for sensorManager streams.
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
    private TextView locationIndicator;
    private volatile boolean keepRunning = true;
    DateTimeFormatter formatter;
    private int frame_mean = 0;

    private LocationRequest locationRequest;
    private LocationCallback locationCallback;
    private FusedLocationProviderClient mFusedLocationClient;

    private AudioRecordThread audioRecordThread;

    private static final int MULTIPLE_PERMISSIONS_REQUEST_CODE = 123;

    private void handleReconnection(BluetoothGatt gatt) {
        hasAttemptedReconnect = true;
        // Attempt to reconnect
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        boolean isReconnecting = gatt.connect();
        Button button = findViewById(R.id.scanButton);
        button.setBackgroundColor(Color.parseColor("#FFFF00")); // sets background color to Yellow
        if (!isReconnecting) {
            // If reconnection fails immediately, close GATT
            closeGatt();
        } else {
            // Start a timeout for the reconnection attempt
            reconnectionHandler.postDelayed(reconnectionTimeoutRunnable, RECONNECTION_TIMEOUT_MS);
        }
    }


    // Code for writing location and BLE/Serial data to files.

    private Uri createMediaStoreUri(String fileName) {
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.Files.FileColumns.MIME_TYPE, "text/csv");
        values.put(MediaStore.Files.FileColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS);

        Uri uri = resolver.insert(MediaStore.Files.getContentUri("external"), values);
        if (uri == null) {
            throw new RuntimeException("Failed to create MediaStore record");
        }
        return uri;
    }

    // TODO ucomment for URI mediastore instead of filepath.
    private void writeLineToFile(String line, File outputFile) {
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(outputFile, true))) {
            bw.write(line);
            bw.newLine();
        } catch (IOException e) {
            // Handle IOException
            e.printStackTrace();
        }
        /*
        ContentResolver resolver = getContentResolver();
        try (OutputStream outputStream = resolver.openOutputStream(fileUri, "wa");  // "wa" for write append
             BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(outputStream))) {
            bw.write(line);
            bw.newLine();
        } catch (IOException e) {
            // Handle IOException
            e.printStackTrace();
        }
        */
    }

    private synchronized boolean isFileWritingThreadRunning(Thread fileWritingThread) {
        return fileWritingThread != null && fileWritingThread.isAlive();
    }

    // Thread for writing bluetooth data only.
    // TODO change these outputFile to Uri fileUri
    private synchronized Thread startFileWritingThread(Thread thread,
                                                       ConcurrentLinkedQueue<Integer> queue,
                                                       File outputFile,
                                                       String threadName) {
        // if thread is already running just return it.
        if (isFileWritingThreadRunning(thread)) {
            return thread;
        }
        keepRunning = true;

        thread = new Thread(() -> {
            StringBuilder csvLine = new StringBuilder();
            while (keepRunning) {
                while (!queue.isEmpty()) {
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

    // Thread for writing data which is passed to the concurrentLinkedQueue as a complete row including timestamp.
    private synchronized Thread startStringRowFileWritingThread(Thread thread,
                                                                ConcurrentLinkedQueue<String> queue,
                                                                File outputFile,
                                                                String threadName) {
        // if thread is already running just return it.
        if (isFileWritingThreadRunning(thread)) {
            return thread;
            //return; // The thread is already running
        }
        keepRunning = true;

        thread = new Thread(() -> {
            while (keepRunning) {
                while (!queue.isEmpty()) {
                    String polledValue = queue.poll();
                    writeLineToFile(polledValue, outputFile);
                }
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

    public void startLoggingAllSensors(boolean overBLE) {
        // Create a new output file to write to each time you reconnection.
        LocalDateTime now = LocalDateTime.now();
        formattedDateTime = now.format(formatter);
        String rearFilename = getExternalFilesDir(null) + "/" + formattedDateTime + "_rear.csv";
        String sideFilename = getExternalFilesDir(null) + "/" + formattedDateTime + "_side.csv";
        String locationFilename = getExternalFilesDir(null) + "/" + formattedDateTime + "_location.csv";

        Log.i("FILEPATH:", rearFilename + "");
        rearSensorOutputFile = new File(rearFilename);
        sideSensorOutputFile = new File(sideFilename);
        locationOutputFile = new File(locationFilename);
        frame_mean = 0;

        // TODO Instead of making an output file a more robust approach may be:
        //Uri fileUri = createMediaStoreUri("bluetooth_data.csv");

        restartCounter++;

        rotationSensorLogger.register(formattedDateTime);
        accelerometerLogger.register(formattedDateTime);
        gyroscopeLogger.register(formattedDateTime);
        magnetometerLogger.register(formattedDateTime);
        audioFilePath = getExternalFilesDir(null) + "/" + formattedDateTime + "_audio.pcm";

        // TODO These file writing threads handle timestamping which is dumb. Should move this to .offer.
        if (overBLE) {
            // Register the side and rear ToF receivers over BLE communication.
            if (!isFileWritingThreadRunning(sideSensorFileWritingThread)) {
                sideSensorFileWritingThread = startFileWritingThread(
                        sideSensorFileWritingThread, sideSensorDataQueueBLE,
                        sideSensorOutputFile, "sideSensorThread" + restartCounter);
            }
            if (!isFileWritingThreadRunning(rearSensorFileWritingThread)) {
                rearSensorFileWritingThread = startFileWritingThread(
                        rearSensorFileWritingThread, rearSensorDataQueueBLE,
                        rearSensorOutputFile, "rearSensorThread" + restartCounter);
            }
        } else {
            // Register the side and rear ToF receivers over Serial Connection.
            if (!isFileWritingThreadRunning(sideSensorFileWritingThread)) {
                sideSensorFileWritingThread = startStringRowFileWritingThread(
                        sideSensorFileWritingThread, sideSensorDataQueueSerial,
                        sideSensorOutputFile, "sideSensorThread" + restartCounter);
            }
            if (!isFileWritingThreadRunning(rearSensorFileWritingThread)) {
                rearSensorFileWritingThread = startStringRowFileWritingThread(
                        rearSensorFileWritingThread, rearSensorDataQueueSerial,
                        rearSensorOutputFile, "rearSensorThread" + restartCounter);
            }
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

        startLocationUpdates();

        // For logging location file (uses a different function to define thread than other writer threads).
        if (!isFileWritingThreadRunning(locationFileWritingThread)) {
            locationFileWritingThread = startStringRowFileWritingThread(
                    locationFileWritingThread, locationQueue,
                    locationOutputFile, "locationThread" + restartCounter);
        }
    }

    private void stopLoggingAllSensors() {
        rearSensorFileWritingThread = stopFileWritingThread(rearSensorFileWritingThread);
        rearSensorDataQueueBLE.clear(); // Clear the data queue
        rearSensorDataQueueSerial.clear(); // Clear the data queue
        sideSensorFileWritingThread = stopFileWritingThread(sideSensorFileWritingThread);
        sideSensorDataQueueBLE.clear(); // Clear the data queue
        sideSensorDataQueueSerial.clear(); // Clear the data queue

        rotationSensorLogger.close();
        accelerometerLogger.close();
        gyroscopeLogger.close();
        magnetometerLogger.close();

        audioRecordThread.stopRecording();

        locationFileWritingThread = stopFileWritingThread(locationFileWritingThread);
        locationQueue.clear(); // Clear the data queue
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
                hasAttemptedReconnect = false;
                reconnectionHandler.removeCallbacks(reconnectionTimeoutRunnable);

                // Start logging all sensors!
                startLoggingAllSensors(true);


                mBluetoothGatt.discoverServices();
                Log.i("BLE", "Attempting to start service discovery");

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i("BLE", "Disconnected from GATT server.");
                // Stop the file writing thread and reset the variable to null.
                stopLoggingAllSensors();

                //closeGatt(); // Necessary to ensure only one bluetooth callback is registered at a time.
                if (!hasAttemptedReconnect) {
                    handleReconnection(gatt);
                } else {
                    closeGatt();
                }

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
                        rearSensorDataQueueBLE.offer(value);
                    } else if (sensorIndex == 2) {
                        sideSensorDataQueueBLE.offer(value);
                    }
                }

                // Optionally, add a special marker to indicate packet end
                int endOfPacketMarker = Integer.MAX_VALUE; // or some other value that won't conflict with actual data
                int endOfReading = Integer.MIN_VALUE;
                if (sensorIndex == 1) {
                    rearSensorDataQueueBLE.offer(endOfPacketMarker - packetIndex);
                    // TODO update this in the case that there are more than 2 packets per reading.
                    if (packetIndex == 1) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                // Update your TextView here
                                textView.setText(firstPayloadInt + "");
                            }
                        });
                        rearSensorDataQueueBLE.offer(endOfReading);
                    }
                } else if (sensorIndex == 2) {
                    sideSensorDataQueueBLE.offer(endOfPacketMarker - packetIndex);
                    if (packetIndex == 1) {
                        sideSensorDataQueueBLE.offer(endOfReading);
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
                //File file = new File(getExternalFilesDir(null), commonFileName + "_" + this.sensorType + ".csv");
                try {
                    //this.bufferedWriter = new BufferedWriter(new FileWriter(file, true)); // 'true' to append
                    this.bufferedWriter = createBufferedWriter(commonFileName + "_" + this.sensorType + ".csv");
                } catch (IOException e) {
                    e.printStackTrace();
                }
                // Initialize your sensor event listener
                this.sensorEventListener = new SensorEventWriter(this.bufferedWriter);
                this.sensorManager.registerListener(this.sensorEventListener,
                        this.sensor, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler);
            } else {
                Log.i("SENSOR!", sensorType + " NOT Available");
            }
        }

        private BufferedWriter createBufferedWriter(String fileName) throws IOException {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Files.FileColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.Files.FileColumns.MIME_TYPE, "text/csv");
            values.put(MediaStore.Files.FileColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS);

            Uri uri = resolver.insert(MediaStore.Files.getContentUri("external"), values);
            if (uri != null) {
                OutputStream outputStream = resolver.openOutputStream(uri);
                if (outputStream != null) {
                    return new BufferedWriter(new OutputStreamWriter(outputStream));
                } else {
                    throw new IOException("Failed to open output stream");
                }
            } else {
                throw new IOException("Failed to create MediaStore record");
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

    public boolean isBluetoothEnabled() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Log.e("Bluetooth", "Device doesn't support Bluetooth");
            return false;
        }
        return bluetoothAdapter.isEnabled();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        checkAndRequestPermissions();

        String testfile = getExternalFilesDir(null) + "/" + formattedDateTime + "test.csv";
        Log.i("FILEPATH:", testfile + "");

        // Write logs to log file
        // TODO Uncomment if you need to debug serial connection code.
        try {
            // Define the log file
            String filename = "zzz_logcat_" + System.currentTimeMillis() + ".txt";
            File outputFile = new File(getExternalFilesDir(null), filename);

            // Start the logcat process
            Process process = Runtime.getRuntime().exec("logcat -f " + outputFile.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
        }

        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        registerReceiver(usbPermissionReceiver, filter);

        locationIndicator = findViewById(R.id.locationIndicator);
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
        bleScanButton = findViewById(R.id.scanButton);
        serialLoggingButton = findViewById(R.id.serialLogging);
        serialLoggingButton.setEnabled(false);
        serialLogging = false;

        // Set the click listener
        bleScanButton.setOnClickListener(new View.OnClickListener() {
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
                    bleScanButton.setText("Start Scanning");
                    currentlyScanning = false;

                } else {
                    if (isBluetoothEnabled()) {
                        startScanning();
                        bleScanButton.setText("Stop Scanning");
                        currentlyScanning = true;
                        Toast.makeText(MainActivity.this,
                                "Button Clicked: attempting to scan.", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this,
                                "Bluetooth is OFF, please turn it on!", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });

        // Set the click listener to initiate all sensor readings over serial!
        serialLoggingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (serialLogging) {
                    serialLoggingButton.setText("start logging");
                    serialLoggingButton.setBackgroundColor(Color.parseColor("#FF0000")); // sets background color to red
                    serialLogging = false;
                    stopLoggingAllSensors();
                } else {
                    serialLoggingButton.setText("stop logging");
                    serialLoggingButton.setBackgroundColor(Color.parseColor("#00FF00")); // sets background color to red
                    serialLogging = true;
                    startLoggingAllSensors(false);
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
        unregisterReceiver(usbPermissionReceiver);
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

    // Trigger new location updates at interval
    protected void startLocationUpdates() {
        // Set up the reoccurring request.
        locationRequest = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setWaitForAccurateLocation(false)
                .setMinUpdateIntervalMillis(1000)
                .setMaxUpdateDelayMillis(5000)
                .build();

        // Added location settings request?
        LocationSettingsRequest.Builder builder = new LocationSettingsRequest.Builder()
                .addLocationRequest(locationRequest);
        SettingsClient client = LocationServices.getSettingsClient(this);
        Task<LocationSettingsResponse> task = client.checkLocationSettings(builder.build());

        task.addOnSuccessListener(this, new OnSuccessListener<LocationSettingsResponse>() {
            @Override
            public void onSuccess(LocationSettingsResponse locationSettingsResponse) {
                // All location settings are satisfied. The client can initialize
                // location requests here.
                // ...
                Log.i("LOCATION", "Successfully got location setting response");
            }
        });
        task.addOnFailureListener(this, new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception e) {
                if (e instanceof ResolvableApiException) {
                    // Location settings are not satisfied, but this can be fixed
                    // by showing the user a dialog.
                }
            }
        });

        //Toast.makeText(getApplicationContext(), "Starting Location Updates!", Toast.LENGTH_SHORT).show();

        Log.i("LOCATION", "Starting location requests.");

        // TODO: Maybe move this to on create?
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult locationResult) {
                if (locationResult == null) {
                    Log.i("LOCATION", "Null result...");
                    //Toast.makeText(getApplicationContext(), "LocationCallback NULL", Toast.LENGTH_SHORT).show();
                    return;
                }
                for (Location location : locationResult.getLocations()) {
                    if (location != null) {
                        double latitude = location.getLatitude();
                        double longitude = location.getLongitude();
                        String locationString = String.format(Locale.US, "%s -- %s", latitude, longitude);
                        long timestamp = System.currentTimeMillis();
                        String locationRow = timestamp + "," + locationString;
                        // Only offer to queue if the start button has been pressed.
                        if (locationFileWritingThread != null) {
                            locationQueue.offer(locationRow);
                        }
                        locationIndicator.setText(locationString);
                        Log.i("LOCATION", "Got a location at " + latitude + " " + longitude);
                        //Toast.makeText(getApplicationContext(), "LocationCallback SUCCESS!", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        };

        // Just a check to make sure locationClient is defined.
        if (mFusedLocationClient == null) {
            mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                Log.i("LOCATION", "Could not get permission.");
                return;
            }
        }
        // Connection locationClient to locationRequest and callback.
        mFusedLocationClient.requestLocationUpdates(locationRequest,
                locationCallback,
                Looper.getMainLooper());
    }

    public static class UsbSerialReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(action)) {
                // Routine executed when attaching a USB device.
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    // call method to set up device communication
                    UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
                    HashMap<String, UsbDevice> deviceList = usbManager.getDeviceList();
                    for (UsbDevice d : deviceList.values()) {
                        int vendorId = d.getVendorId();
                        int productId = d.getProductId();
                        Log.i("USB_DEVICE_INFO", "VID: " + vendorId + " PID: " + productId);
                    }
                    PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE);
                    usbManager.requestPermission(device, permissionIntent);
                }
            } else if ("android.hardware.usb.action.USB_DEVICE_DETACHED".equals(action)) {
                // Routine executed when detaching the USB device.
                UsbDevice detachedDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (serialPort != null) {
                    serialPort.close();
                }
                // If logging was still left on before USB unplugged, stop it by programmatically pressing the button.
                serialLoggingButton.setEnabled(false);
                // TODO this does not work and sometimes gets out of phase.
                //if(serialLoggingButton.getText().toString().equals("start logging")) {
                //    serialLoggingButton.performClick();
                //}
            }
        }
    }

    UsbSerialInterface.UsbReadCallback mCallback = new UsbSerialInterface.UsbReadCallback() {
        @Override
        public void onReceivedData(byte[] arg0) {
            // Convert bytes to string
            if (serialLogging) {

                String data = new String(arg0, StandardCharsets.UTF_8);
                Log.i("SerialData", data);

                String[] parts = data.trim().split("\\s+"); // Split the data by whitespace

                int sensorId;

                try {
                    sensorId = Integer.parseInt(parts[0]);
                } catch (NumberFormatException e) {
                    // Handle invalid number format
                    return;
                }

                // Only
                if (parts.length >= 3 && sensorId == 1 || sensorId == 2) {
                    long timestamp = System.currentTimeMillis();

                    int firstSensorIndex;
                    int readCounter;

                    try {
                        firstSensorIndex = Integer.parseInt(parts[2]);
                        readCounter = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException e) {
                        // Handle invalid number format
                        return;
                    }

                    // Replace the sensor ID with a timestamp and create a CSV row.
                    parts[0] = timestamp + "";
                    // Merging the array back into a comma-separated string
                    String row = String.join(", ", parts);


                    Log.i("SerialRow", row);

                    if (sensorId == 1) {
                        rearSensorDataQueueSerial.offer(row);
                    } else if (sensorId == 2) {
                        sideSensorDataQueueSerial.offer(row);
                    }

                    // Since serial is so fast, just update every 10 reads.
                    if (readCounter % 10 == 0) {
                        // (Optional) Update the UI or handle the data as required
                        // Remember, UI updates must be run on the main thread
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                // Update UI with data
                                textView.setText(firstSensorIndex + ""); // Example if you have a TextView for display
                            }
                        });
                    }

                } else {
                    // not a sensor reading.
                }
            }
        }
    };

    BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if(device != null){
                            // Permission granted - setup the device
                            Log.i("SERIAL", "Permission granted for device " + device);
                            UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
                            connection = usbManager.openDevice(device);
                            if (connection != null) {
                                serialPort = UsbSerialDevice.createUsbSerialDevice(device, connection);
                                if (serialPort != null) {
                                    if (serialPort.open()) {
                                        // Set Serial Connection Parameters.
                                        serialPort.setBaudRate(115200);
                                        serialPort.setDataBits(UsbSerialInterface.DATA_BITS_8);
                                        serialPort.setStopBits(UsbSerialInterface.STOP_BITS_1);
                                        serialPort.setParity(UsbSerialInterface.PARITY_NONE);
                                        serialPort.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);
                                        serialPort.read(mCallback);
                                        serialLoggingButton.setEnabled(true);
                                    }
                                }
                            } else {
                                Log.i("SERIAL", "SERIAL CONNECTION IS NULL...");
                            }
                        }
                    } else {
                        // Permission denied
                        Log.i("SERIAL!", "permission defied for device: " + device);
                    }
                }
            }
        }
    };

}