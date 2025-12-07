package com.example.securedatawiper

import android.content.ContentValues
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import com.example.securedatawiper.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.min

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private val job = SupervisorJob()
    private val uiScope = CoroutineScope(Dispatchers.Main + job)

    private var pickedTreeUri: Uri? = null
    private var certificateId: String = ""
    private var isWiping = false

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) {
                Toast.makeText(this, "No folder selected", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }

            try {
                val takeFlags = (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: Exception) {}

            pickedTreeUri = uri
            val folderName = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment
            binding.tvFolder.text = "Selected: $folderName"

            updateLog("Folder selected: $uri")
            binding.btnWipe.isEnabled = true
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        certificateId = generateCertificateId()
        binding.tvCertificate.text = "Certificate: $certificateId"

        binding.btnSelect.setOnClickListener { folderPicker.launch(null) }

        binding.btnWipe.setOnClickListener {
            if (pickedTreeUri == null) {
                Toast.makeText(this, "Select a folder first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            showDeleteConfirmDialog()
        }

        binding.btnWipe.isEnabled = false
        binding.progressBar.visibility = View.GONE
    }

    private fun showDeleteConfirmDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Confirm wipe")
            .setMessage("This will irreversibly wipe all data inside the selected folder.\nType DELETE to confirm.")
            .setView(R.layout.confirm_typed_dialog)
            .setPositiveButton("Confirm") { dialog, _ ->
                val input = (dialog as androidx.appcompat.app.AlertDialog)
                    .findViewById<android.widget.EditText>(R.id.confirm_input)
                    ?.text?.toString()?.trim() ?: ""

                if (input.equals("DELETE", ignoreCase = true)) {
                    startWipe()
                } else {
                    Toast.makeText(this, "Incorrect confirmation text", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startWipe() {
        val uri = pickedTreeUri ?: return

        isWiping = true
        binding.progressBar.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        binding.tvStatus.text = "Wiping..."
        binding.tvStatus.setTextColor(getColorCompat(R.color.orange))
        binding.tvLog.text = ""

        uiScope.launch {
            val deletedFiles = mutableListOf<DeletedFileInfo>()
            val startTime = System.currentTimeMillis()

            try {
                withContext(Dispatchers.IO) {
                    val rootDoc = DocumentFile.fromTreeUri(this@MainActivity, uri)
                        ?: throw Exception("Cannot access folder")

                    val allFiles = mutableListOf<DocumentFile>()
                    collectRecursive(rootDoc, allFiles)

                    updateLog("Files found: ${allFiles.size}")
                    var processed = 0

                    for (doc in allFiles) {
                        processed++
                        val fileName = doc.name ?: "unknown"
                        val fileSize = doc.length()

                        updateLog("Processing: $fileName ($fileSize bytes)")
                        val wiped = secureOverwrite(doc)

                        if (wiped) {
                            deletedFiles.add(DeletedFileInfo(fileName, fileSize))
                            doc.delete()
                            updateLog("✓ Deleted: $fileName")
                        } else updateLog("✗ Failed: $fileName")

                        val progress = processed * 100 / allFiles.size
                        withContext(Dispatchers.Main) { binding.progressBar.progress = progress }
                    }

                    deleteEmptyDirs(rootDoc)
                }

                val saved = savePDF(deletedFiles)

                binding.tvStatus.text = "Wipe Complete"
                binding.tvStatus.setTextColor(getColorCompat(R.color.green))
                updateLog("Done: ${deletedFiles.size} files wiped")

                if (saved) updateLog("Certificate saved to Downloads")
                else updateLog("Failed to save certificate")

            } catch (e: Exception) {
                binding.tvStatus.text = "Wipe Failed"
                binding.tvStatus.setTextColor(getColorCompat(R.color.red))
                updateLog("Error: ${e.message}")
            } finally {
                isWiping = false
                binding.progressBar.visibility = View.GONE
                updateLog("Total time: ${(System.currentTimeMillis() - startTime) / 1000}s")
            }
        }
    }

    private fun collectRecursive(dir: DocumentFile, out: MutableList<DocumentFile>) {
        dir.listFiles().forEach { child ->
            if (child.isDirectory) collectRecursive(child, out)
            else out.add(child)
        }
    }

    private fun deleteEmptyDirs(dir: DocumentFile) {
        dir.listFiles().forEach { child ->
            if (child.isDirectory) {
                deleteEmptyDirs(child)
                if (child.listFiles().isEmpty()) child.delete()
            }
        }
    }

    // ---------------------- SECURE WIPE LOGIC ----------------------

    private fun secureOverwrite(doc: DocumentFile): Boolean {
        val length = doc.length()
        if (length <= 0) return true

        contentResolver.openFileDescriptor(doc.uri, "rw")?.use { pfd ->
            val channel: FileChannel = FileOutputStream(pfd.fileDescriptor).channel

            try {
                fun pass(bytes: ByteArray) {
                    var remaining = length
                    val buffer = ByteBuffer.wrap(bytes)
                    channel.position(0)

                    while (remaining > 0) {
                        val w = min(remaining, bytes.size.toLong()).toInt()
                        buffer.limit(w)
                        buffer.position(0)
                        channel.write(buffer)
                        remaining -= w
                    }
                    channel.force(true)
                }

                pass(ByteArray(4096)) // zeros

                val randomBuf = ByteArray(4096)
                SecureRandom().nextBytes(randomBuf)
                pass(randomBuf) // random

                pass(ByteArray(4096) { 0xFF.toByte() }) // ones

                // Optional final certificate stamp
                val cert = certificateId.toByteArray()
                channel.position(0)
                var r = length
                while (r > 0) {
                    val size = min(r, cert.size.toLong()).toInt()
                    channel.write(ByteBuffer.wrap(cert, 0, size))
                    r -= size
                }
                channel.force(true)

                return true

            } catch (e: Exception) {
                Log.e("SecureWipe", "Overwrite failed: ${e.message}")
                return false
            } finally {
                channel.close()
            }
        }

        return false
    }

    // ---------------------- PDF GENERATION ----------------------

    private fun savePDF(files: List<DeletedFileInfo>): Boolean {
        return try {
            val name = "SecureWipe_${System.currentTimeMillis()}.pdf"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
                    ?: return false

                contentResolver.openOutputStream(uri)?.use { writePDF(it, files) }
                true

            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()

                val file = java.io.File(dir, name)
                FileOutputStream(file).use { writePDF(it, files) }
                true
            }

        } catch (e: Exception) {
            Log.e("PDF", "Error: ${e.message}")
            false
        }
    }

    private fun writePDF(os: OutputStream, files: List<DeletedFileInfo>) {
        val pdf = PdfDocument()
        var pageNumber = 1

        fun newPage(): Pair<Canvas, PdfDocument.Page> {
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
            val page = pdf.startPage(pageInfo)
            return Pair(page.canvas, page)
        }

        var (canvas, page) = newPage()
        val paint = Paint().apply { textSize = 12f }
        var y = 40f

        fun nextPage() {
            pdf.finishPage(page)
            pageNumber++
            val result = newPage()
            canvas = result.first
            page = result.second
            y = 40f
        }

        canvas.drawText("SecureWipe Certificate", 40f, y, paint)
        y += 30f
        canvas.drawText("Certificate ID: $certificateId", 40f, y, paint)
        y += 20f
        canvas.drawText("Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())}", 40f, y, paint)
        y += 30f
        canvas.drawText("Deleted Files:", 40f, y, paint)
        y += 20f

        files.forEach { file ->
            canvas.drawText("- ${file.name} (${file.size} bytes)", 50f, y, paint)
            y += 16f

            if (y > 800f) nextPage()
        }

        pdf.finishPage(page)
        pdf.writeTo(os)
        pdf.close()
    }

    // ---------------------- HELPERS ----------------------

    private fun updateLog(msg: String) {
        uiScope.launch {
            binding.tvLog.append("$msg\n")
        }
    }

    private fun getColorCompat(id: Int) = resources.getColor(id, theme)

    private fun generateCertificateId(): String {
        return "CERT-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }

    data class DeletedFileInfo(val name: String, val size: Long)
}
