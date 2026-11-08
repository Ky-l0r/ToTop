package com.totop.app.ml

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 录音采集（0.7.0，语音转写用）。
 *
 * 与系统识别器（`SpeechRecognizer`）的根本区别：这里拿到的是**原始 PCM**，
 * 音频要自己发给厂商的流式接口。因此参数必须与协议对齐：
 * 16 kHz、单声道、16-bit，每 100 ms 一片（3200 字节）——分片太大会让「边说边出字」变成「说完才出字」。
 *
 * 刻意不做降噪、不做增益：识别端有 VAD 与降噪，客户端再处理一遍只会引入延迟，
 * 而且会让「听起来正常但识别不出来」这类问题更难定位。
 */
class AsrRecorder {
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    private val started = AtomicBoolean(false)

    /**
     * 开始录音。
     *
     * @param onChunk 每片 PCM（3200 字节），在读线程上回调；调用方不要在里面做重活
     * @param onError 初始化或读取失败时的可读原因
     * @return 是否成功启动
     */
    @SuppressLint("MissingPermission")
    fun start(
        onChunk: (ByteArray) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        if (started.get()) return true
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            onError("这台设备的录音参数不受支持（16k 单声道）")
            return false
        }
        val bufferSize = maxOf(minBuffer, CHUNK_BYTES * 4)
        val recorder =
            runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    CHANNEL,
                    ENCODING,
                    bufferSize,
                )
            }.getOrElse { failure ->
                onError("无法打开麦克风：" + (failure.message ?: "未知原因"))
                return false
            }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            onError("麦克风被占用或权限未授予")
            return false
        }
        record = recorder
        running = true
        started.set(true)
        runCatching { recorder.startRecording() }.onFailure { failure ->
            running = false
            started.set(false)
            recorder.release()
            record = null
            onError("开始录音失败：" + (failure.message ?: "未知原因"))
            return false
        }
        worker =
            Thread {
                val buffer = ByteArray(CHUNK_BYTES)
                while (running) {
                    val read = record?.read(buffer, 0, buffer.size) ?: break
                    if (read <= 0) continue
                    // 最后一片可能不满：按实际长度发，不要补零（补零会在末尾多出一段静音）。
                    onChunk(if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read))
                }
            }.also { thread ->
                thread.name = "asr-recorder"
                thread.isDaemon = true
                thread.start()
            }
        return true
    }

    /** 停止录音并释放麦克风；可重复调用。 */
    fun stop() {
        running = false
        started.set(false)
        worker?.let { thread ->
            runCatching { thread.join(THREAD_JOIN_MS) }
        }
        worker = null
        record?.let { recorder ->
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
        record = null
    }

    private companion object {
        const val SAMPLE_RATE: Int = 16000
        const val CHANNEL: Int = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING: Int = AudioFormat.ENCODING_PCM_16BIT

        /** 100ms @ 16kHz 16bit 单声道 = 16000 * 0.1 * 2。与协议文档给的分片大小一致。 */
        const val CHUNK_BYTES: Int = 3200
        const val THREAD_JOIN_MS: Long = 400
    }
}
