package com.vardhanni.pdfmenu.data

import android.content.ContentResolver
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PdfRepository(
    private val contentResolver: ContentResolver,
    private val cacheDir: File
) {
    suspend fun processPdf(uri: Uri, password: String? = null): Result<File> = withContext(Dispatchers.IO) {
        try {
            val sourceFile = File(cacheDir, "source_temp.pdf")
            contentResolver.openInputStream(uri)?.use { input ->
                sourceFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(Exception("Failed to open input stream"))

            val document = try {
                if (password != null) PDDocument.load(sourceFile, password) 
                else PDDocument.load(sourceFile)
            } catch (e: Exception) {
                return@withContext Result.failure(e)
            }

            if (document.isEncrypted && password == null) {
                document.close()
                return@withContext Result.failure(SecurityException("Password required"))
            }

            val tempFile = File(cacheDir, "temp_render.pdf")
            document.setAllSecurityToBeRemoved(true)
            document.save(tempFile)
            document.close()
            sourceFile.delete()
            
            Result.success(tempFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveUnprotectedPdf(sourceFile: File, targetUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                sourceFile.inputStream().use { it.copyTo(outputStream) }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
