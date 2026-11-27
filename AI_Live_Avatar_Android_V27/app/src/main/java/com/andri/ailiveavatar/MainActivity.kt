package com.andri.ailiveavatar

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.*
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    private lateinit var avatar: AvatarView
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private val executor = Executors.newSingleThreadExecutor()
    private val cutoutExecutor = Executors.newSingleThreadExecutor()
    private val selfieSegmenter by lazy {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
        Segmentation.getClient(options)
    }
    private var tracking = false
    private var live = false
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    @Volatile private var audioRunning = false
    @Volatile private var activityResumed = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var lastFaceMs = 0L
    private var trackingQuality = 0f
    private var performanceMode = true
    private var lastAudioUiMs = 0L
    private var lastFaceUiMs = 0L
    // Adaptive microphone noise gate: learns the room noise while the user is quiet.
    @Volatile private var micNoiseFloor = 0.008f
    @Volatile private var micSensitivity = 1.0f
    @Volatile private var noiseGateStrength = 1.35f

    private val detector by lazy { FaceDetection.getClient(FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .build()) }

    // OpenDocument lets Android grant durable access; a private app copy is also kept
    // so the selected images survive app restarts and gallery-provider changes.
    private val pickAvatar = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { /* The private copy below is the durable fallback. */ }
        if (uri != null) contentResolver.openInputStream(uri)?.use { stream ->
            val picked = BitmapFactory.decodeStream(stream)
            if (picked != null) {
                avatar.setAvatarBitmap(picked)
                persistBitmap(picked, "avatar_image.png")
                Toast.makeText(this, "Foto avatar tersimpan", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val pickBackground = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { /* The private copy below is the durable fallback. */ }
        if (uri != null) contentResolver.openInputStream(uri)?.use { stream ->
            val picked = BitmapFactory.decodeStream(stream)
            if (picked != null) {
                avatar.setBackgroundBitmap(picked)
                persistBitmap(picked, "background_image.png")
                Toast.makeText(this, "Background tersimpan", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun persistBitmap(bitmap: Bitmap, filename: String) {
        try {
            FileOutputStream(File(filesDir, filename)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.flush()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal menyimpan gambar: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    private fun restoreSavedImages() {
        try {
            File(filesDir, "avatar_image.png").takeIf { it.exists() }?.let {
                BitmapFactory.decodeFile(it.absolutePath)?.let { bitmap -> avatar.setAvatarBitmap(bitmap) }
            }
            File(filesDir, "background_image.png").takeIf { it.exists() }?.let {
                BitmapFactory.decodeFile(it.absolutePath)?.let { bitmap -> avatar.setBackgroundBitmap(bitmap) }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Sebagian gambar profil tidak bisa dipulihkan", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)

        val crashFile = File(filesDir, "crash-log.txt")
        if (crashFile.exists()) {
            val report = crashFile.readText()
            setContentView(android.widget.TextView(this).apply {
                text = "LAPORAN CRASH AI LIVE AVATAR\\n\\n$report"
                textSize = 14f
                setTextIsSelectable(true)
                setPadding(24, 32, 24, 32)
            })
            return
        }

        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        avatar=findViewById(R.id.avatar); preview=findViewById(R.id.cameraPreview); status=findViewById(R.id.status)
        findViewById<Button>(R.id.avatarPick).setOnClickListener { pickAvatar.launch(arrayOf("image/*")) }
        findViewById<Button>(R.id.removeBg).setOnClickListener { removeAvatarBackground() }
        findViewById<Button>(R.id.bg).setOnClickListener { pickBackground.launch(arrayOf("image/*")) }
        findViewById<Switch>(R.id.mirror).setOnCheckedChangeListener { _, checked -> avatar.setMirror(checked) }
        findViewById<Switch>(R.id.idle).setOnCheckedChangeListener { _, checked -> avatar.setIdle(checked) }
        findViewById<Switch>(R.id.performance).setOnCheckedChangeListener { _, checked -> performanceMode = checked; status.text = if (checked) "● AI AVATAR  •  Performance ON" else "● AI AVATAR  •  Performance OFF" }
        findViewById<Switch>(R.id.headFollow).setOnCheckedChangeListener { _, checked -> avatar.setHeadFollow(checked) }
        findViewById<SeekBar>(R.id.intensity).setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if(fromUser) avatar.setIntensity(p/100f) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        findViewById<SeekBar>(R.id.micSensitivity).setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if(fromUser) micSensitivity = (p.coerceAtLeast(10) / 100f) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        findViewById<SeekBar>(R.id.noiseGate).setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if(fromUser) noiseGateStrength = (p.coerceAtLeast(50) / 100f) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        findViewById<Button>(R.id.moodNatural).setOnClickListener { avatar.setMood(0) }
        findViewById<Button>(R.id.moodHappy).setOnClickListener { avatar.setMood(1) }
        findViewById<Button>(R.id.moodSurprised).setOnClickListener { avatar.setMood(2) }
        findViewById<Button>(R.id.moodCalm).setOnClickListener { avatar.setMood(3) }
        findViewById<Button>(R.id.track).setOnClickListener { if(tracking) stopTracking() else startTracking() }
        findViewById<Button>(R.id.center).setOnClickListener { avatar.resetFraming(); findViewById<SeekBar>(R.id.avatarScale).progress=92; findViewById<SeekBar>(R.id.avatarX).progress=50; findViewById<SeekBar>(R.id.avatarY).progress=50; findViewById<SeekBar>(R.id.avatarAlpha).progress=100 }
        findViewById<Button>(R.id.live).setOnClickListener { toggleLive() }
        findViewById<SeekBar>(R.id.mouth).setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if(fromUser) avatar.setManualMouth(p/100f) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        findViewById<SeekBar>(R.id.avatarScale).setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if(fromUser) avatar.setAvatarScale(p/100f) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        findViewById<SeekBar>(R.id.avatarX).setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if(fromUser) avatar.setAvatarOffsetX((p-50)/100f) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        findViewById<SeekBar>(R.id.avatarAlpha).setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if(fromUser) avatar.setAvatarAlpha(p/100f) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        findViewById<Button>(R.id.resetFrame).setOnClickListener {
            avatar.resetFraming()
            findViewById<SeekBar>(R.id.avatarScale).progress=92
            findViewById<SeekBar>(R.id.avatarX).progress=50
            findViewById<SeekBar>(R.id.avatarY).progress=50
            findViewById<SeekBar>(R.id.avatarAlpha).progress=100
        }
        findViewById<SeekBar>(R.id.avatarY).setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if(fromUser) avatar.setAvatarOffsetY((p-50)/100f) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        findViewById<SeekBar>(R.id.smooth).setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if(fromUser) avatar.setSmooth(p/100f) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        findViewById<Button>(R.id.saveProfile).setOnClickListener { saveProfile() }
        findViewById<Button>(R.id.loadProfile).setOnClickListener { loadProfile() }
        // Restore saved control values on startup after listeners are attached.
        loadProfile(showToast = false)
        restoreSavedImages()
    }



    private fun hasRuntimePermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun ensureRuntimePermissions() {
        if (!hasRuntimePermissions()) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                10
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10) {
            if (hasRuntimePermissions()) {
                if (activityResumed) {
                    if (!tracking) startTracking()
                    if (!audioRunning) startAudio()
                }
            } else {
                status.text = "● AI AVATAR  •  Izin kamera dan mikrofon diperlukan"
                Toast.makeText(this, "Izinkan kamera dan mikrofon untuk mengaktifkan avatar", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveProfile() {
        val p = getSharedPreferences("avatar_profile_v22", MODE_PRIVATE)
        p.edit()
            .putInt("scale", findViewById<SeekBar>(R.id.avatarScale).progress)
            .putInt("x", findViewById<SeekBar>(R.id.avatarX).progress)
            .putInt("y", findViewById<SeekBar>(R.id.avatarY).progress)
            .putInt("alpha", findViewById<SeekBar>(R.id.avatarAlpha).progress)
            .putInt("mouth", findViewById<SeekBar>(R.id.mouth).progress)
            .putInt("intensity", findViewById<SeekBar>(R.id.intensity).progress)
            .putInt("smooth", findViewById<SeekBar>(R.id.smooth).progress)
            .putInt("micSensitivity", findViewById<SeekBar>(R.id.micSensitivity).progress)
            .putInt("noiseGate", findViewById<SeekBar>(R.id.noiseGate).progress)
            .putBoolean("mirror", findViewById<Switch>(R.id.mirror).isChecked)
            .putBoolean("headFollow", findViewById<Switch>(R.id.headFollow).isChecked)
            .putBoolean("performance", findViewById<Switch>(R.id.performance).isChecked)
            .putBoolean("idle", findViewById<Switch>(R.id.idle).isChecked)
            .apply()
        Toast.makeText(this, "Pengaturan profil avatar disimpan", Toast.LENGTH_SHORT).show()
    }

    private fun loadProfile(showToast: Boolean = true) {
        val p = getSharedPreferences("avatar_profile_v22", MODE_PRIVATE)
        if (!p.contains("scale")) {
            if (showToast) Toast.makeText(this, "Belum ada profil tersimpan", Toast.LENGTH_SHORT).show()
            return
        }
        fun seek(id: Int, key: String, fallback: Int) {
            findViewById<SeekBar>(id).progress = p.getInt(key, fallback)
        }
        seek(R.id.avatarScale, "scale", 92)
        seek(R.id.avatarX, "x", 50)
        seek(R.id.avatarY, "y", 50)
        seek(R.id.avatarAlpha, "alpha", 100)
        seek(R.id.mouth, "mouth", 10)
        seek(R.id.intensity, "intensity", 100)
        seek(R.id.smooth, "smooth", 72)
        seek(R.id.micSensitivity, "micSensitivity", 100)
        seek(R.id.noiseGate, "noiseGate", 135)
        findViewById<Switch>(R.id.mirror).isChecked = p.getBoolean("mirror", false)
        findViewById<Switch>(R.id.headFollow).isChecked = p.getBoolean("headFollow", true)
        findViewById<Switch>(R.id.performance).isChecked = p.getBoolean("performance", true)
        findViewById<Switch>(R.id.idle).isChecked = p.getBoolean("idle", true)
        // Apply restored values directly; programmatic SeekBar changes don't invoke fromUser callbacks.
        avatar.setAvatarScale(p.getInt("scale", 92) / 100f)
        avatar.setAvatarOffsetX((p.getInt("x", 50) - 50) / 100f)
        avatar.setAvatarOffsetY((p.getInt("y", 50) - 50) / 100f)
        avatar.setAvatarAlpha(p.getInt("alpha", 100) / 100f)
        avatar.setManualMouth(p.getInt("mouth", 10) / 100f)
        avatar.setIntensity(p.getInt("intensity", 100) / 100f)
        avatar.setSmooth(p.getInt("smooth", 72) / 100f)
        micSensitivity = p.getInt("micSensitivity", 100) / 100f
        noiseGateStrength = p.getInt("noiseGate", 135) / 100f
        avatar.setMirror(p.getBoolean("mirror", false))
        avatar.setHeadFollow(p.getBoolean("headFollow", true))
        avatar.setIdle(p.getBoolean("idle", true))
        performanceMode = p.getBoolean("performance", true)
        if (showToast) Toast.makeText(this, "Profil avatar berhasil dimuat", Toast.LENGTH_SHORT).show()
    }

    private fun removeAvatarBackground() {
        val source = avatar.currentAvatarBitmap()
        if (source == null) {
            Toast.makeText(this, "Pilih foto avatar dulu", Toast.LENGTH_SHORT).show()
            return
        }
        status.text = "● AI AVATAR  •  Memproses hapus background…"
        findViewById<Button>(R.id.removeBg).isEnabled = false
        cutoutExecutor.execute {
            try {
                val maxSide = 1024
                val ratio = minOf(1f, maxSide.toFloat() / maxOf(source.width, source.height))
                val inputBitmap = if (ratio < 1f) Bitmap.createScaledBitmap(source, (source.width * ratio).toInt().coerceAtLeast(1), (source.height * ratio).toInt().coerceAtLeast(1), true) else source
                selfieSegmenter.process(InputImage.fromBitmap(inputBitmap, 0))
                    .addOnSuccessListener { result ->
                        val mask = result.buffer.asFloatBuffer()
                        mask.rewind()
                        val mw = result.width
                        val mh = result.height
                        val pixels = IntArray(inputBitmap.width * inputBitmap.height)
                        for (y in 0 until inputBitmap.height) {
                            val my = (y.toFloat() / inputBitmap.height * mh).toInt().coerceIn(0, mh - 1)
                            for (x in 0 until inputBitmap.width) {
                                val mx = (x.toFloat() / inputBitmap.width * mw).toInt().coerceIn(0, mw - 1)
                                val confidence = mask.get(my * mw + mx).coerceIn(0f, 1f)
                                val alpha = (((confidence - 0.12f) / 0.72f).coerceIn(0f, 1f) * 255f).toInt()
                                val original = inputBitmap.getPixel(x, y)
                                pixels[y * inputBitmap.width + x] = (alpha shl 24) or (original and 0x00FFFFFF)
                            }
                        }
                        val cutout = Bitmap.createBitmap(inputBitmap.width, inputBitmap.height, Bitmap.Config.ARGB_8888)
                        cutout.setPixels(pixels, 0, inputBitmap.width, 0, 0, inputBitmap.width, inputBitmap.height)
                        runOnUiThread {
                            avatar.setAvatarBitmap(cutout)
                            persistBitmap(cutout, "avatar_image.png")
                            status.text = "● AI AVATAR  •  Background avatar dihapus"
                            findViewById<Button>(R.id.removeBg).isEnabled = true
                            Toast.makeText(this, "Background avatar berhasil dipisahkan", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .addOnFailureListener { error ->
                        runOnUiThread {
                            status.text = "● AI AVATAR  •  Gagal memproses foto"
                            findViewById<Button>(R.id.removeBg).isEnabled = true
                            Toast.makeText(this, "Gagal hapus background: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
                        }
                    }
            } catch (e: Exception) {
                runOnUiThread {
                    findViewById<Button>(R.id.removeBg).isEnabled = true
                    status.text = "● AI AVATAR  •  Gagal memproses foto"
                    Toast.makeText(this, "Foto tidak bisa diproses", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startAudio() {
        if (audioRunning || !activityResumed || !hasRuntimePermissions()) return
        var recorder: AudioRecord? = null
        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) {
                status.text = "● AI AVATAR  •  Mikrofon tidak tersedia"
                return
            }
            val bufferSize = maxOf(minBuffer, 4096)
            recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                status.text = "● AI AVATAR  •  Gagal membuka mikrofon"
                return
            }
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                recorder.release()
                status.text = "● AI AVATAR  •  Mikrofon tidak aktif"
                return
            }
            audioRecord = recorder
            val activeRecorder = recorder
            audioRunning = true
            audioThread = Thread {
                val buffer = ShortArray(bufferSize / 2)
                while (audioRunning && !Thread.currentThread().isInterrupted) {
                    val count = try { activeRecorder.read(buffer, 0, buffer.size) } catch (_: Exception) { -1 }
                    if (count > 0) {
                        var sum = 0.0
                        for (i in 0 until count) {
                            val sample = buffer[i] / 32768.0
                            sum += sample * sample
                        }
                        val rms = sqrt(sum / count).toFloat()
                        val floor = micNoiseFloor
                        if (rms < floor * 2.2f) {
                            micNoiseFloor = (floor * 0.985f + rms * 0.015f).coerceIn(0.0025f, 0.06f)
                        }
                        val gate = micNoiseFloor * noiseGateStrength
                        val gated = ((rms - gate) / (0.18f - gate).coerceAtLeast(0.025f)).coerceIn(0f, 1f)
                        val level = (gated * micSensitivity * 1.12f).coerceIn(0f, 1f)
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (!performanceMode || now - lastAudioUiMs >= 33L) {
                            lastAudioUiMs = now
                            runOnUiThread {
                                if (activityResumed && !isFinishing && !isDestroyed) avatar.setAudioLevel(level)
                            }
                        }
                    } else if (count < 0) break
                }
            }.also { it.name = "AvatarAudioMeter"; it.start() }
        } catch (_: SecurityException) {
            audioRunning = false
            try { recorder?.release() } catch (_: Exception) {}
            audioRecord = null
            status.text = "● AI AVATAR  •  Izin mikrofon belum diberikan"
        } catch (_: Exception) {
            audioRunning = false
            try { recorder?.release() } catch (_: Exception) {}
            audioRecord = null
            status.text = "● AI AVATAR  •  Mikrofon gagal dijalankan"
        }
    }

    private fun stopAudio() {
        audioRunning = false
        val recorder = audioRecord
        audioRecord = null
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        audioThread?.interrupt()
        audioThread = null
    }

    private fun toggleLive(){
        live=!live
        findViewById<View>(R.id.panel).visibility=if(live) View.GONE else View.VISIBLE
        window.decorView.systemUiVisibility=if(live) 5894 else 0
        status.text=if(live) "● LIVE PREVIEW  •  Avatar aktif  •  9:16" else "● AI AVATAR  •  Face tracking ${if(tracking) "ON" else "OFF"}"
    }

    private fun startTracking(){
        if (!activityResumed || !hasRuntimePermissions()) {
            ensureRuntimePermissions()
            return
        }
        tracking=true; status.text="● AI AVATAR  •  Face tracking ON"; findViewById<Button>(R.id.track).text="Matikan Face Tracking"
        val provider=ProcessCameraProvider.getInstance(this)
        provider.addListener({
            if (!tracking || !activityResumed || isFinishing || isDestroyed) return@addListener
            val p=provider.get(); cameraProvider=p
            val analysis=ImageAnalysis.Builder().setTargetResolution(android.util.Size(480,640)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor){ image ->
                val media=image.image ?: run { image.close(); return@setAnalyzer }
                var faceFound = false
                detector.process(InputImage.fromMediaImage(media,image.imageInfo.rotationDegrees))
                    .addOnSuccessListener{faces->
                        faceFound = faces.isNotEmpty()
                        faces.firstOrNull()?.let{f->
                            lastFaceMs = android.os.SystemClock.elapsedRealtime()
                            val yaw=(f.headEulerAngleY/35f).coerceIn(-1f,1f)
                            val pitch=(f.headEulerAngleX/25f).coerceIn(-1f,1f)
                            val eye=((f.leftEyeOpenProbability?:1f)+(f.rightEyeOpenProbability?:1f))/2f
                            avatar.setFace(yaw,pitch,eye,f.smilingProbability?:0f)
                            trackingQuality = 1f
                            val now=android.os.SystemClock.elapsedRealtime(); if(!performanceMode || now-lastFaceUiMs>=250L){ lastFaceUiMs=now; runOnUiThread { status.text = "● AI AVATAR  •  Face tracking ON  •  Face detected" } }
                        }
                    }.addOnCompleteListener{
                        if (!faceFound && android.os.SystemClock.elapsedRealtime() - lastFaceMs > 350L) {
                            avatar.setFaceLost()
                            runOnUiThread { status.text = "● AI AVATAR  •  Face tracking ON  •  Cari wajah…" }
                        }
                        image.close()
                    }
            }
            p.unbindAll(); p.bindToLifecycle(this,CameraSelector.DEFAULT_FRONT_CAMERA,analysis)
        },ContextCompat.getMainExecutor(this))
    }

    private fun stopTracking() {
        tracking = false
        cameraProvider?.unbindAll()
        status.text = "● AI AVATAR  •  Face tracking OFF"
        findViewById<Button>(R.id.track).text = "Aktifkan Face Tracking"
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        if (hasRuntimePermissions()) {
            if (!tracking) startTracking()
            if (!audioRunning) startAudio()
        } else {
            ensureRuntimePermissions()
        }
    }

    override fun onPause() {
        activityResumed = false
        cameraProvider?.unbindAll()
        stopAudio()
        super.onPause()
    }

    override fun onDestroy() {
        activityResumed = false
        cameraProvider?.unbindAll()
        stopAudio()
        try { selfieSegmenter.close() } catch (_: Exception) {}
        try { detector.close() } catch (_: Exception) {}
        executor.shutdown()
        cutoutExecutor.shutdown()
        super.onDestroy()
    }
}
