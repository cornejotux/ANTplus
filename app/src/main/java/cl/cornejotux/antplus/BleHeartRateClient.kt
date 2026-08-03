package cl.cornejotux.antplus

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import java.util.UUID

class BleHeartRateClient(private val context: Context, private val listener: Listener) {
    interface Listener { fun onStatus(message: String); fun onHeartRate(bpm: Int) }
    private val serviceUuid = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val measurementUuid = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val adapter = (context.getSystemService(BluetoothManager::class.java)).adapter
    private var scanner = adapter?.bluetoothLeScanner
    private var gatt: BluetoothGatt? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (adapter == null || !adapter.isEnabled) { listener.onStatus("Bluetooth está desactivado"); return }
        listener.onStatus("Buscando monitor por Bluetooth LE...")
        scanner?.startScan(listOf(ScanFilter.Builder().setServiceUuid(android.os.ParcelUuid(serviceUuid)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
    }

    @SuppressLint("MissingPermission")
    fun stop() { scanner?.stopScan(callback); gatt?.close(); gatt = null }

    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission") override fun onScanResult(type: Int, result: ScanResult) {
            scanner?.stopScan(this); listener.onStatus("Sensor BLE encontrado: ${result.device.name ?: "ThinkRider"}")
            gatt = result.device.connectGatt(context, false, gattCallback)
        }
        override fun onScanFailed(errorCode: Int) { listener.onStatus("Error de búsqueda BLE: $errorCode") }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission") override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            if (state == BluetoothProfile.STATE_CONNECTED) {
                listener.onStatus("Sensor BLE conectado; descubriendo servicios...")
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.discoverServices()
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                listener.onStatus("Sensor BLE desconectado (código $status)")
                g.close()
            }
        }
        @SuppressLint("MissingPermission") override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { listener.onStatus("Error descubriendo servicios BLE ($status)"); return }
            val characteristic = g.getService(serviceUuid)?.getCharacteristic(measurementUuid)
            if (characteristic == null) { listener.onStatus("El sensor no expone Heart Rate BLE"); return }
            g.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(cccdUuid)
            if (descriptor == null) { listener.onStatus("Falta descriptor de notificaciones BLE"); return }
            val supportsNotify = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            val supportsIndicate = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            descriptor.value = if (supportsNotify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (!supportsNotify && !supportsIndicate) { listener.onStatus("El sensor no permite notificaciones Heart Rate"); return }
            listener.onStatus("Notificaciones BLE activadas; esperando BPM...")
            g.writeDescriptor(descriptor)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) listener.onStatus("No se pudieron activar notificaciones BLE ($status)")
            else listener.onStatus("Notificaciones BLE listas; esperando BPM...")
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) { parse(value) }
        @Suppress("DEPRECATION") override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { c.value?.let(::parse) }
        private fun parse(data: ByteArray) { if (data.size >= 2) listener.onHeartRate(if ((data[0].toInt() and 1) == 0) data[1].toInt() and 255 else if (data.size >= 3) (data[1].toInt() and 255) or ((data[2].toInt() and 255) shl 8) else 0) }
    }
}
