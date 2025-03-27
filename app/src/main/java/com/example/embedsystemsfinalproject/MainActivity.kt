package com.example.embedsystemsfinalproject

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var tvData: TextView
    private lateinit var lvDevices: ListView
    private val discoveredDevices = mutableListOf<BluetoothDevice>()
    private lateinit var devicesAdapter: ArrayAdapter<String>
    private var bluetoothGatt: BluetoothGatt? = null
    private var bleScanner: BluetoothLeScanner? = null
    private var bleScanCallback: ScanCallback? = null
    private val REQUEST_CODE_BLE_PERMISSIONS = 1
    private val TAG = "MyProject"

    // UUIDs for your ESP32 BLE service and characteristic
    private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
    private val CHARACTERISTIC_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
    private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    @RequiresApi(Build.VERSION_CODES.S)
    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: Started")
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Setup UI – ensure your layout has a TextView with id "tvData"
        tvData = findViewById(R.id.tvData)
        lvDevices = findViewById(R.id.lvDevices)
        devicesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        lvDevices.adapter = devicesAdapter

        lvDevices.setOnItemClickListener { _, _, position, _ ->
            // Stop scanning when a device is selected
            bleScanner?.let { scanner ->
                bleScanCallback?.let { callback ->
                    scanner.stopScan(callback)
                }
            }
            val selectedDevice = discoveredDevices[position]
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                // Permission not granted; exit the lambda
                return@setOnItemClickListener
            }
            connectToDevice(selectedDevice)
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        if (checkAndRequestPermissions()) {
            startBleScan()
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun checkAndRequestPermissions(): Boolean {
        Log.d(TAG, "checkAndRequestPermissions: Checking permissions")
        val permissions = mutableListOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        val missingPermissions = permissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missingPermissions.isNotEmpty()) {
            Log.d(TAG, "checkAndRequestPermissions: Missing permissions: $missingPermissions")
            requestPermissions(missingPermissions.toTypedArray(), REQUEST_CODE_BLE_PERMISSIONS)
            return false
        } else {
            Log.d(TAG, "checkAndRequestPermissions: All permissions granted")
            return true
        }
    }

    @SuppressLint("SetTextI18n")
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        Log.d(TAG, "onRequestPermissionsResult: requestCode=$requestCode, permissions=${permissions.joinToString()}, grantResults=${grantResults.joinToString()}")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_BLE_PERMISSIONS) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.d(TAG, "onRequestPermissionsResult: Permissions granted")
                startBleScan()
            } else {
                Log.d(TAG, "onRequestPermissionsResult: Permissions denied")
                tvData.text = "Permissions required for BLE functionality"
            }
        }
    }

    @SuppressLint("SetTextI18n")
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    private fun startBleScan() {
        Log.d(TAG, "startBleScan: Starting BLE scan")
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Log.d(TAG, "startBleScan: BLE not supported on this device")
            tvData.text = "BLE not supported on this device."
            return
        }

        if (!bluetoothAdapter.isEnabled) {
            Log.d(TAG, "startBleScan: Bluetooth is disabled")
            tvData.text = "Please enable Bluetooth."
            return
        }

        // Add bonded (paired) devices to the list in case they're not advertising now
        val bondedDevices = bluetoothAdapter.bondedDevices
        for (device in bondedDevices) {
            if (!discoveredDevices.any { it.address == device.address }) {
                discoveredDevices.add(device)
                val displayName = device.name ?: device.address
                Log.d(TAG, "startBleScan: Bonded device found: $displayName")
                runOnUiThread {
                    devicesAdapter.add(displayName)
                    devicesAdapter.notifyDataSetChanged()
                }
            }
        }

        bleScanner = bluetoothAdapter.bluetoothLeScanner
        if (bleScanner == null) {
            Log.d(TAG, "startBleScan: BLE scanner is not available")
            tvData.text = "BLE scanner is not available."
            return
        }

        bleScanCallback = object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.device?.let { device ->
                    if (!discoveredDevices.any { it.address == device.address }) {
                        discoveredDevices.add(device)
                        val displayName = device.name ?: device.address
                        Log.d(TAG, "onScanResult: Found device: $displayName")
                        runOnUiThread {
                            devicesAdapter.add(displayName)
                            devicesAdapter.notifyDataSetChanged()
                        }
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.d(TAG, "onScanFailed: errorCode=$errorCode")
                runOnUiThread {
                    tvData.text = "BLE scan failed with error: $errorCode"
                }
            }
        }

        tvData.text = "Scanning for BLE devices..."
        Log.d(TAG, "startBleScan: Initiating scan")
        bleScanner?.startScan(bleScanCallback)
    }

    @SuppressLint("SetTextI18n")
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "connectToDevice: Attempting to connect to device: ${device.name ?: device.address}")
        tvData.text = "Connecting to ${device.name}..."
        bluetoothGatt = device.connectGatt(this, false, object : BluetoothGattCallback() {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                Log.d(TAG, "onConnectionStateChange: status=$status, newState=$newState for device ${device.name ?: device.address}")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    runOnUiThread {
                        tvData.text = "Connected to ${device.name}. Requesting MTU..."
                    }
                    gatt?.requestMtu(100)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    runOnUiThread {
                        tvData.text = "Disconnected from ${device.name}."
                    }
                }
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                Log.d(TAG, "onMtuChanged: New MTU = $mtu, status = $status")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    runOnUiThread {
                        tvData.text = "MTU updated to $mtu. Discovering services..."
                    }
                    gatt?.discoverServices()
                } else {
                    runOnUiThread {
                        tvData.text = "Failed to update MTU. Discovering services..."
                    }
                    gatt?.discoverServices()
                }
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                Log.d(TAG, "onServicesDiscovered: status=$status for device ${device.name ?: device.address}")
                val service = gatt?.getService(SERVICE_UUID)
                val characteristic = service?.getCharacteristic(CHARACTERISTIC_UUID)
                if (characteristic != null) {
                    // Enable notifications locally
                    gatt.setCharacteristicNotification(characteristic, true)
                    val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                    if (descriptor != null) {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                        runOnUiThread {
                            tvData.text = "Notifications enabled. Waiting for data..."
                        }
                        Log.d(TAG, "onServicesDiscovered: Notifications enabled")
                    } else {
                        runOnUiThread {
                            tvData.text = "Descriptor not found."
                        }
                        Log.d(TAG, "onServicesDiscovered: Descriptor not found")
                    }
                } else {
                    runOnUiThread {
                        tvData.text = "Service/Characteristic not found."
                    }
                    Log.d(TAG, "onServicesDiscovered: Service/Characteristic not found")
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
                val value = characteristic?.value
                val data = if (value != null) String(value) else ""
                Log.d(TAG, "onCharacteristicChanged: value=$data")
                runOnUiThread {
                    tvData.text = data
                }
            }
        })
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDestroy() {
        Log.d(TAG, "onDestroy: Cleaning up BluetoothGatt")
        super.onDestroy()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}