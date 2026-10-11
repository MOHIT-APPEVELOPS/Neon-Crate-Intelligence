package com.neon.crateintelligence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Calendar
import java.util.TimeZone

object PrecisionTimingSyncEngine {

    var clockOffsetMs: Long = 0L
    var isClockCalibrated: Boolean = false

    private const val NTP_SERVER = "time.google.com"
    private const val NTP_PORT = 123
    private const val NTP_TIMEOUT_MS = 3000

    suspend fun syncAtomicClock(): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = DatagramSocket().apply { soTimeout = NTP_TIMEOUT_MS }
            val address = InetAddress.getByName(NTP_SERVER)
            val buffer = ByteArray(48).apply { this[0] = 0x1B }

            val requestPacket = DatagramPacket(buffer, buffer.size, address, NTP_PORT)
            val t0 = System.currentTimeMillis()
            socket.send(requestPacket)

            val responsePacket = DatagramPacket(buffer, buffer.size)
            socket.receive(responsePacket)
            val t3 = System.currentTimeMillis()
            socket.close()

            val t2 = readNtpTimestamp(buffer, 40)
            val t1 = readNtpTimestamp(buffer, 32)

            clockOffsetMs = ((t1 - t0) + (t2 - t3)) / 2
            isClockCalibrated = true
            true
        } catch (e: Exception) {
            clockOffsetMs = 0L
            isClockCalibrated = false
            false
        }
    }

    private fun readNtpTimestamp(buffer: ByteArray, offset: Int): Long {
        var seconds = 0L
        for (i in 0..3) {
            seconds = (seconds shl 8) or (buffer[offset + i].toLong() and 0xFFL)
        }
        var fraction = 0L
        for (i in 4..7) {
            fraction = (fraction shl 8) or (buffer[offset + i].toLong() and 0xFFL)
        }
        val msFrom1900 = (seconds * 1000L) + ((fraction * 1000L) / 0x100000000L)
        return msFrom1900 - 2208988800000L
    }

    fun getAtomicTimeMillis(): Long = System.currentTimeMillis() + clockOffsetMs

    fun getCurrentSecondMillisecond(): Int = (getAtomicTimeMillis() % 1000).toInt()

    fun isTickExecutionSweetSpot(): Boolean = getCurrentSecondMillisecond() in 0..65

    fun isGlobalSeedFreshWindow(): Boolean = (getAtomicTimeMillis() % 60000) in 0..1200

    fun getMillisUntilServerReset(): Long {
        val now = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        now.timeInMillis = getAtomicTimeMillis()

        val resetTime = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata")).apply {
            timeInMillis = now.timeInMillis
            set(Calendar.HOUR_OF_DAY, 5)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (now.after(resetTime)) {
            resetTime.add(Calendar.DAY_OF_YEAR, 1)
        }

        return resetTime.timeInMillis - now.timeInMillis
    }
}
