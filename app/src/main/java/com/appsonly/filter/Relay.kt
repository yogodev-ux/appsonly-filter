package com.appsonly.filter

import android.net.VpnService
import android.util.Log
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Carries every app connection (except our own) through the filter.
 *
 * The phone sends all of its traffic into [FilterVpnService]. For each new connection we find
 * out which app opened it and ask [allow] whether that app may reach that address. Allowed
 * connections are relayed through a normal socket that bypasses the filter ([VpnService.protect]);
 * others are refused. This is what stops a Wi-Fi proxy, a typed-in IP address or an app's own
 * secure DNS from getting around the name filter: they all need a connection to an address the
 * app never looked up through us.
 */
class Relay(
    private val svc: VpnService,
    private val out: FileOutputStream,
    private val ownerUid: (proto: Int, src: InetSocketAddress, dst: InetSocketAddress) -> Int,
    private val allow: (uid: Int, ip: InetAddress, port: Int) -> Boolean,
    private val debug: Boolean,
) {
    companion object {
        private const val TAG = WebFilter.TAG
        const val MSS = 1400
        private const val UP_LIMIT = 256 * 1024 // bytes from the app waiting to go out, per connection
        private const val FIN = 0x01; private const val SYN = 0x02; private const val RST = 0x04
        private const val PSH = 0x08; private const val ACK = 0x10
        private fun seqGt(a: Int, b: Int) = a - b > 0
        private fun seqGe(a: Int, b: Int) = a - b >= 0
    }

    private val tcp = ConcurrentHashMap<String, TcpFlow>()
    private val udp = ConcurrentHashMap<String, UdpFlow>()
    private val threads = Executors.newCachedThreadPool()
    private val ticker = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var closed = false

    init { ticker.scheduleWithFixedDelay({ tick() }, 250, 250, TimeUnit.MILLISECONDS) }

    fun write(p: ByteArray) {
        if (closed) return
        try { synchronized(out) { out.write(p) } } catch (e: Exception) { if (debug) Log.w(TAG, "tun write: $e") }
    }

    fun shutdown() {
        closed = true
        ticker.shutdownNow()
        tcp.values.forEach { it.close(sendRst = false) }
        udp.values.forEach { it.close() }
        threads.shutdownNow()
    }

    private fun log(msg: String) { if (debug) Log.i(TAG, msg) }

    // ---------------------------------------------------------------- packets

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
    private fun u32(b: ByteArray, i: Int) =
        (u16(b, i) shl 16) or u16(b, i + 2)

    private fun checksum(b: ByteArray, off: Int, len: Int, start: Int = 0): Int {
        var sum = start.toLong()
        var i = off
        while (i + 1 < off + len) { sum += u16(b, i); i += 2 }
        if (i < off + len) sum += (b[i].toInt() and 0xff) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
        return sum.toInt()
    }

    /** An IPv4 + TCP packet from [src]:[sp] to [dst]:[dp]. */
    private fun tcpPacket(src: InetAddress, sp: Int, dst: InetAddress, dp: Int, seq: Int, ack: Int,
                          flags: Int, window: Int, data: ByteArray? = null, off: Int = 0, len: Int = 0,
                          mss: Boolean = false): ByteArray {
        val opt = if (mss) 4 else 0
        val tl = 20 + opt
        val total = 20 + tl + len
        val p = ByteArray(total)
        p[0] = 0x45; p[2] = (total shr 8).toByte(); p[3] = total.toByte()
        p[6] = 0x40; p[8] = 64; p[9] = 6
        System.arraycopy(src.address, 0, p, 12, 4)
        System.arraycopy(dst.address, 0, p, 16, 4)
        val ipcs = checksum(p, 0, 20).inv() and 0xffff
        p[10] = (ipcs shr 8).toByte(); p[11] = ipcs.toByte()
        val t = 20
        p[t] = (sp shr 8).toByte(); p[t + 1] = sp.toByte(); p[t + 2] = (dp shr 8).toByte(); p[t + 3] = dp.toByte()
        for (k in 0..3) { p[t + 4 + k] = (seq ushr (24 - 8 * k)).toByte(); p[t + 8 + k] = (ack ushr (24 - 8 * k)).toByte() }
        p[t + 12] = ((tl / 4) shl 4).toByte(); p[t + 13] = flags.toByte()
        val w = window.coerceIn(0, 65535)
        p[t + 14] = (w shr 8).toByte(); p[t + 15] = w.toByte()
        if (mss) { p[t + 20] = 2; p[t + 21] = 4; p[t + 22] = (MSS shr 8).toByte(); p[t + 23] = MSS.toByte() }
        if (data != null && len > 0) System.arraycopy(data, off, p, t + tl, len)
        // Pseudo header: source, destination, protocol, TCP length.
        var pseudo = checksum(p, 12, 8) + 6 + tl + len
        while (pseudo shr 16 != 0) pseudo = (pseudo and 0xffff) + (pseudo shr 16)
        val cs = checksum(p, t, tl + len, pseudo).inv() and 0xffff
        p[t + 16] = (cs shr 8).toByte(); p[t + 17] = cs.toByte()
        return p
    }

    // ---------------------------------------------------------------- TCP

    /** Handles one IPv4 TCP packet from an app. */
    fun onTcp(p: ByteArray, ihl: Int, total: Int, src: InetAddress, dst: InetAddress) {
        if (total < ihl + 20) return
        val t = ihl
        val sp = u16(p, t); val dp = u16(p, t + 2)
        val seq = u32(p, t + 4); val ack = u32(p, t + 8)
        val doff = ((p[t + 12].toInt() and 0xf0) shr 4) * 4
        val flags = p[t + 13].toInt() and 0x3f
        val window = u16(p, t + 14)
        if (doff < 20 || t + doff > total) return
        val dataOff = t + doff
        val dataLen = total - dataOff
        val key = "${src.hostAddress}:$sp>${dst.hostAddress}:$dp"
        val flow = tcp[key]

        if (flow == null) {
            if (flags and RST != 0) return
            if (flags and SYN == 0 || flags and ACK != 0) {
                // Not a connection we know (closed already, or opened before the filter started).
                val rseq = if (flags and ACK != 0) ack else 0
                val rack = seq + dataLen + (if (flags and FIN != 0) 1 else 0)
                write(tcpPacket(dst, dp, src, sp, rseq, rack, RST or ACK, 0))
                return
            }
            var mss = 536
            var i = t + 20
            while (i < dataOff) {
                val kind = p[i].toInt() and 0xff
                if (kind == 0) break
                if (kind == 1) { i++; continue }
                if (i + 1 >= dataOff) break
                val l = p[i + 1].toInt() and 0xff
                if (l < 2) break
                if (kind == 2 && l == 4 && i + 3 < dataOff) mss = u16(p, i + 2)
                i += l
            }
            val uid = ownerUid(6, InetSocketAddress(src, sp), InetSocketAddress(dst, dp))
            if (!allow(uid, dst, dp)) {
                log("BLOCKED connection ${dst.hostAddress}:$dp (${who(uid)})")
                write(tcpPacket(dst, dp, src, sp, 0, seq + 1, RST or ACK, 0))
                return
            }
            val f = TcpFlow(key, src, sp, dst, dp, uid, seq, minOf(mss, MSS).coerceAtLeast(200), window)
            if (tcp.putIfAbsent(key, f) == null) threads.execute { f.connect() }
            return
        }
        flow.onSegment(seq, ack, flags, window, p, dataOff, dataLen)
    }

    private fun who(uid: Int) =
        svc.packageManager.getPackagesForUid(uid)?.joinToString() ?: "uid $uid"

    private inner class TcpFlow(
        val key: String, val appIp: InetAddress, val appPort: Int,
        val remoteIp: InetAddress, val remotePort: Int, val uid: Int,
        appIsn: Int, val mss: Int, initialWindow: Int,
    ) {
        private val lock = Object()
        private var socket: Socket? = null
        private var established = false
        private var done = false
        private val ourIsn = Random.nextInt()
        private var ourNext = ourIsn + 1          // next byte we send to the app
        private var appAcked = ourIsn + 1         // first byte the app hasn't confirmed
        private var appNext = appIsn + 1          // next byte we expect from the app
        private var appWindow = initialWindow
        private var finSent = false
        private var finSeq = 0
        private var appFin = false
        private var remoteEof = false
        // Bytes sent to the app but not yet confirmed, for resending: (seq, bytes).
        private val unacked = ArrayDeque<Pair<Int, ByteArray>>()
        private var rto = 400L
        private var lastProgress = System.currentTimeMillis()
        var lastActivity = System.currentTimeMillis()
        private val up = LinkedBlockingQueue<ByteArray>()
        private val upBytes = AtomicInteger(0)

        private fun ourWindow() = (UP_LIMIT - upBytes.get()).coerceIn(0, 65535)

        private fun send(flags: Int, seq: Int, data: ByteArray? = null, mssOpt: Boolean = false) {
            write(tcpPacket(remoteIp, remotePort, appIp, appPort, seq, appNext, flags, ourWindow(),
                data, 0, data?.size ?: 0, mssOpt))
        }

        fun connect() {
            val s = Socket()
            try {
                svc.protect(s)
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(remoteIp, remotePort), 15_000)
            } catch (e: Exception) {
                log("connect ${remoteIp.hostAddress}:$remotePort failed: $e")
                try { s.close() } catch (_: Exception) {}
                synchronized(lock) { write(tcpPacket(remoteIp, remotePort, appIp, appPort, 0, appNext, RST or ACK, 0)) }
                tcp.remove(key); done = true
                return
            }
            synchronized(lock) {
                if (done) { try { s.close() } catch (_: Exception) {}; return }
                socket = s
                send(SYN or ACK, ourIsn, mssOpt = true)
                lastProgress = System.currentTimeMillis()
            }
            threads.execute { upLoop(s) }
            downLoop(s)
        }

        /** The app's bytes, out to the real server. */
        private fun upLoop(s: Socket) {
            try {
                val o = s.getOutputStream()
                while (true) {
                    val b = up.take()
                    if (b.isEmpty()) { s.shutdownOutput(); return } // the app finished sending
                    o.write(b)
                    val before = upBytes.get()
                    upBytes.addAndGet(-b.size)
                    // The app may have stopped because our window was full: tell it there's room.
                    if (before >= UP_LIMIT - 65535) synchronized(lock) { if (!done) send(ACK, ourNext) }
                }
            } catch (e: InterruptedException) {
            } catch (e: Exception) {
                if (!done) close(sendRst = true)
            }
        }

        /** The server's bytes, back to the app. */
        private fun downLoop(s: Socket) {
            val buf = ByteArray(mss)
            try {
                val input = s.getInputStream()
                while (true) {
                    var room: Int
                    synchronized(lock) {
                        while (!done && (!established || (ourNext - appAcked) >= appWindow)) lock.wait(500)
                        if (done) return
                        room = minOf(mss, appWindow - (ourNext - appAcked))
                    }
                    val n = input.read(buf, 0, room)
                    if (n < 0) break
                    if (n == 0) continue
                    synchronized(lock) {
                        if (done) return
                        val data = buf.copyOf(n)
                        if (unacked.isEmpty()) lastProgress = System.currentTimeMillis()
                        unacked.addLast(ourNext to data)
                        send(ACK or PSH, ourNext, data)
                        ourNext += n
                        lastActivity = System.currentTimeMillis()
                    }
                }
                synchronized(lock) {
                    if (done) return
                    remoteEof = true
                    finSeq = ourNext
                    finSent = true
                    send(FIN or ACK, finSeq)
                    ourNext += 1
                    if (unacked.isEmpty()) lastProgress = System.currentTimeMillis()
                    maybeFinished()
                }
            } catch (e: InterruptedException) {
            } catch (e: Exception) {
                if (!done) close(sendRst = true)
            }
        }

        fun onSegment(seq: Int, ack: Int, flags: Int, window: Int, p: ByteArray, off: Int, len: Int) {
            synchronized(lock) {
                if (done) return
                lastActivity = System.currentTimeMillis()
                if (flags and RST != 0) { close(sendRst = false); return }
                if (flags and SYN != 0) { // the app asked again before our answer arrived
                    if (socket != null && !established) send(SYN or ACK, ourIsn, mssOpt = true)
                    return
                }
                if (flags and ACK != 0) {
                    if (!established && socket != null && seqGe(ack, ourIsn + 1)) established = true
                    if (seqGt(ack, appAcked) && seqGe(ourNext, ack)) {
                        appAcked = ack
                        while (unacked.isNotEmpty()) {
                            val (s0, d) = unacked.first()
                            if (seqGe(ack, s0 + d.size)) unacked.removeFirst() else break
                        }
                        rto = 400L
                        lastProgress = System.currentTimeMillis()
                    }
                    appWindow = window
                    lock.notifyAll()
                }
                if (!established) return
                if (len > 0) {
                    if (seq == appNext) {
                        if (upBytes.get() + len > UP_LIMIT) return // full: the app will send it again
                        upBytes.addAndGet(len)
                        up.put(p.copyOfRange(off, off + len))
                        appNext += len
                    }
                    if (flags and FIN == 0) { send(ACK, ourNext); return }
                }
                if (flags and FIN != 0) {
                    if (seq + len == appNext && !appFin) {
                        appFin = true
                        appNext += 1
                        up.put(ByteArray(0))
                    }
                    send(ACK, ourNext)
                }
                maybeFinished()
            }
        }

        private fun maybeFinished() {
            if (appFin && finSent && seqGt(appAcked, finSeq)) close(sendRst = false)
        }

        /** Called often: resend what the app hasn't confirmed, and drop dead connections. */
        fun tick(now: Long) {
            synchronized(lock) {
                if (done) return
                if (!established && socket != null && now - lastProgress > rto) {
                    send(SYN or ACK, ourIsn, mssOpt = true)
                    rto = minOf(rto * 2, 8000); lastProgress = now
                } else if ((unacked.isNotEmpty() || (finSent && !seqGt(appAcked, finSeq))) && now - lastProgress > rto) {
                    var budget = maxOf(appWindow, mss)
                    for ((s0, d) in unacked) {
                        if (budget <= 0) break
                        send(ACK or PSH, s0, d); budget -= d.size
                    }
                    if (finSent && unacked.isEmpty()) send(FIN or ACK, finSeq)
                    rto = minOf(rto * 2, 8000); lastProgress = now
                    if (rto >= 8000 && now - lastActivity > 60_000) close(sendRst = true)
                } else if (appWindow == 0 && established && now - lastProgress > 2000) {
                    send(ACK, ourNext) // nudge the app to repeat its window
                    lastProgress = now
                }
                if (now - lastActivity > 2 * 60 * 60_000L) close(sendRst = true)
                if (appFin && remoteEof && now - lastActivity > 30_000) close(sendRst = false)
            }
        }

        fun close(sendRst: Boolean) {
            synchronized(lock) {
                if (done) return
                done = true
                if (sendRst) write(tcpPacket(remoteIp, remotePort, appIp, appPort, ourNext, appNext, RST or ACK, 0))
                lock.notifyAll()
            }
            tcp.remove(key)
            try { socket?.close() } catch (_: Exception) {}
            up.clear(); up.offer(ByteArray(0))
        }
    }

    // ---------------------------------------------------------------- UDP

    /** Handles one IPv4 UDP packet from an app that isn't a DNS lookup. */
    fun onUdp(q: Packets.Udp) {
        val key = "${q.src.hostAddress}:${q.srcPort}>${q.dst.hostAddress}:${q.dstPort}"
        val existing = udp[key]
        if (existing != null) { existing.send(q.payload); return }
        val uid = ownerUid(17, InetSocketAddress(q.src, q.srcPort), InetSocketAddress(q.dst, q.dstPort))
        if (!allow(uid, q.dst, q.dstPort)) {
            log("BLOCKED udp ${q.dst.hostAddress}:${q.dstPort} (${who(uid)})")
            return
        }
        val f = try { UdpFlow(key, q) } catch (e: Exception) { log("udp: $e"); return }
        if (udp.putIfAbsent(key, f) == null) { threads.execute { f.receiveLoop() }; f.send(q.payload) } else f.close()
    }

    private inner class UdpFlow(val key: String, q: Packets.Udp) {
        private val appIp = q.src; private val appPort = q.srcPort
        private val remoteIp = q.dst; private val remotePort = q.dstPort
        private val s = DatagramSocket().also { svc.protect(it); it.connect(remoteIp, remotePort); it.soTimeout = 60_000 }

        fun send(data: ByteArray) {
            try { s.send(DatagramPacket(data, data.size)) } catch (e: Exception) { close() }
        }

        fun receiveLoop() {
            val buf = ByteArray(65535)
            try {
                while (true) {
                    val pk = DatagramPacket(buf, buf.size)
                    s.receive(pk) // times out after a minute of silence
                    write(Packets.buildUdpIpv4(remoteIp, appIp, remotePort, appPort, buf.copyOf(pk.length)))
                }
            } catch (e: Exception) {
            } finally { close() }
        }

        fun close() { udp.remove(key); try { s.close() } catch (_: Exception) {} }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        try { tcp.values.forEach { it.tick(now) } } catch (e: Exception) { if (debug) Log.w(TAG, "tick", e) }
    }
}
