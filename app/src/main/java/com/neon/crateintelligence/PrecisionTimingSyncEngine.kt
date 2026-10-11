package com.neon.crateintelligence.timing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

object PrecisionTimingSyncEngine {

    private var synchronizedOffsetMs: Long = 0L
    var isClockCalibrated: Boolean = false
        private set

    private val NTP_SERVERS = listOf("time.google.com", "time.cloudflare.com")

    /**
     * NTP servers se network latency filter karke atomic clock sync karta hai
     */
    suspend fun syncAtomicClock(): Boolean = withContext(Dispatchers.IO) {
        val offsets = mutableListOf<Long>()

        for (host in NTP_SERVERS) {
            try {
                val address = InetAddress.getByName(host)
                val socket = DatagramSocket().apply { soTimeout = 2000 }

                val buffer = ByteArray(48)
                buffer[0] = 0x1B // NTP Version 3, Client mode

                val request = DatagramPacket(buffer, buffer.size, address, 123)
                val t0 = System.currentTimeMillis()
                socket.send(request)

                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                val t3 = System.currentTimeMillis()

                var seconds = 0L
                for (i in 40..43) {
                    seconds = (seconds shl 8) or (buffer[i].toLong() and 0xFF)
                }

                // 1900 se 1970 Unix epoch offset (2208988800 seconds)
                val serverTransmitMs = (seconds - 2208988800L) * 1000L
                val roundTripLatency = t3 - t0
                val calculatedOffset = serverTransmitMs - (t0 + roundTripLatency / 2)
                offsets.add(calculatedOffset)
                socket.close()
            } catch (ignored: Exception) {}
        }

        if (offsets.isNotEmpty()) {
            synchronizedOffsetMs = if (offsets.size > 1 && abs(offsets[0] - offsets[1]) < 300) {
                (offsets[0] + offsets[1]) / 2
            } else {
                offsets[0]
            }
            isClockCalibrated = true
            return@withContext true
        }

        isClockCalibrated = false
        return@withContext false
    }

    /**
     * Millisecond-accurate server timestamp
     */
    fun getAtomicServerTimeMs(): Long {
        return System.currentTimeMillis() + synchronizedOffsetMs
    }

    /**
     * 05:30 AM IST (00:00:00 UTC) reset tak remaining milliseconds
     */
    fun getMillisUntilServerReset(): Long {
        val nowMs = getAtomicServerTimeMs()
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (cal.timeInMillis <= nowMs) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis - nowMs
    }

    /**
     * Seed reset ke shuruati 15 minute (Peak Lucky Window)
     */
    fun isGlobalSeedFreshWindow(): Boolean {
        val nowMs = getAtomicServerTimeMs()
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = nowMs
        }
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        return hour == 0 && minute in 0..14
    }

    /**
     * Current second position in ms (0-999)
     */
    fun getCurrentSecondMillisecond(): Int {
        val currentMs = getAtomicServerTimeMs()
        return (currentMs % 1000).toInt()
    }

    /**
     * Server tick boundary sweet-spot (920ms - 990ms mark)
     */
    fun isTickExecutionSweetSpot(): Boolean {
        val msInSec = getCurrentSecondMillisecond()
        return msInSec in 920..990
    }
}
