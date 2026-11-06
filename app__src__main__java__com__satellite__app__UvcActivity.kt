package com.satellite.app

import android.app.Activity
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.media.*
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper

/** شاشة أصلية لعرض كارت التقاط UVC عبر OTG، مع نقل صوت الكارت (USB Audio) إن وُجد. */
class UvcActivity : Activity() {
    private var helper: ICameraHelper? = null
    private var surface: Surface? = null
    private var opened = false
    private lateinit var status: TextView
    private var audioThread: Thread? = null
    @Volatile private var audioRun = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val sv = SurfaceView(this)
        root.addView(sv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        status = TextView(this).apply {
            text = "وصّل كارت الالتقاط عبر OTG…"; setTextColor(Color.WHITE); textSize = 15f; setBackgroundColor(0x99000000.toInt())
            setPadding(24, 14, 24, 14)
        }
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = 24 })
        val close = TextView(this).apply {
            text = "إغلاق"; setTextColor(Color.WHITE); textSize = 15f; setBackgroundColor(0xCC0F172A.toInt()); setPadding(36, 18, 36, 18)
            setOnClickListener { finish() }
        }
        root.addView(close, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(24, 24, 24, 24) })
        setContentView(root)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { surface = h.surface; if (opened) helper?.addSurface(h.surface, false) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) { surface?.let { try { helper?.removeSurface(it) } catch (_: Exception) {} }; surface = null }
        })
        initCamera()
    }

    private fun initCamera() {
        try {
            helper = CameraHelper().also { hp ->
                hp.setStateCallback(object : ICameraHelper.StateCallback {
                    override fun onAttach(device: UsbDevice) { hp.selectDevice(device) }
                    override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) { hp.openCamera() }
                    override fun onCameraOpen(device: UsbDevice) {
                        hp.startPreview(); opened = true
                        surface?.let { hp.addSurface(it, false) }
                        runOnUiThread { status.text = "يعمل: " + (device.productName ?: "كارت التقاط"); status.postDelayed({ status.visibility = View.GONE }, 2500) }
                        startAudio()
                    }
                    override fun onCameraClose(device: UsbDevice) { opened = false; stopAudio(); surface?.let { try { hp.removeSurface(it) } catch (_: Exception) {} } }
                    override fun onDeviceClose(device: UsbDevice) {}
                    override fun onDetach(device: UsbDevice) { runOnUiThread { status.visibility = View.VISIBLE; status.text = "تم فصل الكارت" } }
                    override fun onCancel(device: UsbDevice) { runOnUiThread { status.visibility = View.VISIBLE; status.text = "تم رفض إذن USB" } }
                })
                // إن كان الكارت موصولًا مسبقًا
                try { hp.deviceList?.firstOrNull()?.let { hp.selectDevice(it) } } catch (_: Exception) {}
            }
        } catch (e: Throwable) { status.text = "تعذر تشغيل مكتبة UVC: " + e.message }
    }

    /** نقل الصوت من مدخل USB إلى مخرج الهاتف (إن كان الكارت يوفر صوتًا) */
    private fun startAudio() {
        if (audioRun || Build.VERSION.SDK_INT < 23) return
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val usbIn = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        } ?: return                                    // لا يوجد صوت USB: لا نشغّل مايك الهاتف لتفادي الصدى
        val rate = 48000; val ch = AudioFormat.CHANNEL_IN_STEREO
        val min = AudioRecord.getMinBufferSize(rate, ch, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return
        try {
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, ch, AudioFormat.ENCODING_PCM_16BIT, min * 2)
            rec.preferredDevice = usbIn
            val trk = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(min * 2).setTransferMode(AudioTrack.MODE_STREAM).build()
            audioRun = true
            audioThread = Thread {
                val buf = ByteArray(min)
                try { rec.startRecording(); trk.play()
                    while (audioRun) { val n = rec.read(buf, 0, buf.size); if (n > 0) trk.write(buf, 0, n) }
                } catch (_: Exception) {} finally {
                    try { rec.stop() } catch (_: Exception) {}; rec.release(); try { trk.stop() } catch (_: Exception) {}; trk.release()
                }
            }.also { it.start() }
        } catch (_: Exception) { audioRun = false }
    }
    private fun stopAudio() { audioRun = false }

    override fun onDestroy() { stopAudio(); try { helper?.release() } catch (_: Exception) {}; helper = null; super.onDestroy() }
}
