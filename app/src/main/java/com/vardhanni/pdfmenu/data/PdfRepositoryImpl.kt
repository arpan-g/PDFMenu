package com.vardhanni.pdfmenu.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.vardhanni.pdfmenu.domain.repository.PdfRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

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
            document = PDDocument.load(sourceFile)
            compressedDoc = PDDocument()
            val renderer = PDFRenderer(document)

            for (i in 0 until document.numberOfPages) {
                val page = document.getPage(i)
                val mediaBox = page.mediaBox
                
                // Create a fresh page instead of importing to save memory
                val newPage = PDPage(mediaBox)
                compressedDoc.addPage(newPage)
                
                // Calculate DPI: 72 is minimum readable, 150 is standard
                val dpi = (72f + (150f - 72f) * quality)
                val bitmap = renderer.renderImageWithDPI(i, dpi)
                
                // Add compressed image to new page
                val pdImage = JPEGFactory.createFromImage(compressedDoc, bitmap, quality)
                PDPageContentStream(compressedDoc, newPage, PDPageContentStream.AppendMode.OVERWRITE, true).use { contentStream ->
                    contentStream.drawImage(pdImage, 0f, 0f, mediaBox.width, mediaBox.height)
                }
                
                // CRITICAL: Recycle bitmap immediately to free native memory
                bitmap.recycle()
            }

            val compressedFile = File(cacheDir, "compressed.pdf")
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
