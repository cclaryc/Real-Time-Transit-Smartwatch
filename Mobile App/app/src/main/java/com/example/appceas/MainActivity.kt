package com.example.appceas

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.stbgateway.BluetoothGateway
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private var gateway: BluetoothGateway? = null
    private lateinit var logView: TextView
    private lateinit var stopIdInput: EditText
    private lateinit var messageInput: EditText

    private val stbClient = StbClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestBtPermissions()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val deviceNameInput = EditText(this).apply {
            hint = "Nume device BLE"
            setText("ZEPHYR")
        }

        stopIdInput = EditText(this).apply {
            hint = "Stop ID"
            setText("3901")
        }

        messageInput = EditText(this).apply {
            hint = "Mesaj manual către ceas"
            setText("test")
        }

        val connectBtn = Button(this).apply {
            text = "Conectare la ceas"
        }

        val sendBtn = Button(this).apply {
            text = "Trimite test"
        }

        val fetchAndSendBtn = Button(this).apply {
            text = "Ia STB si trimite"
        }

        val disconnectBtn = Button(this).apply {
            text = "Deconectare"
        }

        logView = TextView(this).apply {
            text = "Log:\n"
            textSize = 14f
        }

        val scroll = ScrollView(this).apply {
            addView(logView)
        }

        root.addView(deviceNameInput)
        root.addView(stopIdInput)
        root.addView(messageInput)
        root.addView(connectBtn)
        root.addView(sendBtn)
        root.addView(fetchAndSendBtn)
        root.addView(disconnectBtn)
        root.addView(scroll)

        setContentView(root)

        gateway = BluetoothGateway(this) { msg ->
            runOnUiThread {
                logView.append("$msg\n")
            }
        }

        connectBtn.setOnClickListener {
            val name = deviceNameInput.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "Numele device-ului nu poate fi gol.", Toast.LENGTH_SHORT).show()
            } else {
                appendLog("Incerc conectarea la $name...\n")
                gateway?.connectToWatchByName(name)
            }
        }

        sendBtn.setOnClickListener {
            val msg = messageInput.text.toString()
            if (msg.isEmpty()) {
                Toast.makeText(this, "Mesajul nu poate fi gol.", Toast.LENGTH_SHORT).show()
            } else {
                appendLog("Trimit manual: $msg\n")
                gateway?.sendText(msg)
            }
        }

        fetchAndSendBtn.setOnClickListener {
            val stopId = stopIdInput.text.toString().trim()
            if (stopId.isEmpty()) {
                Toast.makeText(this, "Stop ID nu poate fi gol.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            appendLog("Cer date STB pentru statia $stopId...\n")

            thread(start = true) {
                val result = stbClient.checkStb(stopId)

                runOnUiThread {
                    appendLog("Rezultat STB: $result\n")
                    messageInput.setText(result)
                }

                gateway?.sendText(result)
            }
        }

        disconnectBtn.setOnClickListener {
            appendLog("Deconectare...\n")
            gateway?.disconnect()
        }
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            logView.append(msg)
        }
    }

    private fun requestBtPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val needed = mutableListOf<String>()

            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }

            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }

            if (needed.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1)
            }
        } else {
            val needed = mutableListOf<String>()

            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }

            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }

            if (needed.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, needed.toTypedArray(), 2)
            }
        }
    }
}