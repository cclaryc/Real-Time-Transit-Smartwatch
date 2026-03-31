package com.example.appceas

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.stbgateway.BluetoothGateway

class MainActivity : Activity() { // Am schimbat AppCompatActivity în Activity simplu
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Cerem permisiuni
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
                    1
                )
            }
        }

        // 2. Creăm interfața din cod
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        val macInput = EditText(this).apply {
            hint = "Adresa MAC"
            setText("B4:8C:9D:31:5C:3C") // Adresa MAC a laptopului tău
        }

        val connectBtn = Button(this).apply {
            text = "Conectare Bluetooth & Start"
        }

        layout.addView(macInput)
        layout.addView(connectBtn)
        setContentView(layout)

        // 3. Acțiunea butonului
        connectBtn.setOnClickListener {
            val mac = macInput.text.toString().trim()
            if (mac.isNotEmpty()) {
                Toast.makeText(this, "Încerc conectarea la $mac...", Toast.LENGTH_SHORT).show()

                try {
                    val gateway = BluetoothGateway(mac)
                    gateway.startListening()
                } catch (e: Exception) {
                    Toast.makeText(this, "Eroare la pornire: ${e.message}", Toast.LENGTH_LONG).show()
                }

            } else {
                Toast.makeText(this, "Adresa MAC nu poate fi goală!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}