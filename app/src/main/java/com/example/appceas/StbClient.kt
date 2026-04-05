package com.example.appceas

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class StbClient {

    private var currentAppId = ""
    private var currentUserInfo = ""

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun generateStbSession(): Boolean {
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

        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return false

            val body = response.body?.string() ?: return false
            val json = JSONObject(body)
            val userInfo = json.optJSONObject("data")?.optString("userInfo") ?: ""

            if (userInfo.isNotEmpty()) {
                currentAppId = appId
                currentUserInfo = userInfo
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun decodeProtobufVarint(data: ByteArray, startPos: Int): Pair<Int, Int> {
        var res = 0
        var shift = 0
        var pos = startPos

        while (pos < data.size) {
            val b = data[pos].toInt() and 0xFF
            res = res or ((b and 0x7F) shl shift)
            pos++
            if ((b and 0x80) == 0) break
            shift += 7
        }

        return Pair(res, pos)
    }

    fun checkStb(stopId: String): String {
        if (currentAppId.isEmpty() || currentUserInfo.isEmpty()) {
            val ok = generateStbSession()
            if (!ok) return "Auth failed"
        }

        var result = fetchStop(stopId)
        if (result == "EXPIRED") {
            val ok = generateStbSession()
            if (!ok) return "Auth failed"
            result = fetchStop(stopId)
        }

        return result
    }

    private fun fetchStop(stopId: String): String {
        val urlApi = "https://info.stb.ro/api/web/v2-6/lines/stop?stop_id=$stopId"

        val currentCookie =
            "buc-start-date-filter=1773266400000; buc-start-time-filter=%7B%22hours%22%3A%2215%22%2C%22minutes%22%3A%2245%22%2C%22useCurrentTime%22%3Atrue%7D"

        val request = Request.Builder()
            .url(urlApi)
            .header("Host", "info.stb.ro")
            .header("App-Id", currentAppId)
            .header("App-Version", "0.0.0")
            .header("Device-Name", "Chrome")
            .header("OS-Type", "Web")
            .header(
                "OS-Version",
                "5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 OPR/127.0.0.0"
            )
            .header("Lang", "ro")
            .header("Source", "ro.radcom.smartcity.web")
            .header("User-Info", currentUserInfo)
            .header("Cookie", currentCookie)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 OPR/127.0.0.0"
            )
            .header("Accept", "application/x-protobuf, application/json, text/plain, */*")
            .header("Referer", "https://info.stb.ro/")
            .header("Connection", "keep-alive")
            .build()

        return try {
            val response = client.newCall(request).execute()

            if (response.code == 401 || response.code == 412) return "EXPIRED"
            if (!response.isSuccessful) return "Eroare server ${response.code}"

            val data = response.body?.bytes() ?: return "Pachet gol"
            val linesFound = linkedMapOf<String, String>()
            var idx = 0

            while (idx < data.size) {
                if (data[idx] == 0x0A.toByte()) {
                    try {
                        val nameLen = data[idx + 1].toInt() and 0xFF
                        val lineName = String(data, idx + 2, nameLen, Charsets.UTF_8)

                        if (nameLen in 1..5 && lineName.all { it.isLetterOrDigit() }) {
                            var endIdx = data.size
                            var nextSearch = idx + 2 + nameLen

                            while (true) {
                                val next0a = data.indexOf(0x0A.toByte(), nextSearch)
                                if (next0a == -1) break

                                try {
                                    val nxtLen = data[next0a + 1].toInt() and 0xFF
                                    if (nxtLen in 1..5) {
                                        val nxtStr = String(data, next0a + 2, nxtLen, Charsets.UTF_8)
                                        if (nxtStr.all { it.isLetterOrDigit() } &&
                                            (nxtStr.firstOrNull()?.isDigit() == true ||
                                                    nxtStr.startsWith("N") ||
                                                    nxtStr.startsWith("R"))
                                        ) {
                                            endIdx = next0a
                                            break
                                        }
                                    }
                                } catch (_: Exception) {
                                }

                                nextSearch = next0a + 1
                            }

                            val block = data.copyOfRange(idx + 2 + nameLen, endIdx)
                            val foundTimesInt = mutableListOf<Int>()

                            var sPos = 0
                            while (true) {
                                val pos = block.indexOf(0x4A.toByte(), sPos)
                                if (pos == -1) break
                                try {
                                    val (msgLen, contentStart) = decodeProtobufVarint(block, pos + 1)
                                    val subMsg = block.copyOfRange(contentStart, contentStart + msgLen)

                                    val subPos = subMsg.indexOf(0x10.toByte())
                                    if (subPos != -1) {
                                        val (seconds, _) = decodeProtobufVarint(subMsg, subPos + 1)
                                        if (seconds in 0..17999) {
                                            foundTimesInt.add(seconds / 60)
                                        }
                                    }
                                } catch (_: Exception) {
                                }
                                sPos = pos + 1
                            }

                            if (foundTimesInt.isNotEmpty()) {
                                val uniqueTimes = foundTimesInt.sorted().take(3)
                                val formattedTimes = uniqueTimes.map { m ->
                                    if (m < 60) {
                                        "$m min"
                                    } else {
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
                    } catch (_: Exception) {
                        idx++
                    }
                } else {
                    idx++
                }
            }

            if (linesFound.isNotEmpty()) {
                linesFound.entries.joinToString("|") { "${it.key}:${it.value}" }
            } else {
                "Nicio masina"
            }
        } catch (e: Exception) {
            "Eroare script: ${e.message}"
        }
    }
    private fun ByteArray.indexOf(value: Byte, startIndex: Int = 0): Int {
        for (i in startIndex until size) {
            if (this[i] == value) return i
        }
        return -1
    }
}