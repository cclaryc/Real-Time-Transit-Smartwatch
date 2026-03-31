package com.example.stbgateway // Asigură-te că pachetul este corect pentru proiectul tău!

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.Scanner
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

// Extensie utilă pentru a găsi secvențe de bytes (echivalentul lui .find() din Python)
fun ByteArray.indexOf(pattern: ByteArray, startIndex: Int = 0): Int {
    if (pattern.isEmpty()) return -1
    for (i in startIndex..this.size - pattern.size) {
        var match = true
        for (j in pattern.indices) {
            if (this[i + j] != pattern[j]) {
                match = false
                break
            }
        }
        if (match) return i
    }
    return -1
}

@SuppressLint("MissingPermission")
class BluetoothGateway(private val macAddress: String) {

    private val MY_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null

    private var currentAppId = ""
    private var currentUserInfo = ""

    // Inițializăm clientul HTTP o singură dată pentru eficiență
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun startListening() {
        thread(start = true) {
            try {
                // 1. Luăm cheile STB
                val authPair = generateStbSession()
                currentAppId = authPair.first
                currentUserInfo = authPair.second

                // 2. Ne conectăm la Bluetooth
                val adapter = BluetoothAdapter.getDefaultAdapter()
                val device: BluetoothDevice = adapter.getRemoteDevice(macAddress)
                socket = device.createRfcommSocketToServiceRecord(MY_UUID)
                socket?.connect()

                println("Conectat la HC-05! Aștept comenzi...")

                val inputStream: InputStream = socket!!.inputStream
                val outputStream: OutputStream = socket!!.outputStream
                val scanner = Scanner(inputStream)

                // 3. Ascultăm comenzile de la plăcuță
                while (true) {
                    if (scanner.hasNextLine()) {
                        val command = scanner.nextLine().trim()
                        println("Am primit: $command")

                        if (command.startsWith("REQ:")) {
                            val stopId = command.split(":")[1]

                            var result = checkStb(currentAppId, currentUserInfo, stopId)

                            if (result == "EXPIRED") {
                                println("Sesiune expirată, reînnoim...")
                                val newAuth = generateStbSession()
                                currentAppId = newAuth.first
                                currentUserInfo = newAuth.second
                                result = checkStb(currentAppId, currentUserInfo, stopId)
                            }

                            // Împachetăm răspunsul cu * și # pentru a fi ușor de citit de C/C++
                            val pachetFinal = "*$result#\n"
                            outputStream.write(pachetFinal.toByteArray())
                            outputStream.flush()
                            println("Trimis spre placă: $pachetFinal")
                        }
                    }
                }
            } catch (e: Exception) {
                println("Eroare Gateway: ${e.message}")
            }
        }
    }

    // --- FUNCȚIA DE AUTENTIFICARE (Din Python in Kotlin) ---
    private fun generateStbSession(): Pair<String, String> {
        val appId = UUID.randomUUID().toString()
        val urlAuth = "https://info.stb.ro/api/web/v2-6/proxy/user/auth"
        val appKey = "gcALgRyZHC,qFonZ=Jde"

        val request = Request.Builder()
            .url(urlAuth)
            .header("App-key", appKey)
            .header("App-Id", appId)
            .header("Source", "ro.radcom.smartcity.web")
            .header("Accept", "application/json")
            .build()

        try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val jsonData = response.body?.string() ?: return Pair("", "")
                val jsonObject = JSONObject(jsonData)
                val userInfo = jsonObject.optJSONObject("data")?.optString("userInfo") ?: ""

                if (userInfo.isNotEmpty()) {
                    return Pair(appId, userInfo)
                }
            }
        } catch (e: Exception) {
            println("Eroare Auth: ${e.message}")
        }
        return Pair("", "")
    }

    // --- FUNCȚIA DE DECODARE VARINT ---
    private fun decodeProtobufVarint(data: ByteArray, startPos: Int): Pair<Int, Int> {
        var res = 0
        var shift = 0
        var pos = startPos
        while (pos < data.size) {
            val b = data[pos].toInt()
            res = res or ((b and 0x7f) shl shift)
            pos++
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return Pair(res, pos)
    }

    // --- FUNCȚIA PRINCIPALĂ DE PARSARE BINARĂ ---
    private fun checkStb(appId: String, userInfo: String, stopId: String): String {
        val urlApi = "https://info.stb.ro/api/web/v2-6/lines/stop?stop_id=$stopId"

        val request = Request.Builder()
            .url(urlApi)
            .header("App-Id", appId)
            .header("User-Info", userInfo)
            .header("Source", "ro.radcom.smartcity.web")
            .header("Accept", "application/x-protobuf")
            .build()

        try {
            val response = client.newCall(request).execute()

            if (response.code == 401 || response.code == 412) return "EXPIRED"
            if (!response.isSuccessful) return "Eroare Server"

            val data = response.body?.bytes() ?: return "Pachet Gol"
            val linesFound = mutableMapOf<String, String>()
            var idx = 0

            while (idx < data.size) {
                if (data[idx] == 0x0A.toByte()) {
                    try {
                        val nameLen = data[idx + 1].toInt()
                        val lineName = String(data, idx + 2, nameLen, Charsets.UTF_8)

                        if (nameLen in 1..5 && lineName.all { it.isLetterOrDigit() }) {
                            var endIdx = data.size
                            var nextSearch = idx + 2 + nameLen

                            // Izolăm blocul
                            while (true) {
                                val next0a = data.indexOf(byteArrayOf(0x0A.toByte()), nextSearch)
                                if (next0a == -1) break
                                try {
                                    val nxtLen = data[next0a + 1].toInt()
                                    if (nxtLen in 1..5) {
                                        val nxtStr = String(data, next0a + 2, nxtLen, Charsets.UTF_8)
                                        if (nxtStr.all { it.isLetterOrDigit() } &&
                                            (nxtStr[0].isDigit() || nxtStr.startsWith("N") || nxtStr.startsWith("R"))) {
                                            endIdx = next0a
                                            break
                                        }
                                    }
                                } catch (e: Exception) {}
                                nextSearch = next0a + 1
                            }

                            val block = data.copyOfRange(idx + 2 + nameLen, endIdx)
                            if (!linesFound.containsKey(lineName)) linesFound[lineName] = ""

                            val foundTimesInt = mutableListOf<Int>()

                            // Căutăm prima sosire (0x30, 0x40)
                            for (marker in listOf(byteArrayOf(0x30), byteArrayOf(0x40))) {
                                var sPos = 0
                                while (true) {
                                    val pos = block.indexOf(marker, sPos)
                                    if (pos == -1) break
                                    try {
                                        val (seconds, _) = decodeProtobufVarint(block, pos + 1)
                                        if (seconds in 16..17999) foundTimesInt.add(seconds / 60)
                                    } catch (e: Exception) {}
                                    sPos = pos + 1
                                }
                            }

                            // Căutăm restul sosirilor (0x4A)
                            var sPosLista = 0
                            while (true) {
                                val pos = block.indexOf(byteArrayOf(0x4A), sPosLista)
                                if (pos == -1) break
                                try {
                                    val (msgLen, contentStart) = decodeProtobufVarint(block, pos + 1)
                                    val subMsg = block.copyOfRange(contentStart, contentStart + msgLen)

                                    val subPos = subMsg.indexOf(byteArrayOf(0x10))
                                    if (subPos != -1) {
                                        val (seconds, _) = decodeProtobufVarint(subMsg, subPos + 1)
                                        if (seconds in 16..17999) foundTimesInt.add(seconds / 60)
                                    }
                                } catch (e: Exception) {}
                                sPosLista = pos + 1
                            }

                            // Formatare identică cu Python (limita de 3)
                            if (foundTimesInt.isNotEmpty()) {
                                val uniqueTimes = foundTimesInt.distinct().sorted().take(3)
                                val formattedTimes = uniqueTimes.map { m ->
                                    if (m < 60) "$m min"
                                    else {
                                        val h = m / 60
                                        val remM = m % 60
                                        val oreStr = if (h == 1) "oră" else "ore"
                                        if (remM == 0) "$h $oreStr" else "$h $oreStr, $remM min"
                                    }
                                }
                                linesFound[lineName] = formattedTimes.joinToString(", ")
                            }
                        }
                        idx += 2 + nameLen
                    } catch (e: Exception) {
                        idx++
                    }
                } else {
                    idx++
                }
            }

            // Dacă avem date, le lipim cu '|' între ele
            if (linesFound.isNotEmpty()) {
                return linesFound.map { "${it.key}:${it.value}" }.joinToString("|")
            }
            return "Nicio masina"

        } catch (e: Exception) {
            return "Eroare Script"
        }
    }
}