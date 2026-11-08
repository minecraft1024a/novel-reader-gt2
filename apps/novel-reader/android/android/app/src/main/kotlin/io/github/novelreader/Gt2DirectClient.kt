package io.github.novelreader

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.TimeZone
import kotlin.math.abs
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Direct GT2 BR/EDR client.
 *
 * This is the no-whitelist route documented in bluetooth-computer/:
 * RFCOMM -> HiChain Lite authentication -> svc 0x28 file upload. It does not
 * call Huawei Health or the Wear Engine service. The implementation keeps only
 * one protocol frame and one upload chunk in memory at a time.
 */
class Gt2DirectClient(
    private val context: Context,
    private val adapter: BluetoothAdapter,
    private val progress: (Int) -> Unit
) {
    companion object {
        private val SERVICE_UUID: UUID = UUID.fromString("82FF3820-8411-400C-B85A-55BDB32CF060")
        private const val SERVICE_FILE = 0x28
        private const val CMD_INFO = 0x02
        private const val CMD_HASH = 0x03
        private const val CMD_CONSULT = 0x04
        private const val CMD_CHUNK_PARAMS = 0x05
        private const val CMD_CHUNK = 0x06
        private const val CMD_RESULT = 0x07
        private const val CMD_DEVICE_RESULT = 0x08
        private const val OK = 0x000186A0
        private const val DEFAULT_SLICE = 0xF4
        private val DIGEST_SECRET_V1 = byteArrayOf(
            0x70, 0xFB.toByte(), 0x6C, 0x24, 0x03, 0x5F, 0xDB.toByte(), 0x55,
            0x2F, 0x38, 0x89.toByte(), 0x8A.toByte(), 0xEE.toByte(), 0xDE.toByte(), 0x3F, 0x69
        )
        private val MESSAGE_CHALLENGE = byteArrayOf(0x01, 0x00)
        private val MESSAGE_RESPONSE = byteArrayOf(0x01, 0x10)
        private val random = SecureRandom()
    }

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var sliceSize = DEFAULT_SLICE
    private var authVersion = 0
    private var authAlgo = 0
    private var encryptMethod = 0
    private var serverNonce = ByteArray(0)
    private var secretKey = ByteArray(0)
    private var encryptionCounter = 0L
    private val pending = ArrayDeque<Message>()
    private var partial: ByteArrayOutputStream? = null
    private var rx = ByteArray(0)
    private val prefs = context.getSharedPreferences("gt2_direct", Context.MODE_PRIVATE)

    @SuppressLint("MissingPermission")
    fun sendNovel(mac: String, file: File, account: String = "") {
        require(file.isFile) { "文件不存在: ${file.absolutePath}" }
        val device = adapter.getRemoteDevice(mac.uppercase())
        adapter.cancelDiscovery()
        connect(device)
        try {
            authenticate(mac)
            upload(file, file.name)
        } finally {
            close()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        var firstError: Throwable? = null
        try {
            socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
            socket!!.connect()
        } catch (error: Throwable) {
            firstError = error
            try { socket?.close() } catch (_: Throwable) { }
            socket = null
            // GT2 firmware sometimes does not publish SDP. The known fallback
            // channel is 16; the desktop tool probes 1..30 after this one.
            try {
                val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType!!)
                socket = method.invoke(device, 16) as BluetoothSocket
                socket!!.connect()
            } catch (fallback: Throwable) {
                try { socket?.close() } catch (_: Throwable) { }
                throw IllegalStateException("RFCOMM 连接失败: ${firstError?.message ?: fallback.message}")
            }
        }
        input = socket!!.inputStream
        output = socket!!.outputStream
    }

    private fun close() {
        try { input?.close() } catch (_: Throwable) { }
        try { output?.close() } catch (_: Throwable) { }
        try { socket?.close() } catch (_: Throwable) { }
        input = null
        output = null
        socket = null
    }

    private fun authenticate(mac: String) {
        val link = request(0x01, 0x01, Tlv().empty(1).empty(2).empty(3).empty(4))
        val tag5 = link.tlv.getBytes(5)
        require(tag5.size >= 18) { "GT2 LinkParams nonce 不完整" }
        sliceSize = link.tlv.getShort(2, DEFAULT_SLICE)
        authVersion = tag5[1].toInt() and 0xff
        serverNonce = tag5.copyOfRange(2, 18)
        authAlgo = link.tlv.getByte(8, 0)
        encryptMethod = link.tlv.getByte(0x0C, 0)
        val keyName = "gt2.secret.${mac.replace(":", "").uppercase()}"
        val idName = "gt2.androidId.${mac.replace(":", "").uppercase()}"
        secretKey = prefs.getString(keyName, null)?.let { hex(it) } ?: randomBytes(16).also {
            prefs.edit().putString(keyName, it.toHex()).apply()
        }
        val androidId = prefs.getString(idName, null) ?: randomBytes(16).toHex().also {
            prefs.edit().putString(idName, it).apply()
        }
        request(0x01, 0x33, Tlv().put(1, byteArrayOf(0x02)).put(2, byteArrayOf(0x01))
            .put(5, androidId.toByteArray(Charsets.UTF_8)).put(3, byteArrayOf(0x01)).empty(4))

        val authKey = if (authVersion == 0x02) secretKey else {
            val pin = request(0x01, 0x2C, Tlv().empty(1)).tlv
            aesDecrypt(DIGEST_SECRET_V1, pin.getBytes(2), pin.getBytes(1))
        }
        val clientNonce = randomBytes(16)
        val doubleNonce = serverNonce + clientNonce
        val digest = if (authVersion == 0x02) digestV2(authKey, MESSAGE_CHALLENGE, doubleNonce)
        else digestV1(authKey, MESSAGE_CHALLENGE, doubleNonce, authAlgo)
        val expected = if (authVersion == 0x02) digestV2(authKey, MESSAGE_RESPONSE, doubleNonce).copyOfRange(0, 32)
        else digestV1(authKey, MESSAGE_RESPONSE, doubleNonce, authAlgo).copyOfRange(0, 32)
        val nonce18 = byteArrayOf((authVersion shr 8).toByte(), authVersion.toByte()) + clientNonce
        val authTlv = Tlv().put(1, digest.copyOfRange(0, 32)).put(2, nonce18).put(3, byteArrayOf(authAlgo.toByte()))
        val authResp = request(0x01, 0x13, authTlv).tlv.getBytes(1)
        require(authResp.contentEquals(expected)) { "GT2 HiChain challenge 校验失败" }

        val macClean = mac.replace(":", "")
        val serial = macClean.takeLast(6).toByteArray(Charsets.UTF_8)
        val bond = request(0x01, 0x0F, Tlv().empty(1).put(3, serial).put(4, byteArrayOf(0x02))
            .empty(5).put(7, mac.toByteArray(Charsets.UTF_8)).empty(9)).tlv
        encryptionCounter = bond.getInt(9, 0).toLong() and 0xffffffffL
        val iv = nextIv()
        request(0x01, 0x0E, Tlv().empty(1).empty(3).put(5, serial)
            .put(6, aesEncrypt(digest.copyOfRange(32, 48), iv, secretKey)).put(7, iv))
        val offsetSeconds = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
        val hours = if (offsetSeconds < 0) (-offsetSeconds / 3600) + 128 else offsetSeconds / 3600
        val minutes = abs(offsetSeconds) / 60 % 60
        val zone = (hours shl 8) or minutes
        request(0x01, 0x05, Tlv().putInt(1, System.currentTimeMillis() / 1000)
            .put(2, byteArrayOf((zone shr 8).toByte(), zone.toByte())))
        progress(15)
    }

    private fun upload(file: File, fileName: String) {
        val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|]"), "_").takeLast(72)
        val info = request(SERVICE_FILE, CMD_INFO, Tlv().put(1, safeName.toByteArray(Charsets.UTF_8))
            .putInt(2, file.length()).put(3, byteArrayOf(0x07)))
        require(info.tlv.getInt(0x7F, 0) == OK) { "手表拒绝文件: ${info.tlv.getInt(0x7F, 0)}" }
        val fileId = await(SERVICE_FILE, CMD_HASH).tlv.getByte(1, 0)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = stream.read(buf); if (n <= 0) break; digest.update(buf, 0, n) }
        }
        send(SERVICE_FILE, CMD_HASH, Tlv().put(1, byteArrayOf(fileId.toByte())).put(3, digest.digest()))
        val consultMsg = await(SERVICE_FILE, CMD_CONSULT).tlv
        val unit = consultMsg.getShort(5, 0x1000).coerceAtLeast(256)
        send(SERVICE_FILE, CMD_CONSULT, Tlv().putInt(0x7F, OK.toLong()).put(1, byteArrayOf(fileId.toByte())).put(9, byteArrayOf(0x01)))
        var sent = 0L
        RandomAccessFile(file, "r").use { raf ->
            while (sent < file.length()) {
                val params = awaitAny(SERVICE_FILE, CMD_CHUNK_PARAMS, CMD_DEVICE_RESULT).tlv
                if (lastMessageCommand == CMD_DEVICE_RESULT) {
                    val code = params.getInt(0x7F, -1)
                    require(code == 0 || code == OK) { "手表中止上传: $code" }
                    break
                }
                val position = params.getInt(2, sent.toInt()).toLong() and 0xffffffffL
                val batch = params.getInt(3, unit).coerceAtLeast(1).coerceAtMost(1024 * 1024)
                raf.seek(position)
                var remaining = minOf(batch.toLong(), file.length() - position)
                var pieceIndex = 0
                sent = position
                while (remaining > 0) {
                    val size = minOf(unit, remaining.toInt())
                    val data = ByteArray(size)
                    raf.readFully(data)
                    sendRaw(SERVICE_FILE, CMD_CHUNK, byteArrayOf(fileId.toByte(), pieceIndex.toByte()) +
                        intBytes(sent.toInt()) + data)
                    sent += size
                    remaining -= size
                    pieceIndex++
                }
                progress(15 + ((sent * 84L) / file.length().coerceAtLeast(1L)).toInt())
            }
        }
        await(SERVICE_FILE, CMD_RESULT)
        send(SERVICE_FILE, CMD_RESULT, Tlv().putInt(0x7F, OK.toLong()).put(1, byteArrayOf(fileId.toByte())))
        progress(100)
    }

    private var lastMessageCommand = -1

    private fun send(service: Int, command: Int, tlv: Tlv) = sendRaw(service, command, tlv.serialize())

    private fun sendRaw(service: Int, command: Int, tlv: ByteArray) {
        val max = sliceSize.coerceIn(64, 1024)
        val maxBody = max - 5 - 2
        val count = (tlv.size + 2 + maxBody - 1) / maxBody
        if (count <= 1) {
            writeFrame(byteArrayOf(0, service.toByte(), command.toByte()) + tlv)
            return
        }
        var offset = 0
        for (index in 0 until count) {
            val first = index == 0
            val last = index == count - 1
            var content = max - 5 - 2
            if (first) content -= 2
            val take = minOf(content, tlv.size - offset)
            val body = ByteArrayOutputStream()
            body.write(if (last) 3 else if (first) 1 else 2)
            body.write(index and 0xff)
            if (first) { body.write(service); body.write(command) }
            body.write(tlv, offset, take)
            writeFrame(body.toByteArray())
            offset += take
        }
    }

    private fun writeFrame(body: ByteArray) {
        val out = ByteArrayOutputStream()
        out.write(0x5A)
        out.write((body.size shr 8) and 0xff)
        out.write(body.size and 0xff)
        out.write(body)
        val crc = crc16(out.toByteArray())
        out.write((crc shr 8) and 0xff)
        out.write(crc and 0xff)
        output!!.write(out.toByteArray())
        output!!.flush()
    }

    private fun request(service: Int, command: Int, tlv: Tlv): Message {
        send(service, command, tlv)
        return await(service, command)
    }

    private fun await(service: Int, command: Int): Message = awaitAny(service, command)

    private fun awaitAny(service: Int, vararg commands: Int): Message {
        while (true) {
            val message = nextMessage()
            if (message.service == service && commands.contains(message.command)) {
                lastMessageCommand = message.command
                return message
            }
        }
    }

    private fun nextMessage(): Message {
        if (pending.isNotEmpty()) return decrypt(pending.removeFirst())
        while (true) {
            val buf = ByteArray(4096)
            val count = input!!.read(buf)
            require(count > 0) { "GT2 RFCOMM 连接已断开" }
            rx += buf.copyOf(count)
            while (true) {
                val parsed = extractFrame() ?: break
                val msg = consumeFrame(parsed) ?: continue
                return decrypt(msg)
            }
        }
    }

    private fun extractFrame(): ByteArray? {
        while (rx.isNotEmpty() && (rx[0].toInt() and 0xff) != 0x5A) rx = rx.copyOfRange(1, rx.size)
        if (rx.size < 3) return null
        val len = ((rx[1].toInt() and 0xff) shl 8) or (rx[2].toInt() and 0xff)
        val total = len + 5
        if (total > 4096) { rx = rx.copyOfRange(1, rx.size); return null }
        if (rx.size < total) return null
        val frame = rx.copyOfRange(0, total)
        val expected = ((frame[total - 2].toInt() and 0xff) shl 8) or (frame[total - 1].toInt() and 0xff)
        if (crc16(frame.copyOfRange(0, total - 2)) != expected) { rx = rx.copyOfRange(1, rx.size); return null }
        rx = rx.copyOfRange(total, rx.size)
        return frame.copyOfRange(3, total - 2)
    }

    private fun consumeFrame(body: ByteArray): Message? {
        if (body.isEmpty()) return null
        val flag = body[0].toInt() and 0xff
        val payload: ByteArray
        if (flag == 0) { partial = null; payload = body.copyOfRange(1, body.size) }
        else if (flag in 1..3) {
            val part = body.copyOfRange(2, body.size)
            if (flag == 1) { partial = ByteArrayOutputStream().apply { write(part) }; return null }
            val current = partial ?: return null
            current.write(part)
            if (flag != 3) return null
            payload = current.toByteArray(); partial = null
        } else return null
        if (payload.size < 2) return null
        return Message(payload[0].toInt() and 0xff, payload[1].toInt() and 0xff, payload.copyOfRange(2, payload.size))
    }

    private fun decrypt(message: Message): Message {
        if (secretKey.isEmpty()) return message
        val tlv = Tlv.parse(message.payload)
        if (!tlv.has(0x7C) || !tlv.has(0x7D) || !tlv.has(0x7E)) return message
        return message.copy(payload = aesDecrypt(secretKey, tlv.getBytes(0x7D), tlv.getBytes(0x7E)))
    }

    private fun nextIv(): ByteArray {
        encryptionCounter = (encryptionCounter + 1) and 0xffffffffL
        return randomBytes(12) + intBytes(encryptionCounter.toInt())
    }

    private data class Message(val service: Int, val command: Int, val payload: ByteArray) {
        val tlv: Tlv get() = Tlv.parse(payload)
    }

    private class Tlv(private val pairs: MutableList<Pair<Int, ByteArray>> = mutableListOf()) {
        fun empty(tag: Int) = put(tag, ByteArray(0))
        fun put(tag: Int, value: ByteArray) = apply { pairs.add(tag to value) }
        fun putInt(tag: Int, value: Long): Tlv {
            val v = value.toInt()
            return put(tag, byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))
        }
        fun has(tag: Int) = pairs.any { it.first == tag }
        fun getBytes(tag: Int): ByteArray = pairs.lastOrNull { it.first == tag }?.second ?: error("TLV 0x${tag.toString(16)} missing")
        fun getByte(tag: Int, fallback: Int): Int = getBytesOrNull(tag)?.firstOrNull()?.toInt()?.and(0xff) ?: fallback
        fun getShort(tag: Int, fallback: Int): Int {
            val b = getBytesOrNull(tag) ?: return fallback
            return if (b.size >= 2) ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff) else fallback
        }
        fun getInt(tag: Int, fallback: Int): Int {
            val b = getBytesOrNull(tag) ?: return fallback
            var v = 0
            for (x in b.takeLast(4)) v = (v shl 8) or (x.toInt() and 0xff)
            return v
        }
        private fun getBytesOrNull(tag: Int) = pairs.lastOrNull { it.first == tag }?.second
        fun serialize(): ByteArray {
            val out = ByteArrayOutputStream()
            for ((tag, value) in pairs) { out.write(tag); writeVarint(out, value.size); out.write(value) }
            return out.toByteArray()
        }
        companion object {
            fun parse(data: ByteArray): Tlv {
                val out = Tlv(); var i = 0
                while (i < data.size) {
                    val tag = data[i++].toInt() and 0xff
                    var len = 0; var shift = 0; var b: Int
                    do { require(i < data.size) { "TLV 长度截断" }; b = data[i++].toInt() and 0xff; len = len or ((b and 0x7f) shl shift); shift += 7 } while ((b and 0x80) != 0)
                    require(i + len <= data.size) { "TLV 值截断" }
                    out.put(tag, data.copyOfRange(i, i + len)); i += len
                }
                return out
            }
            private fun writeVarint(out: ByteArrayOutputStream, value: Int) { var v = value; while (true) { val b = v and 0x7f; v = v ushr 7; if (v == 0) { out.write(b); return }; out.write(b or 0x80) } }
        }
    }

    private fun digestV2(key: ByteArray, message: ByteArray, nonce: ByteArray): ByteArray {
        val material = key + message
        return hmac(material, nonce) + material + ByteArray(64 - 32 - material.size)
    }

    private fun digestV1(key: ByteArray, message: ByteArray, nonce: ByteArray, algo: Int): ByteArray {
        val hashKey = sha256(key.toHex().uppercase().toByteArray(Charsets.US_ASCII))
        val secret = ByteArray(16) { (DIGEST_SECRET_V1[it].toInt() xor hashKey[it].toInt()).toByte() }
        val material = secret + message
        val step = if (algo == 1) {
            val spec = PBEKeySpec(material.toHex().uppercase().toCharArray(), nonce, 1000, 256)
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } else hmac(material, nonce)
        return hmac(step, nonce) + step
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, algorithm)); doFinal(data) }
    private fun aesEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray = aes(key, iv, data, Cipher.ENCRYPT_MODE)
    private fun aesDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray = aes(key, iv, data, Cipher.DECRYPT_MODE)
    private fun aes(key: ByteArray, iv: ByteArray, data: ByteArray, mode: Int): ByteArray = Cipher.getInstance("AES/CBC/PKCS5Padding").run { init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv)); doFinal(data) }
    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)
    private fun intBytes(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    private fun crc16(data: ByteArray): Int { var crc = 0; for (raw in data) { val b = raw.toInt() and 0xff; crc = ((crc ushr 8) or ((crc shl 8) and 0xffff)) and 0xffff; crc = crc xor b; crc = crc xor ((crc and 0xff) ushr 4); crc = crc xor ((crc shl 12) and 0xffff); crc = crc xor ((crc and 0xff) shl 5); }; return crc and 0xffff }
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun hex(value: String): ByteArray { require(value.length % 2 == 0); return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
}
