package com.ubicomplab.biketofreceiver;

import android.Manifest;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
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
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.telephony.TelephonyManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity {

    boolean DEBUGGING_LOG_FILE = false;
    // Serial logic variables.
    public static final String ACTION_USB_PERMISSION = "com.ubicomplab.biketofreceiver.USB_PERMISSION";

    // Server communication variables.
    private static final String serverURL = "https://homes.cs.washington.edu/~joebreda/web_server/"; //"http://10.19.127.111:8080/upload";
    private String uniqueID;
    private OkHttpClient httpUserClient = new OkHttpClient();
    private boolean cancelUpload = false;
    private int currentFileIndex = 0;
    private int chunksSent = 0;
    private File[] filesToUpload;
    private Map<String, TextView> fileTextViewMap = new HashMap<>();
    private ProgressBar progressBar;
    private AlertDialog dialog;
    private LinearLayout fileListLayout;

    static UsbSerialDevice serialPort;
    UsbDeviceConnection connection;
    static Button serialLoggingButton;
    static Button bleScanButton;
    static Button enableSwitchButton;
    boolean serialLogging;
    boolean enableSwitchButtonPressed;
    private Switch audioSwitch;
    boolean recordAudio;
    private View connectionIndicator;
    private View labelIndicator;
    private boolean bleConnected;
    private boolean disconnectButtonPressed;
    static Button uploadDataButton;
    Button cancelSendDataButton;
    Button sendDataButton;


    // BLE adapter to list off BLE devices on screen.
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBluetoothLeScanner;
    private List<BluetoothDevice> mDeviceList;  // List of devices to query using the clicked device name.
    private ArrayAdapter<String> mDeviceListAdapter;  // List of strings to display on the screen.
    private ListView mDeviceListView;
    private List<BluetoothDevice> filteredDeviceList;
    private String searchFilter;
    private boolean currentlyScanning = false;
    private String formattedDateTime;

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
    private Sensor lightSensor;
    private SensorLogger lightSensorLogger;
    private Sensor proximitySensor;
    private SensorLogger proximitySensorLogger;

    private TextView textView;
    private BroadcastReceiver updateReceiver;
    private TextView locationIndicator;
    private volatile boolean keepRunning = true;
    DateTimeFormatter formatter;

    private LocationRequest locationRequest;
    private LocationCallback locationCallback;
    private FusedLocationProviderClient mFusedLocationClient;

    private AudioRecordThread audioRecordThread;

    private static final int MULTIPLE_PERMISSIONS_REQUEST_CODE = 123;

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

    // This writer thread is used when the Serial is used and data is queued as a complete string.
    private synchronized Thread startStringRowFileWritingThread(Thread thread,
                                                                ConcurrentLinkedQueue<String> queue,
                                                                File outputFile,
                                                                String threadName) {
        // if thread is already running just return it.
        if (isFileWritingThreadRunning(thread)) {
            return thread;
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

    public void startLoggingAllSensors(boolean overBLE, String formattedDateTime) {
        // Create a new output file to write to each time you reconnection using a global datetime.
        String rearFilename = getExternalFilesDir(null) + "/" + formattedDateTime + "_rear.csv";
        String sideFilename = getExternalFilesDir(null) + "/" + formattedDateTime + "_side.csv";
        String locationFilename = getExternalFilesDir(null) + "/" + formattedDateTime + "_location.csv";

        Log.i("FILEPATH:", rearFilename + "");
        rearSensorOutputFile = new File(rearFilename);
        sideSensorOutputFile = new File(sideFilename);
        locationOutputFile = new File(locationFilename);

        // TODO Instead of making an output file a more robust approach may be:
        //Uri fileUri = createMediaStoreUri("bluetooth_data.csv");

        restartCounter++;

        lightSensorLogger.register(formattedDateTime);
        proximitySensorLogger.register(formattedDateTime);
        rotationSensorLogger.register(formattedDateTime);
        accelerometerLogger.register(formattedDateTime);
        gyroscopeLogger.register(formattedDateTime);
        magnetometerLogger.register(formattedDateTime);
        audioFilePath = getExternalFilesDir(null) + "/" + formattedDateTime + "_audio.pcm";

        // If using BLE logging occurs in service but transmission over serial is still using threads.
        if (overBLE) {
            Log.i("logging", "logging over ble using service now.");
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

        if (recordAudio) {
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
        }

        // Location recording
        startLocationUpdates();
        // For logging location file (uses a different function to define thread than other writer threads).
        if (!isFileWritingThreadRunning(locationFileWritingThread)) {
            locationFileWritingThread = startStringRowFileWritingThread(
                    locationFileWritingThread, locationQueue,
                    locationOutputFile, "locationThread" + restartCounter);
        }
    }

    // Disconnection dialog which is used to confirm whether user intends to disconnect.
    private void showDisconnectDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Disconnect")
                .setMessage("Are you sure you want to disconnect?")
                .setPositiveButton("Disconnect", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        // Perform disconnection
                        disconnectDevice();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void disconnectDevice() {
        disconnectButtonPressed = true;
        stopLoggingAllSensors();
        // Stop the BLE service
        Intent serviceIntent = new Intent(this, BleService.class);
        stopService(serviceIntent);
        connectionIndicator.setBackgroundColor(Color.RED);
        labelIndicator.setBackgroundColor(Color.BLUE);
        bleScanButton.setEnabled(true);
        bleScanButton.setText("Start Scanning");
        enableSwitchButton.setEnabled(true);
        uploadDataButton.setEnabled(true);
        bleConnected = false;
    }

    private void showFilePopup() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.file_popup, null);
        builder.setView(dialogView);

        fileListLayout = dialogView.findViewById(R.id.fileListLayout);
        progressBar = dialogView.findViewById(R.id.progressBar);
        cancelSendDataButton = dialogView.findViewById(R.id.cancelButton);
        sendDataButton = dialogView.findViewById(R.id.sendButton);
        sendDataButton.setEnabled(false);

        dialog = builder.create();

        cancelSendDataButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cancelUpload = true;
                dialog.dismiss();
            }
        });

        sendDataButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                progressBar.setVisibility(View.VISIBLE);
                cancelUpload = false;
                File dir = new File("/storage/emulated/0/Android/data/com.ubicomplab.biketofreceiver/files/");
                if (dir.exists() && dir.isDirectory()) {
                    filesToUpload = dir.listFiles();
                    Log.i("Number of files to upload before filtering", "" + filesToUpload.length);
                    filesToUpload = filterFilesToUpload(dir.listFiles(), fileTextViewMap.keySet());
                    Log.i("Number of files to upload after filtering", "" + filesToUpload.length);
                    if (filesToUpload != null && filesToUpload.length > 0) {
                        currentFileIndex = 0;
                        sendNextFile(); // Start sending files
                    }
                }
            }
        });

        dialog.show();

        new LoadFilesTask().execute();
    }

    private File[] filterFilesToUpload(File[] allFiles, Set<String> validFileNames) {
        List<File> filteredFiles = new ArrayList<>();
        for (File file : allFiles) {
            if (validFileNames.contains(file.getName())) {
                filteredFiles.add(file);
            }
        }
        return filteredFiles.toArray(new File[0]);
    }

    private void sendNextFile() {
        if (cancelUpload || currentFileIndex >= filesToUpload.length) {
            progressBar.setVisibility(View.GONE);
            updateSendButtonToDone();
            return;
        }

        File file = filesToUpload[currentFileIndex];
        String fileName = file.getName();

        // Check if the file is empty
        if (file.length() == 0) {
            Log.i("Skipping file", "File " + fileName + " is empty.");
            TextView fileTextView = fileTextViewMap.get(file.getName());
            if (fileTextView != null) {
                fileTextView.setTextColor(Color.BLUE);
            }
            currentFileIndex++;
            sendNextFile(); // Skip this file and send the next one
            return;
        }

        // Check if the file extension is .csv
        if (!fileName.endsWith(".csv")) {
            Log.i("Skipping file", "File " + fileName + " is not a CSV file.");
            TextView fileTextView = fileTextViewMap.get(file.getName());
            if (fileTextView != null) {
                fileTextView.setTextColor(Color.RED);
            }
            currentFileIndex++;
            sendNextFile(); // Skip this file and send the next one
            return;
        }

        Log.i("Sending file", "fileName: " + fileName);

        sendFileInChunks(file, new FileUploadCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    Log.i("sending file", "success!");
                    TextView fileTextView = fileTextViewMap.get(file.getName());
                    if (fileTextView != null) {
                        fileTextView.setTextColor(Color.GREEN);
                    }
                    currentFileIndex++;
                    chunksSent++;
                    Log.i("CURRENT FILE INDEX NUMBER", "" + currentFileIndex + " CHUNKS: " + chunksSent);
                    boolean deleted = deleteFile(MainActivity.this, file.getAbsolutePath());
                    sendNextFile(); // Send the next file
                });
            }

            @Override
            public void onFailure(String errorMessage) {
                runOnUiThread(() -> {
                    Log.i("sending file", "FAILED!");
                    progressBar.setVisibility(View.GONE);
                    showMessage("Upload failed: " + errorMessage);
                });
            }
        });
    }

    // Utility function to delete a file if it was successfully sent to a server.
    public static boolean deleteFile(Context context, String filePath) {
        File file = new File(filePath);
        if (file.exists()) {
            return file.delete();
        }
        return false;
    }


    private void sendFileInChunks(File file, FileUploadCallback callback) {
        final long chunkSize = 1 * 1024 * 1024; // 1 MB
        long fileSize = file.length();
        final int totalChunks = (int) Math.ceil((double) fileSize / chunkSize);

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) chunkSize];
            int chunkNumber = 1;
            int bytesRead;

            while ((bytesRead = fis.read(buffer)) != -1) {
                if (cancelUpload) {
                    progressBar.setVisibility(View.GONE);
                    return;
                }

                // Create a new file with the chunk data
                File chunkFile = new File(getCacheDir(), file.getName() + "_chunk_" + chunkNumber);
                try (FileOutputStream fos = new FileOutputStream(chunkFile)) {
                    fos.write(buffer, 0, bytesRead);
                }

                // Send the chunk
                sendFileChunk(chunkFile, chunkNumber, totalChunks, file.getName(), callback);

                // Increment chunk number
                chunkNumber++;
            }
        } catch (IOException e) {
            e.printStackTrace();
            showMessage("Error reading file: " + e.getMessage());
            callback.onFailure(e.getMessage());
        }
    }

    private void sendFileChunk(File chunkFile, int chunkNumber, int totalChunks, String originalFileName, FileUploadCallback callback) {
        Log.i("SendFileChunk", "Sending chunk " + chunkNumber + " of " + totalChunks + " for file " + originalFileName);

        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", chunkFile.getName(),
                        RequestBody.create(chunkFile, MediaType.parse("text/plain")))
                .addFormDataPart("chunk_number", String.valueOf(chunkNumber))
                .addFormDataPart("total_chunks", String.valueOf(totalChunks))
                .addFormDataPart("file_name", originalFileName)
                .addFormDataPart("unique_id", uniqueID)
                .build();

        Request request = new Request.Builder()
                .url(serverURL)
                .post(requestBody)
                .build();

        httpUserClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    Log.e("Upload failed", "Error: " + e.getMessage());
                    showMessage("Upload failed: " + e.getMessage());
                    callback.onFailure(e.getMessage());
                });
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    String responseBody = response.body().string();
                    Log.i("Upload response", responseBody);
                    if (response.isSuccessful()) {
                        runOnUiThread(() -> {
                            chunkFile.delete(); // Delete the chunk file after successful upload
                            Log.i("Upload success", "Chunk " + chunkNumber + " uploaded successfully.");
                            if (chunkNumber == totalChunks) {
                                callback.onSuccess();
                            }
                        });
                    } else {
                        runOnUiThread(() -> {
                            progressBar.setVisibility(View.GONE);
                            Log.e("Upload failed", "Response: " + responseBody);
                            showMessage("Upload failed: " + responseBody);
                            callback.onFailure(responseBody);
                        });
                    }
                } finally {
                    response.close(); // Ensure the response body is closed
                }
            }
        });
    }


    private class LoadFilesTask extends AsyncTask<Void, Void, List<String>> {
        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            progressBar.setVisibility(View.VISIBLE);
        }

        @Override
        protected List<String> doInBackground(Void... voids) {
            List<String> localFileNames = getLocalCSVFiles();
            List<String> serverFileNames = getServerFiles(uniqueID);

            localFileNames.removeAll(serverFileNames); // Filter out files that are already on the server

            return localFileNames;
        }

        @Override
        protected void onPostExecute(List<String> fileNames) {
            super.onPostExecute(fileNames);
            progressBar.setVisibility(View.GONE);
            for (String fileName : fileNames) {
                TextView fileTextView = new TextView(MainActivity.this);
                fileTextView.setText(fileName);
                fileListLayout.addView(fileTextView);
                fileTextViewMap.put(fileName, fileTextView);
            }
            // After checking server and populating list, enable send data button with list of files.
            sendDataButton.setEnabled(true);
        }

        private List<String> getLocalCSVFiles() {
            List<String> fileNames = new ArrayList<>();
            File dir = new File("/storage/emulated/0/Android/data/com.ubicomplab.biketofreceiver/files/");
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.getName().endsWith(".csv")) {
                            fileNames.add(file.getName());
                        }
                    }
                }
            }
            return fileNames;
        }

        private List<String> getServerFiles(String uniqueID) {
            OkHttpClient client = new OkHttpClient();
            String listFilesGetURL = serverURL + "?list_files=true&unique_id=" + uniqueID;

            Request request = new Request.Builder()
                    .url(listFilesGetURL)
                    .get()
                    .build();

            try {
                Response response = client.newCall(request).execute();
                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    JSONArray files = new JSONArray(responseBody);

                    List<String> fileNames = new ArrayList<>();
                    for (int i = 0; i < files.length(); i++) {
                        fileNames.add(files.getString(i));
                    }

                    response.close();
                    return fileNames;
                } else {
                    response.close();
                }
            } catch (IOException | JSONException e) {
                Log.e("getServerFiles", "Error fetching server files", e);
            }

            return new ArrayList<>();
        }

    }

    private interface FileUploadCallback {
        void onSuccess();
        void onFailure(String errorMessage);
    }

    private void showMessage(String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(message)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                });
        builder.create().show();
    }

    private void updateSendButtonToDone() {
        runOnUiThread(() -> {
            sendDataButton.setText("Done");
            sendDataButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                }
            });
        });
    }

    // Stop the file writing thread and reset the variable to null.
    private void stopLoggingAllSensors() {
        rearSensorFileWritingThread = stopFileWritingThread(rearSensorFileWritingThread);
        rearSensorDataQueueSerial.clear(); // Clear the data queue
        sideSensorFileWritingThread = stopFileWritingThread(sideSensorFileWritingThread);
        sideSensorDataQueueSerial.clear(); // Clear the data queue

        lightSensorLogger.close();
        proximitySensorLogger.close();
        rotationSensorLogger.close();
        accelerometerLogger.close();
        gyroscopeLogger.close();
        magnetometerLogger.close();
        if (recordAudio && audioRecordThread.isRecording) {
            audioRecordThread.stopRecording();
        }

        locationFileWritingThread = stopFileWritingThread(locationFileWritingThread);
        locationQueue.clear(); // Clear the data queue
    }

    public static class SensorReadingPacket {
        public int sensorIndex;
        public int packetIndex;
        public int readIndex;
        public long peripheralTimestamp;
        public long androidTimestamp;
        public int[] payload;

        public SensorReadingPacket(int sensorIndex, int packetIndex, int readIndex, long peripheralTimestamp, long androidTimestamp, int[] payload) {
            this.sensorIndex = sensorIndex;
            this.packetIndex = packetIndex;
            this.readIndex = readIndex;
            this.peripheralTimestamp = peripheralTimestamp;
            this.androidTimestamp = androidTimestamp;
            this.payload = payload;
        }

        public String getAsCSVRow() {
            StringBuilder csvRow = new StringBuilder();
            csvRow.append(sensorIndex).append(',')
                    .append(packetIndex).append(',')
                    .append(readIndex).append(',')
                    .append(peripheralTimestamp).append(',')
                    .append(androidTimestamp);

            for (int value : payload) {
                csvRow.append(',').append(value);
            }

            return csvRow.toString();
        }
    }

    private boolean checkAndRequestPermissions() {
        String[] permissions = new String[]{
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_PHONE_STATE,
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

    private void filterDeviceList(String text) {
        Log.i("Filtered device list", "filtered.");
        filteredDeviceList.clear();
        List<String> filteredListNames = new ArrayList<>();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            handlePermissionsNotGranted(Manifest.permission.BLUETOOTH_CONNECT);
            return;
        }

        for (BluetoothDevice device : mDeviceList) {
            if (device.getName() != null && device.getName().toLowerCase().contains(
                    text.toLowerCase())) {
                filteredDeviceList.add(device);
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
                    // TODO this is an alternative method for writing.
                    //this.bufferedWriter = createBufferedWriter(commonFileName + "_" + this.sensorType + ".csv");
                } catch (IOException e) {
                    e.printStackTrace();
                }
                // Initialize your sensor event listener
                this.sensorEventListener = new SensorEventWriter(this.bufferedWriter);
                if (this.sensorType.equals("light")) {
                    this.sensorManager.registerListener(this.sensorEventListener,
                            this.sensor, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler);
                } else if (this.sensorType.equals("proximity")) {
                    //this.sensorManager.registerListener(this.sensorEventListener,
                    //        this.sensor, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler);
                    this.sensorEventListener = new ProximitySensorEventListener(this.bufferedWriter);
                    this.sensorManager.registerListener(this.sensorEventListener,
                            this.sensor, SensorManager.SENSOR_DELAY_FASTEST, sensorHandler);
                } else {
                    this.sensorManager.registerListener(this.sensorEventListener,
                            this.sensor, SensorManager.SENSOR_DELAY_NORMAL, sensorHandler);
                }
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

        private class ProximitySensorEventListener implements SensorEventListener {
            private BufferedWriter writer;

            public ProximitySensorEventListener(BufferedWriter writer) {
                this.writer = writer;
            }

            @Override
            public void onSensorChanged(SensorEvent event) {
                if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
                    Log.i("PROXI", "PROXIMITY: " + event.values[0]);
                    float label = event.values[0];
                    if (label > 0) {
                        labelIndicator.setBackgroundColor(Color.BLUE);
                    } else {
                        labelIndicator.setBackgroundColor(Color.WHITE);
                    }
                }
                writeSensorDataToFile(event);
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                // Handle accuracy changes if needed
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

        textView = (TextView) findViewById(R.id.textView);

        // Write a unique id to storage for communicating with the server.
        File phoneIdFile = new File(getExternalFilesDir(null), "phone_id.txt");
        if (phoneIdFile.exists()) {
            // File already exists, do not overwrite
            Log.i("ID File", "Already exists!");
            StringBuilder stringBuilder = new StringBuilder();
            try (FileInputStream fis = new FileInputStream(phoneIdFile);
                 InputStreamReader isr = new InputStreamReader(fis);
                 BufferedReader br = new BufferedReader(isr)) {
                String line;
                while ((line = br.readLine()) != null) {
                    stringBuilder.append(line);
                }
                uniqueID = stringBuilder.toString();
                textView.setText(uniqueID);
            } catch (IOException e) {
                Log.e("MainActivity", "Error reading from file", e);
                textView.setText("failed to read phone ID");
                uniqueID = "-1";
            }
        } else {
            // File does not exist, create and store the unique ID
            uniqueID = UUID.randomUUID().toString();
            try (FileOutputStream fos = new FileOutputStream(phoneIdFile)) {
                fos.write(uniqueID.getBytes());
                Toast.makeText(this, "Unique ID stored in " + phoneIdFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                textView.setText(uniqueID);
            } catch (IOException e) {
                Log.e("MainActivity", "Error writing to file", e);
                Toast.makeText(this, "Failed to write to file", Toast.LENGTH_SHORT).show();
                textView.setText("failed to create phone ID");
                uniqueID = "-1";
            }
        }


        // Write logs to log file
        if (DEBUGGING_LOG_FILE) {
            try {
                // Define the log file
                String filename = "zzz_logcat_" + System.currentTimeMillis() + ".txt";
                File outputFile = new File(getExternalFilesDir(null), filename);

                // Start the logcat process
                Process process = Runtime.getRuntime().exec("logcat -f " + outputFile.getAbsolutePath());
            } catch (IOException e) {
                e.printStackTrace();
            }
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
        searchFilter = "SMARTHANDLEBAR";
        searchEditText.setText(searchFilter);
        filteredDeviceList = new ArrayList<>();

        // Format it to a human-readable string
        formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy_HH:mm:ss");

        // Initialize the IMU sensors.
        mySensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        lightSensor = mySensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        lightSensorLogger = new SensorLogger(
                mySensorManager, lightSensor, "light");
        proximitySensor = mySensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        proximitySensorLogger = new SensorLogger(
                mySensorManager, proximitySensor, "proximity");
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
                searchFilter = s.toString();
                filterDeviceList(s.toString());
            }
        });

        /* Connect to a device when clicked form the list present in the UI */
        mDeviceListView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Log.i("onItemClick", "Clicked device at position " + position);
                //BluetoothDevice device = mDeviceList.get(position);
                BluetoothDevice device = filteredDeviceList.get(position);
                if (ActivityCompat.checkSelfPermission(MainActivity.this,
                        Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    handlePermissionsNotGranted(Manifest.permission.BLUETOOTH_SCAN);
                    return;
                }
                //mBluetoothLeScanner.stopScan(mScanCallback);
                stopScanning();
                bleScanButton.setEnabled(false);
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
        enableSwitchButton = findViewById(R.id.enableSwitchBottom);
        enableSwitchButtonPressed = false;
        audioSwitch = findViewById(R.id.audioSwitch);
        audioSwitch.setEnabled(false);
        recordAudio = false;
        connectionIndicator = findViewById(R.id.connectionIndicator);
        labelIndicator = findViewById(R.id.labelIndicator);
        labelIndicator.setBackgroundColor(Color.BLUE);
        bleConnected = false;
        disconnectButtonPressed = false;
        uploadDataButton = findViewById(R.id.uploadData);

        bleScanButton.setBackgroundColor(Color.GREEN);
        uploadDataButton.setBackgroundColor(Color.GREEN);

        audioSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                // Code to handle switch on
                Toast.makeText(MainActivity.this, "Audio recording enabled", Toast.LENGTH_SHORT).show();
                recordAudio = true;
            } else {
                // Code to handle switch off
                Toast.makeText(MainActivity.this, "Audio recording disabled", Toast.LENGTH_SHORT).show();
                recordAudio = false;
            }
            enableSwitchButtonPressed = false;
            audioSwitch.setEnabled(false);
        });

        // Set the click listener
        bleScanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (bleConnected) {
                    showDisconnectDialog();
                } else {
                    // Check permissions and start scanning for BLE device.
                    if (currentlyScanning) {
                        if (ActivityCompat.checkSelfPermission(MainActivity.this,
                                Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                            handlePermissionsNotGranted(Manifest.permission.BLUETOOTH_SCAN);
                            return;
                        }
                        bleScanButton.setText("Start Scanning");
                        stopScanning();
                        enableSwitchButton.setEnabled(true);
                        uploadDataButton.setEnabled(true);

                    } else {
                        if (isBluetoothEnabled()) {
                            startScanning();
                            bleScanButton.setText("Stop Scanning");
                            uploadDataButton.setEnabled(false);
                            currentlyScanning = true;
                            Toast.makeText(MainActivity.this,
                                    "Button Clicked: attempting to scan.", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this,
                                    "Bluetooth is OFF, please turn it on!", Toast.LENGTH_SHORT).show();
                        }
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
                    serialLoggingButton.setBackgroundColor(Color.parseColor("#FF0000")); // sets background color to Green
                    serialLogging = false;
                    stopLoggingAllSensors();
                } else {
                    serialLoggingButton.setText("stop logging");
                    serialLoggingButton.setBackgroundColor(Color.parseColor("#00FF00")); // sets background color to red
                    serialLogging = true;
                    // Use the same start time for all files.
                    LocalDateTime now = LocalDateTime.now();
                    formattedDateTime = now.format(formatter);
                    startLoggingAllSensors(false, formattedDateTime);
                }
            }
        });

        enableSwitchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Check permissions and start scanning for BLE device.
                if (enableSwitchButtonPressed) {
                    enableSwitchButtonPressed = false;
                    audioSwitch.setEnabled(false);
                } else {
                    enableSwitchButtonPressed = true;
                    audioSwitch.setEnabled(true);

                }
            }
        });

        uploadDataButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFilePopup();
            }
        });

        // Initialize and register the BroadcastReceiver to update UI elements based on BLE activity.
        updateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();

                if ("com.example.ACTION_UPDATE_UI".equals(action)) {
                    int rearReading = intent.getIntExtra("rearReading", 0);
                    int sideReading = intent.getIntExtra("sideReading", 0);
                    long sensorTime1 = intent.getLongExtra("sensorTime1", 0);
                    long sensorTime2 = intent.getLongExtra("sensorTime2", 0);

                    // Format the readings to a fixed width of 4 characters
                    String formattedRear = String.format("%4d", rearReading);
                    String formattedSide = String.format("%4d", sideReading);
                    String delay = String.format("%4d", sensorTime1 - sensorTime2);
                    textView.setText("Rear: " + formattedRear + " side: " + formattedSide + " Delay " + delay);
                } else if ("com.example.ACTION_RECONNECTING".equals(action)) {
                    //bleScanButton.setBackgroundColor(Color.parseColor("#FFFF00")); // sets background color to Yellow
                    connectionIndicator.setBackgroundColor(Color.YELLOW);
                    bleConnected = false;
                } else if ("com.example.ACTION_DISCONNECTED".equals(action)) {
                    //bleScanButton.setBackgroundColor(Color.parseColor("#FF0000")); // sets background color to red
                    connectionIndicator.setBackgroundColor(Color.RED);
                    bleScanButton.setEnabled(true);
                    bleScanButton.setText("Start Scanning");
                    enableSwitchButton.setEnabled(true);
                    uploadDataButton.setEnabled(true);
                    bleConnected = false;
                    if (!disconnectButtonPressed)
                    stopLoggingAllSensors();
                } else if ("com.example.ACTION_CONNECTED".equals(action)) {
                    connectionIndicator.setBackgroundColor(Color.GREEN);
                    bleScanButton.setText("Disconnect");
                    bleScanButton.setEnabled(true);
                    bleConnected = true;
                }
            }
        };
        IntentFilter updateUIFilter = new IntentFilter("com.example.ACTION_UPDATE_UI");
        updateUIFilter.addAction("com.example.ACTION_CONNECTED");
        updateUIFilter.addAction("com.example.ACTION_DISCONNECTED");
        updateUIFilter.addAction("com.example.ACTION_RECONNECTING");
        registerReceiver(updateReceiver, updateUIFilter);

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
                // Upon change to the device list, filter the device list for what is in search bar.
                filterDeviceList(searchFilter);
                Log.i("onScanResult", "added and Filtered scan results");
                for (int i = 0; i < mDeviceList.toArray().length; i++) {
                    BluetoothDevice printDevice = mDeviceList.get(i);
                    Log.i("onScanResult", "Device: " + printDevice.getName() + " position: " + i);
                }

            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.i("BLEScan", "Scan Failed!");
        }
    };

    // Initiate scanning to find BLE device and connect.
    private void startScanning() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this,
                        Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED) {

            // Scan settings allow for higher throughput on BLE.
            ScanSettings scanSettings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();

            // Start the BLE scanner.
            mBluetoothLeScanner.startScan(null, scanSettings, mScanCallback);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH_ADMIN,
                            Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    private void stopScanning() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        mBluetoothLeScanner.stopScan(mScanCallback);
        // Clear the device list and notify the adapter
        mDeviceList.clear();
        mDeviceListAdapter.clear();
        mDeviceListAdapter.notifyDataSetChanged();
        bleScanButton.setText("Start Scanning");
        currentlyScanning = false;
    }


    private void connectToDevice(BluetoothDevice device) {
        if (ActivityCompat.checkSelfPermission(this,
                android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            handlePermissionsNotGranted(android.Manifest.permission.BLUETOOTH_CONNECT);
            return;
        }
        // Starts bluetooth service using the device selected.
        if (device != null) {
            Intent serviceIntent = new Intent(this, BleService.class);
            enableSwitchButton.setEnabled(false);
            LocalDateTime now = LocalDateTime.now();
            formattedDateTime = now.format(formatter);
            serviceIntent.putExtra("BluetoothDevice", device);
            serviceIntent.putExtra("startTime", formattedDateTime);
            startService(serviceIntent);
            startLoggingAllSensors(true, formattedDateTime);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // TODO moved to service!
        //closeGatt();
        unregisterReceiver(usbPermissionReceiver);
        stopLoggingAllSensors();
        unregisterReceiver(updateReceiver);
        // Stop the BLE service
        Intent serviceIntent = new Intent(this, BleService.class);
        stopService(serviceIntent);
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

    // SERIAL PROTOCOL // TODO BLE PROTOCOL AND SERIAL PROTOCOL USE DIFFERENT PACKET FORMATS RN...
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