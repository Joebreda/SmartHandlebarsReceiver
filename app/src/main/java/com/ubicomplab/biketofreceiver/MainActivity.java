package com.ubicomplab.biketofreceiver;

import android.Manifest;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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
import android.os.Bundle;
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
    private int[] frame = new int[8*8];
    private int pixel_index = 0;
    private int changeCounter = 0;
    private String formattedDateTime;
    private File output_file;
    //private ByteBuffer buffer = ByteBuffer.allocate(64 * 2); // Each integer is 2 bytes
    private final Object fileLock = new Object();
    private ConcurrentLinkedQueue<Byte> dataQueue = new ConcurrentLinkedQueue<>();
    private Thread fileWritingThread;
    private TextView textView;
    private volatile boolean keepRunning = true;
    DateTimeFormatter formatter;
    private int frame_mean = 0;



    private static final int MULTIPLE_PERMISSIONS_REQUEST_CODE = 123;

    public void printFrame(int[] frame) {
        Log.i("NEW FRAME", "FRAME: " + frame.length);
        int i = 0;
        while (i < frame.length) {
            StringBuilder row = new StringBuilder();

            for (int j = 0; j < 8; j++) {
                row.append(frame[i]);
                row.append("  ");
                i++;
            }

            Log.i("row", row.toString());
        }
    }

    public void updateTextView(String toThis) {
        TextView textView = (TextView) findViewById(R.id.textView);
        textView.setText(toThis);
    }

    private void writeBufferToFile(ByteBuffer buffer) {
        StringBuilder csvLine = new StringBuilder();
        long timestamp = System.currentTimeMillis();
        csvLine.append(timestamp);
        frame_mean = 0;

        while (buffer.hasRemaining()) {
            int value = buffer.getShort() & 0xFFFF;
            frame_mean = frame_mean + value;
            csvLine.append(",").append(value);
        }
        frame_mean = frame_mean / 64;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Update your TextView here
                textView.setText(frame_mean + "");
            }
        });

        try (BufferedWriter bw = new BufferedWriter(new FileWriter(output_file, true))) {
            bw.write(csvLine.toString());
            bw.newLine();
        } catch (IOException e) {
            // Handle IOException
        }
    }

    private synchronized boolean isFileWritingThreadRunning() {
        return fileWritingThread != null && fileWritingThread.isAlive();
    }

    private synchronized void startFileWritingThread() {
        if (isFileWritingThreadRunning()) {
            return; // The thread is already running
        }
        keepRunning = true;

        fileWritingThread = new Thread(() -> {
            ByteBuffer buffer = ByteBuffer.allocate(64 * 2); // Adjust size as needed
            while (keepRunning) {
                while (!dataQueue.isEmpty()) {
                    buffer.put(dataQueue.poll());

                    if (buffer.position() >= 64 * 2) {
                        buffer.flip(); // Prepare for reading from the buffer
                        writeBufferToFile(buffer);
                        buffer.clear();
                    }
                }

                // Optional: Sleep a bit if queue is empty to reduce CPU usage
                try {
                    Thread.sleep(10); // Sleep for 10 milliseconds
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        fileWritingThread.start();
    }

    private synchronized void stopFileWritingThread() {
        if (!isFileWritingThreadRunning()) {
            return; // The thread is not running
        }
        keepRunning = false;
        fileWritingThread.interrupt();
        try {
            fileWritingThread.join(); // Wait for the thread to finish
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        fileWritingThread = null; // Clear the thread reference
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
                String filename = getExternalFilesDir(null) + "/" + formattedDateTime + ".csv";
                Log.i("FILEPATH:", filename + "");
                output_file = new File(filename);
                frame_mean = 0;

                if (!isFileWritingThreadRunning()) {
                    startFileWritingThread();
                }
                mBluetoothGatt.discoverServices();
                Log.i("BLE", "Attempting to start service discovery");

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i("BLE", "Disconnected from GATT server.");
                stopFileWritingThread(); // Stop the file writing thread
                dataQueue.clear(); // Clear the data queue
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
                Log.i("Received data", "" + timestamp);
                // Add received data to the queue
                for (byte b : data) {
                    dataQueue.offer(b);
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
                Manifest.permission.WRITE_EXTERNAL_STORAGE
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mDeviceList = new ArrayList<>();
        mDeviceListAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        mDeviceListView = findViewById(R.id.deviceListView);
        mDeviceListView.setAdapter(mDeviceListAdapter);
        EditText searchEditText = findViewById(R.id.searchEditText);

        LocalDateTime now = LocalDateTime.now();
        // Format it to a human-readable string
        formatter = DateTimeFormatter.ofPattern("dd-MM-yyyy_HH:mm:ss");
        textView = (TextView) findViewById(R.id.textView);

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
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mBluetoothGatt != null) {
            if (ActivityCompat.checkSelfPermission(this,
                    Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                handlePermissionsNotGranted(Manifest.permission.BLUETOOTH_CONNECT);
                return;
            }
            mBluetoothGatt.close();
            mBluetoothGatt = null;
        }
    }
}