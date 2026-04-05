package com.example.stbgateway

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import java.util.UUID

@SuppressLint("MissingPermission")
class BluetoothGateway(
    private val context: Context,
    private val onLog: (String) -> Unit
) {

    private val adapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    private val nusServiceUuid =
        UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val nusRxUuid =
        UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private val nusTxUuid =
        UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    fun connectToWatchByName(targetName: String = "ZEPHYR") {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            onLog("BLE scanner indisponibil")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        onLog("Pornesc scan BLE pentru: $targetName")

        scanner.startScan(null, settings, object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val name = device.name ?: result.scanRecord?.deviceName ?: "(fara nume)"
                onLog("Gasit BLE: $name / ${device.address}")

                if (name.equals(targetName, ignoreCase = true)) {
                    onLog("Am gasit ceasul. Ma conectez...")
                    scanner.stopScan(this)
                    gatt?.close()
                    gatt = device.connectGatt(context, false, gattCallback)
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                for (result in results) {
                    val device = result.device
                    val name = device.name ?: result.scanRecord?.deviceName ?: "(fara nume)"
                    onLog("Batch BLE: $name / ${device.address}")
                }
            }

            override fun onScanFailed(errorCode: Int) {
                onLog("Scan BLE esuat: $errorCode")
            }
        })
    }

    fun sendText(message: String) {
        val ch = rxCharacteristic
        val g = gatt

        if (ch == null || g == null) {
            onLog("RX characteristic indisponibila")
            return
        }

        val payload = "*$message#"
        val chunkSize = 18

        Thread {
            var index = 0
            while (index < payload.length) {
                val end = minOf(index + chunkSize, payload.length)
                val chunk = payload.substring(index, end)

                ch.value = chunk.toByteArray(Charsets.UTF_8)
                val ok = g.writeCharacteristic(ch)
                onLog("Trimit chunk: \"$chunk\" -> ${if (ok) "OK" else "FAIL"}")

                Thread.sleep(80)
                index = end
            }
        }.start()
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        rxCharacteristic = null
        onLog("Deconectat.")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            onLog("onConnectionStateChange status=$status newState=$newState")

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onLog("Conectat GATT. Descopar serviciile...")
                    gatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    onLog("Deconectat.")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            onLog("Servicii descoperite, caut NUS...")
            val service = gatt.getService(nusServiceUuid)

            if (service == null) {
                onLog("Serviciul NUS nu a fost gasit")
                return
            }

            rxCharacteristic = service.getCharacteristic(nusRxUuid)

            if (rxCharacteristic == null) {
                onLog("RX characteristic nu a fost gasita")
            } else {
                onLog("RX characteristic gasita, gata de trimitere")
            }
        }
    }
}