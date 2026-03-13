package com.vardhanni.pdfmenu.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.vardhanni.pdfmenu.domain.repository.PdfRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

class PdfRepositoryImpl(
    private val contentResolver: ContentResolver,
    private val cacheDir: File
) : PdfRepository {

    override suspend fun copyToTempFile(uri: Uri, fileName: String): Result<File> = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(cacheDir, fileName)
            contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(Exception("Failed to open input stream"))
            Result.success(tempFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun savePdf(sourceFile: File, targetUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                sourceFile.inputStream().use { it.copyTo(outputStream) }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun compressPdf(sourceFile: File, quality: Float): Result<File> = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        var compressedDoc: PDDocument? = null
        try {
            // Memory optimization: Use temp files instead of heap memory for processing
            val memorySetting = MemoryUsageSetting.setupTempFileOnly()
            document = PDDocument.load(sourceFile, memorySetting)
            compressedDoc = PDDocument(memorySetting)
            val renderer = PDFRenderer(document)

            for (i in 0 until document.numberOfPages) {
                // Check if the coroutine was cancelled (e.g., slider moved again)
                if (!coroutineContext.isActive) throw Exception("Compression cancelled")

                val page = document.getPage(i)
                val mediaBox = page.mediaBox
                
                val newPage = PDPage(mediaBox)
                compressedDoc.addPage(newPage)
                
                // Calculate scale: 1.0f is standard, we cap the max rendering resolution
                // to avoid OOM even at 100% quality.
                val scale = 1.0f + (quality * 1.0f) 
                
                // CRITICAL: ImageType.RGB avoids alpha channel processing, 
                // reducing memory usage by ~50% during rendering and JPEG encoding.
                val bitmap = renderer.renderImage(i, scale, ImageType.RGB)
                
                // JPEGFactory uses the provided quality (0.0 to 1.0)
                val pdImage = JPEGFactory.createFromImage(compressedDoc, bitmap, quality)
                PDPageContentStream(compressedDoc, newPage, PDPageContentStream.AppendMode.OVERWRITE, true).use { contentStream ->
                    contentStream.drawImage(pdImage, 0f, 0f, mediaBox.width, mediaBox.height)
                }
                
                bitmap.recycle() // Release native memory immediately
            }

            val compressedFile = File(cacheDir, "compressed_${System.currentTimeMillis()}.pdf")
            compressedDoc.save(compressedFile)
            Result.success(compressedFile)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                compressedDoc?.close()
                document?.close()
            } catch (e: Exception) {}
        }
    }
}
