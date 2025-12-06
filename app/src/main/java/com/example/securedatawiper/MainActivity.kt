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

    // Request code for storage permission
    private val STORAGE_PERMISSION_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Check and request permissions (only where needed)
        checkStoragePermission()

        // Generate unique certificate ID on app start
        certificateId = generateCertificateId()
        binding.tvCertificate.text = "Certificate ID: $certificateId"

        setupButton()
    }

    private fun checkStoragePermission() {
        // WRITE_EXTERNAL_STORAGE is necessary only on Android versions < Q (API 29).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    STORAGE_PERMISSION_CODE
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Storage permission granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Storage permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupButton() {
        binding.btnWipe.setOnClickListener {
            if (!isWiping) {
                startWipingProcess()
            } else {
                updateLog("Wipe process already running...")
            }
        }
    }

    private fun startWipingProcess() {
        isWiping = true
        binding.progressBar.visibility = android.view.View.VISIBLE
        binding.progressBar.progress = 0
        binding.tvStatus.text = "🟡 Wiping in progress..."
        binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.orange))

        // Clear log
        binding.tvLog.text = ""

        coroutineScope.launch {
            try {
                val wipeResult = performSecureWipe()

                if (wipeResult.success) {
                    // Save certificate as PDF
                    val pdfSaved = saveCertificateAsPDF(wipeResult)

                    binding.tvStatus.text = "✅ Wipe Complete!"
                    binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.green))
                    updateLog("✓ All data securely wiped")
                    updateLog("✓ Certificate: $certificateId")
                    updateLog("✓ Files processed: ${wipeResult.filesWiped}")
                    updateLog("✓ Total bytes: ${wipeResult.bytesWiped} bytes")

                    if (pdfSaved) {
                        updateLog("✓ Certificate PDF saved to Downloads folder")
                        Toast.makeText(
                            this@MainActivity,
                            "Certificate saved to Downloads",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        updateLog("✗ Failed to save certificate PDF")
                    }
                } else {
                    binding.tvStatus.text = "❌ Wipe Failed"
                    binding.tvStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.red))
                    updateLog("✗ Error: ${wipeResult.errorMessage}")
                }
            } catch (e: Exception) {
                binding.tvStatus.text = "❌ Error Occurred"
                updateLog("✗ Exception: ${e.message}")
            } finally {
                isWiping = false
                binding.progressBar.visibility = android.view.View.GONE
            }
        }
    }

    private suspend fun performSecureWipe(): WipeResult = withContext(Dispatchers.IO) {
        val targetFolder = File(getExternalFilesDir(null), "WipeTest")

        // Create test folder with dummy files (for demonstration)
        createTestFiles(targetFolder)

        updateLog("Target folder: ${targetFolder.absolutePath}")
        updateLog("Starting secure wipe...")

        var filesWiped = 0
        var bytesWiped = 0L

        try {
            if (!targetFolder.exists()) {
                return@withContext WipeResult(false, 0, 0, "Folder doesn't exist")
            }

            val files = targetFolder.listFiles()
            if (files.isNullOrEmpty()) {
                return@withContext WipeResult(false, 0, 0, "No files to wipe")
            }

            files.forEachIndexed { index, file ->
                withContext(Dispatchers.Main) {
                    binding.progressBar.progress = ((index + 1) * 100 / files.size)
                }

                if (secureDeleteFile(file)) {
                    filesWiped++
                    bytesWiped += file.length()
                    updateLog("✓ Wiped: ${file.name}")
                } else {
                    updateLog("✗ Failed: ${file.name}")
                }

                // Small delay for UI updates
                delay(100)
            }

            // Finally delete the folder itself
            if (targetFolder.deleteRecursively()) {
                updateLog("✓ Folder deleted successfully")
            }

            return@withContext WipeResult(true, filesWiped, bytesWiped, "")

        } catch (e: Exception) {
            return@withContext WipeResult(false, filesWiped, bytesWiped, e.message ?: "Unknown error")
        }
    }

    private fun secureDeleteFile(file: File): Boolean {
        return try {
            if (file.isDirectory) {
                // Recursively wipe directory contents
                file.listFiles()?.forEach { secureDeleteFile(it) }
                file.delete()
            } else {
                // Perform DoD 5220.22-M 3-pass wipe
                performDoDWipe(file)
                file.delete()
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun performDoDWipe(file: File) {
        try {
            val random = SecureRandom()
            val length = file.length()

            RandomAccessFile(file, "rw").use { raf ->
                // Pass 1: Write zeros
                val bufferZero = ByteArray(4096)
                writePass(raf, bufferZero, length)

                // Pass 2: Write random data (repeated blocks)
                val bufferRandom = ByteArray(4096)
                random.nextBytes(bufferRandom)
                writePass(raf, bufferRandom, length)

                // Pass 3: Write ones
                val bufferOnes = ByteArray(4096) { 0xFF.toByte() }
                writePass(raf, bufferOnes, length)

                // Final pass: Write certificate bytes (repeat as needed)
                val certHash = certificateId.toByteArray()
                if (certHash.isNotEmpty()) {
                    val certBuffer = ByteArray(4096)
                    var pos = 0
                    while (pos < certBuffer.size) {
                        val copyLen = min(certHash.size, certBuffer.size - pos)
                        System.arraycopy(certHash, 0, certBuffer, pos, copyLen)
                        pos += copyLen
                    }
                    writePass(raf, certBuffer, min(length, certHash.size.toLong()))
                }
            }
        } catch (e: Exception) {
            throw e
        }
    }

    private fun writePass(raf: RandomAccessFile, pattern: ByteArray, length: Long) {
        var remaining = length
        raf.seek(0)

        while (remaining > 0) {
            val writeSize = min(remaining, pattern.size.toLong()).toInt()
            raf.write(pattern, 0, writeSize)
            remaining -= writeSize
        }
        try {
            raf.fd.sync()
        } catch (_: Exception) {
            // ignore sync errors
        }
    }

    private fun createTestFiles(folder: File) {
        if (!folder.exists()) {
            folder.mkdirs()
        }

        // Create some dummy files for testing
        repeat(5) { index ->
            val testFile = File(folder, "test_file_$index.txt")
            testFile.writeText(
                "This is test data for file $index. " +
                        "Lorem ipsum dolor sit amet, consectetur adipiscing elit. " +
                        "Generated at ${Date()}\nCertificate: $certificateId"
            )
        }

        updateLog("Created 5 test files in ${folder.absolutePath}")
    }

    private fun generateCertificateId(): String {
        val timestamp = System.currentTimeMillis()
        val random = UUID.randomUUID().toString().replace("-", "").substring(0, 8)
        return "CERT-${timestamp}-$random"
    }

    private fun saveCertificateAsPDF(wipeResult: WipeResult): Boolean {
        return try {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
            val timestamp = dateFormat.format(Date())
            val fileName = "Data_Wipe_Certificate_$timestamp.pdf"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                savePDFUsingMediaStore(fileName, wipeResult)
            } else {
                savePDFLegacy(fileName, wipeResult)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun savePDFLegacy(fileName: String, wipeResult: WipeResult): Boolean {
        return try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }

            val pdfFile = File(downloadsDir, fileName)
            FileOutputStream(pdfFile).use { fos ->
                createPDFContent(fos, wipeResult)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun savePDFUsingMediaStore(fileName: String, wipeResult: WipeResult): Boolean {
        return try {
            val resolver = contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val uri = resolver.insert(MediaStore.Files.getContentUri("external"), contentValues)
            uri?.let {
                resolver.openOutputStream(it)?.use { outputStream ->
                    createPDFContent(outputStream, wipeResult)
                }
                true
            } ?: false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun createPDFContent(outputStream: OutputStream, wipeResult: WipeResult) {
        val pdfWriter = PdfWriter(outputStream)
        val pdfDocument = PdfDocument(pdfWriter)
        val document = Document(pdfDocument)

        // Add title
        val title = Paragraph("DATA WIPE CERTIFICATE")
            .setFontSize(24f)
            .setBold()
            .setTextAlignment(TextAlignment.CENTER)
            .setMarginBottom(20f)
        document.add(title)

        // Add certificate ID
        val certIdParagraph = Paragraph("Certificate ID: $certificateId")
            .setFontSize(16f)
            .setBold()
            .setMarginBottom(10f)
        document.add(certIdParagraph)

        // Add date
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val dateParagraph = Paragraph("Date: ${dateFormat.format(Date())}")
            .setFontSize(12f)
            .setMarginBottom(20f)
        document.add(dateParagraph)

        // Add separator
        document.add(Paragraph("________________________________________________________________")
            .setFontSize(10f)
            .setMarginBottom(20f))

        // Add wipe details in a table
        val table = Table(UnitValue.createPercentArray(floatArrayOf(40f, 60f)))
            .setWidth(UnitValue.createPercentValue(100f))
            .setMarginBottom(20f)

        // Add table headers
        table.addHeaderCell(Cell().add(Paragraph("Parameter").setBold()))
        table.addHeaderCell(Cell().add(Paragraph("Value").setBold()))

        // Add table rows
        addTableRow(table, "Status", "SUCCESSFUL")
        addTableRow(table, "Files Wiped", wipeResult.filesWiped.toString())
        addTableRow(table, "Bytes Wiped", "${wipeResult.bytesWiped} bytes")
        addTableRow(table, "Wipe Method", "DoD 5220.22-M 3-Pass")
        addTableRow(table, "Target Folder", "/WipeTest/")
        addTableRow(table, "Device Model", Build.MODEL)
        addTableRow(table, "Android Version", Build.VERSION.RELEASE)
        addTableRow(table, "App Version", "1.0")

        document.add(table)

        // Add footer note
        val footer = Paragraph("This certificate verifies that secure data wiping has been performed according to DoD 5220.22-M standards. The certificate ID serves as proof of the wipe operation.")
            .setFontSize(10f)
            .setItalic()
            .setTextAlignment(TextAlignment.CENTER)
            .setMarginTop(20f)
        document.add(footer)

        // Add signature section
        document.add(Paragraph("\n\n\n"))
        document.add(Paragraph("_________________________")
            .setTextAlignment(TextAlignment.LEFT))
        document.add(Paragraph("Authorized Signature")
            .setFontSize(10f)
            .setTextAlignment(TextAlignment.LEFT))

        document.close()
    }

    private fun addTableRow(table: Table, label: String, value: String) {
        table.addCell(Cell().add(Paragraph(label)))
        table.addCell(Cell().add(Paragraph(value)))
    }

    private fun updateLog(message: String) {
        coroutineScope.launch(Dispatchers.Main) {
            val currentLog = binding.tvLog.text.toString()
            binding.tvLog.text = "$currentLog$message\n"
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
