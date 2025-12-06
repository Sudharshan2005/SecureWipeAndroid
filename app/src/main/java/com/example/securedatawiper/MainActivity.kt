package com.example.securedatawiper

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.securedatawiper.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.*
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isWiping = false
    private lateinit var certificateId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Generate unique certificate ID on app start
        certificateId = generateCertificateId()
        binding.tvCertificate.text = "Certificate ID: $certificateId"

        setupButton()
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
        binding.tvStatus.setTextColor(getColor(R.color.orange))

        // Clear log
        binding.tvLog.text = ""

        coroutineScope.launch {
            try {
                val wipeResult = performSecureWipe()

                if (wipeResult.success) {
                    binding.tvStatus.text = "✅ Wipe Complete!"
                    binding.tvStatus.setTextColor(getColor(R.color.green))
                    updateLog("✓ All data securely wiped")
                    updateLog("✓ Certificate: $certificateId")
                    updateLog("✓ Files processed: ${wipeResult.filesWiped}")
                    updateLog("✓ Total bytes: ${wipeResult.bytesWiped} bytes")
                } else {
                    binding.tvStatus.text = "❌ Wipe Failed"
                    binding.tvStatus.setTextColor(getColor(R.color.red))
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

                // Pass 2: Write random data
                val bufferRandom = ByteArray(4096)
                random.nextBytes(bufferRandom)
                writePass(raf, bufferRandom, length)

                // Pass 3: Write ones
                val bufferOnes = ByteArray(4096) { 0xFF.toByte() }
                writePass(raf, bufferOnes, length)

                // Final pass: Write certificate hash
                val certHash = certificateId.toByteArray()
                writePass(raf, certHash, min(length, certHash.size.toLong()))
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
        raf.fd.sync()
    }

    private fun createTestFiles(folder: File) {
        if (!folder.exists()) {
            folder.mkdirs()
        }

        // Create some dummy files for testing
        repeat(5) { index ->
            val testFile = File(folder, "test_file_$index.txt")
            testFile.writeText("This is test data for file $index. " +
                    "Lorem ipsum dolor sit amet, consectetur adipiscing elit. " +
                    "Generated at ${Date()}\nCertificate: $certificateId")
        }

        updateLog("Created 5 test files in ${folder.absolutePath}")
    }

    private fun generateCertificateId(): String {
        val timestamp = System.currentTimeMillis()
        val random = UUID.randomUUID().toString().replace("-", "").substring(0, 8)
        return "CERT-${timestamp}-$random"
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
