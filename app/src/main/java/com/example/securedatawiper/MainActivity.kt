package com.example.securedatawiper

import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.securedatawiper.databinding.ActivityMainBinding
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isWiping = false
    private lateinit var certificateId: String

    private val STORAGE_PERMISSION_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkStoragePermission()
        certificateId = generateCertificateId()
        binding.tvCertificate.text = "Certificate ID: $certificateId"

        setupButton()
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
        ) {
            if (ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    STORAGE_PERMISSION_CODE
                )
            }
        }
    }

    private fun setupButton() {
        binding.btnWipe.setOnClickListener {
            if (!isWiping) startWipingProcess()
            else updateLog("Wipe process already running...")
        }
    }

    private fun startWipingProcess() {
        isWiping = true
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.progressBar.progress = 0
        binding.tvStatus.text = "🟡 Wiping in progress..."
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.orange))
        binding.tvLog.text = ""

        coroutineScope.launch {
            try {
                val result = performSecureWipe()

                if (result.success) {
                    val pdfSaved = saveCertificateAsPDF(result)

                    binding.tvStatus.text = "✅ Wipe Complete!"
                    binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.green))

                    updateLog("✓ Files wiped: ${result.filesWiped}")
                    updateLog("✓ Total bytes wiped: ${result.bytesWiped}")
                    updateLog("✓ Certificate: $certificateId")

                    if (pdfSaved) {
                        updateLog("✓ Certificate saved in Downloads")
                        Toast.makeText(this@MainActivity, "Certificate saved to Downloads", Toast.LENGTH_LONG).show()
                    }
                } else {
                    binding.tvStatus.text = "❌ Wipe Failed"
                    binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.red))
                    updateLog("Error: ${result.errorMessage}")
                }

            } catch (e: Exception) {
                updateLog("Exception: ${e.message}")
            } finally {
                isWiping = false
                binding.progressBar.visibility = android.view.View.GONE
            }
        }
    }

    private suspend fun performSecureWipe(): WipeResult = withContext(Dispatchers.IO) {
        val folder = File(getExternalFilesDir(null), "WipeTest")

        updateLog("Target folder: ${folder.absolutePath}")

        if (!folder.exists()) {
            return@withContext WipeResult(false, 0, 0, "Folder doesn't exist")
        }

        val files = folder.listFiles()
        if (files.isNullOrEmpty()) {
            return@withContext WipeResult(false, 0, 0, "No files to wipe")
        }

        var filesWiped = 0
        var bytesWiped = 0L

        files.forEachIndexed { index, file ->
            withContext(Dispatchers.Main) {
                binding.progressBar.progress = ((index + 1) * 100 / files.size)
            }

            if (secureDeleteFile(file)) {
                filesWiped++
                bytesWiped += file.length()
                updateLog("✓ Wiped: ${file.name}")
            } else {
                updateLog("✗ Failed to wipe: ${file.name}")
            }
        }

        folder.deleteRecursively()

        return@withContext WipeResult(true, filesWiped, bytesWiped, "")
    }

    private fun secureDeleteFile(file: File): Boolean {
        return try {
            if (file.isDirectory) {
                file.listFiles()?.forEach { secureDeleteFile(it) }
                file.delete()
            } else {
                performDoDWipe(file)
                file.delete()
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun performDoDWipe(file: File) {
        val random = SecureRandom()
        val length = file.length()

        RandomAccessFile(file, "rw").use { raf ->
            val zero = ByteArray(4096)
            writePass(raf, zero, length)

            val randomBuf = ByteArray(4096)
            random.nextBytes(randomBuf)
            writePass(raf, randomBuf, length)

            val ones = ByteArray(4096) { 0xFF.toByte() }
            writePass(raf, ones, length)
        }
    }

    private fun writePass(raf: RandomAccessFile, pattern: ByteArray, length: Long) {
        var remaining = length
        raf.seek(0)

        while (remaining > 0) {
            val size = min(remaining, pattern.size.toLong()).toInt()
            raf.write(pattern, 0, size)
            remaining -= size
        }
        raf.fd.sync()
    }

    private fun generateCertificateId(): String {
        val time = System.currentTimeMillis()
        val rand = UUID.randomUUID().toString().take(8)
        return "CERT-$time-$rand"
    }

    private fun saveCertificateAsPDF(result: WipeResult): Boolean {
        val fileName = "Data_Wipe_Certificate_${System.currentTimeMillis()}.pdf"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            savePDFUsingMediaStore(fileName, result)
        else
            savePDFLegacy(fileName, result)
    }

    @Suppress("DEPRECATION")
    private fun savePDFLegacy(fileName: String, result: WipeResult): Boolean {
        return try {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val pdfFile = File(downloads, fileName)
            FileOutputStream(pdfFile).use { createPDFContent(it, result) }
            true
        } catch (e: Exception) { false }
    }

    private fun savePDFUsingMediaStore(fileName: String, result: WipeResult): Boolean {
        return try {
            val resolver = contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
            uri?.let {
                resolver.openOutputStream(it)?.use { os -> createPDFContent(os, result) }
                true
            } ?: false
        } catch (e: Exception) { false }
    }

    private fun createPDFContent(os: OutputStream, result: WipeResult) {
        val writer = PdfWriter(os)
        val pdf = PdfDocument(writer)
        val doc = Document(pdf)

        doc.add(
            Paragraph("DATA WIPE CERTIFICATE")
                .setFontSize(22f)
                .setBold()
                .setTextAlignment(TextAlignment.CENTER)
        )

        doc.add(Paragraph("\nCertificate ID: $certificateId"))
        doc.add(Paragraph("Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}"))

        val table = Table(UnitValue.createPercentArray(floatArrayOf(40f, 60f)))
            .setWidth(UnitValue.createPercentValue(100f))

        addRow(table, "Files Wiped", result.filesWiped.toString())
        addRow(table, "Bytes Wiped", result.bytesWiped.toString())
        addRow(table, "Method", "DoD 5220.22-M (3-pass)")
        addRow(table, "Folder", "/WipeTest")

        doc.add(table)
        doc.close()
    }

    private fun addRow(table: Table, key: String, value: String) {
        table.addCell(Cell().add(Paragraph(key)))
        table.addCell(Cell().add(Paragraph(value)))
    }

    private fun updateLog(msg: String) {
        coroutineScope.launch(Dispatchers.Main) {
            binding.tvLog.append("$msg\n")
        }
    }

    data class WipeResult(
        val success: Boolean,
        val filesWiped: Int,
        val bytesWiped: Long,
        val errorMessage: String
    )

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
    }
}
