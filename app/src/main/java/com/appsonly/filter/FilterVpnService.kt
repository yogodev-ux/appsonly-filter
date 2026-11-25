package com.appsonly.filter

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Local "VPN" that only carries DNS lookups. Normal traffic never enters it.
 *
 * Every app's name lookups are sent to [DNS_IP], which is routed into this service. We find
 * out which app asked, check [WebFilter], and either forward the question to the real DNS
 * server or answer "no such site". Answers are given a zero cache time so one app's lookup
 * can't be reused by another app.
 */
class FilterVpnService : VpnService() {

    companion object {
        private const val TAG = WebFilter.TAG
        const val TUN_IP = "10.111.0.1"
        const val DNS_IP = "10.111.0.2"
        private val FALLBACK_DNS = listOf("8.8.8.8", "1.1.1.1")
    }

    @Volatile private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private val pool = Executors.newFixedThreadPool(8)

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        if (tun == null) start()
        return START_STICKY
    }

    override fun onRevoke() {
        stop()
        super.onRevoke()
    }

    override fun onDestroy() {
        stop()
        pool.shutdownNow()
        super.onDestroy()
    }

    private fun start() {
        val builder = Builder()
            .setSession("Apps Only web filter")
            .addAddress(TUN_IP, 32)
            .addDnsServer(DNS_IP)
            .addRoute(DNS_IP, 32)
            .setBlocking(true)
        try { builder.addDisallowedApplication(packageName) } catch (e: Exception) { /* not fatal */ }
        val fd = try { builder.establish() } catch (e: Exception) { Log.w(TAG, "establish", e); null }
        if (fd == null) { Log.w(TAG, "VPN not allowed yet"); stopSelf(); return }
        tun = fd
        reader = Thread({ readLoop(fd) }, "dns-filter").also { it.start() }
        Log.i(TAG, "Web filter started")
    }

    private fun stop() {
        reader?.interrupt()
        try { tun?.close() } catch (e: Exception) {}
        tun = null
    }

    private fun readLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)
        while (!Thread.currentThread().isInterrupted) {
            val n = try { input.read(buf) } catch (e: Exception) { break }
            if (n <= 0) continue
            val packet = buf.copyOf(n)
            pool.execute { handle(packet, output) }
        }
    }

    private fun handle(p: ByteArray, out: FileOutputStream) {
        try {
            val q = Packets.parseUdpIpv4(p) ?: return
            if (q.dstPort != 53) return
            val host = Dns.questionName(q.payload) ?: return
            val cm = getSystemService(ConnectivityManager::class.java)
            val uid = try {
                cm.getConnectionOwnerUid(OsConstants.IPPROTO_UDP,
                    InetSocketAddress(q.src, q.srcPort), InetSocketAddress(q.dst, q.dstPort))
            } catch (e: Exception) { -1 }

            val answer = if (WebFilter.allowed(this, uid, host)) {
                forward(q.payload)?.let { Dns.zeroTtl(it) } ?: Dns.failure(q.payload, rcode = 2)
            } else {
                WebFilter.logBlocked(this, uid, host)
                Dns.failure(q.payload, rcode = 3) // NXDOMAIN: "no such site"
            }
            val reply = Packets.buildUdpIpv4(q.dst, q.src, q.dstPort, q.srcPort, answer)
            synchronized(out) { out.write(reply) }
        } catch (e: Exception) {
            Log.w(TAG, "dns packet", e)
        }
    }

    /** Real DNS servers of the underlying (non-VPN) network. */
    private fun upstream(): Pair<Network?, List<InetAddress>> {
        val cm = getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            val dns = cm.getLinkProperties(net)?.dnsServers.orEmpty()
            if (dns.isNotEmpty()) return net to dns
        }
        return null to FALLBACK_DNS.map { InetAddress.getByName(it) }
    }

    private fun forward(query: ByteArray): ByteArray? {
        val (net, servers) = upstream()
        for (server in servers + FALLBACK_DNS.map { InetAddress.getByName(it) }) {
            try {
                DatagramSocket().use { s ->
                    protect(s)
                    net?.bindSocket(s)
                    s.soTimeout = 4000
                    s.send(DatagramPacket(query, query.size, server, 53))
                    val buf = ByteArray(4096)
                    val resp = DatagramPacket(buf, buf.size)
                    s.receive(resp)
                    return buf.copyOf(resp.length)
                }
            } catch (e: Exception) {
                Log.d(TAG, "upstream $server failed: $e")
            }
        }
        return null
    }
}

/** Minimal IPv4/UDP packet handling. */
object Packets {
    class Udp(val src: InetAddress, val dst: InetAddress, val srcPort: Int, val dstPort: Int, val payload: ByteArray)

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)

    fun parseUdpIpv4(p: ByteArray): Udp? {
        if (p.size < 28 || (p[0].toInt() shr 4) != 4 || p[9].toInt() != 17) return null
        val ihl = (p[0].toInt() and 0x0f) * 4
        val total = minOf(u16(p, 2), p.size)
        if (total < ihl + 8) return null
        val src = InetAddress.getByAddress(p.copyOfRange(12, 16))
        val dst = InetAddress.getByAddress(p.copyOfRange(16, 20))
        val udpLen = u16(p, ihl + 4)
        val end = minOf(ihl + udpLen, total)
        return Udp(src, dst, u16(p, ihl), u16(p, ihl + 2), p.copyOfRange(ihl + 8, end))
    }

    fun buildUdpIpv4(src: InetAddress, dst: InetAddress, srcPort: Int, dstPort: Int, data: ByteArray): ByteArray {
        val total = 28 + data.size
        val p = ByteArray(total)
        p[0] = 0x45; p[2] = (total shr 8).toByte(); p[3] = total.toByte()
        p[6] = 0x40 // don't fragment
        p[8] = 64; p[9] = 17
        System.arraycopy(src.address, 0, p, 12, 4)
        System.arraycopy(dst.address, 0, p, 16, 4)
        var sum = 0
        for (i in 0 until 20 step 2) sum += u16(p, i)
        while (sum shr 16 != 0) sum = (sum and 0xffff) + (sum shr 16)
        val cs = sum.inv() and 0xffff
        p[10] = (cs shr 8).toByte(); p[11] = cs.toByte()
        p[20] = (srcPort shr 8).toByte(); p[21] = srcPort.toByte()
        p[22] = (dstPort shr 8).toByte(); p[23] = dstPort.toByte()
        val udpLen = 8 + data.size
        p[24] = (udpLen shr 8).toByte(); p[25] = udpLen.toByte()
        // UDP checksum 0 = "not used" (allowed for IPv4)
        System.arraycopy(data, 0, p, 28, data.size)
        return p
    }
}

/** Minimal DNS message handling. */
object Dns {
    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)

    fun questionName(msg: ByteArray): String? {
        if (msg.size < 17 || u16(msg, 4) < 1) return null
        val sb = StringBuilder()
        var i = 12
        while (i < msg.size) {
            val len = msg[i].toInt() and 0xff
            if (len == 0) return sb.toString()
            if (len and 0xc0 != 0 || i + 1 + len > msg.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(msg, i + 1, len, Charsets.US_ASCII))
            i += 1 + len
        }
        return null
    }

    /** Answer with an error: 2 = server failure, 3 = no such name. Keeps the question. */
    fun failure(query: ByteArray, rcode: Int): ByteArray {
        val qEnd = questionEnd(query) ?: 12
        val r = query.copyOf(minOf(qEnd, query.size))
        r[2] = ((r[2].toInt() or 0x80) and 0xfd).toByte() // QR = 1, not truncated, keep opcode/RD
        r[3] = (0x80 or rcode).toByte() // RA = 1, RCODE
        r[6] = 0; r[7] = 0; r[8] = 0; r[9] = 0; r[10] = 0; r[11] = 0
        return r
    }

    private fun skipName(m: ByteArray, start: Int): Int? {
        var i = start
        while (i < m.size) {
            val len = m[i].toInt() and 0xff
            if (len == 0) return i + 1
            if (len and 0xc0 == 0xc0) return i + 2
            i += 1 + len
        }
        return null
    }

    private fun questionEnd(m: ByteArray): Int? {
        var i = 12
        repeat(u16(m, 4)) { i = (skipName(m, i) ?: return null) + 4 }
        return i
    }

    /** Sets every record's cache time to 0 so the phone doesn't share answers between apps. */
    fun zeroTtl(m: ByteArray): ByteArray {
        if (m.size < 12) return m
        val r = m.copyOf()
        var i = questionEnd(r) ?: return r
        val records = u16(r, 6) + u16(r, 8) + u16(r, 10)
        repeat(records) {
            i = skipName(r, i) ?: return r
            if (i + 10 > r.size) return r
            val type = u16(r, i)
            if (type != 41) { r[i + 4] = 0; r[i + 5] = 0; r[i + 6] = 0; r[i + 7] = 0 } // not EDNS OPT
            i += 10 + u16(r, i + 8)
        }
        return r
    }
}
