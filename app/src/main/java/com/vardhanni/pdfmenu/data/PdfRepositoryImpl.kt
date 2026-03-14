package com.vardhanni.pdfmenu.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.vardhanni.pdfmenu.domain.repository.PdfRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
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

    /**
     * Improved Compression: Object-Level Image Compression with deduplication.
     * Prevents "can't compress a recycled bitmap" by tracking processed images.
     */
    override suspend fun compressPdf(sourceFile: File, quality: Float): Result<File> = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        try {
            val memorySetting = MemoryUsageSetting.setupTempFileOnly()
            document = PDDocument.load(sourceFile, memorySetting)
            
            // Track processed images by their COSObject to avoid re-compressing 
            // shared images (like logos) and hitting the "recycled bitmap" error.
            val processedImages = mutableMapOf<COSBase, PDImageXObject>()
            
            for (page in document.pages) {
                if (!coroutineContext.isActive) throw Exception("Cancelled")
                
                val resources = page.resources ?: continue
                for (name in resources.xObjectNames) {
                    val xObject = resources.getXObject(name)
                    
                    if (xObject is PDImageXObject) {
                        val cosObject = xObject.cosObject
                        
                        // Check if we've already compressed this specific image object
                        if (processedImages.containsKey(cosObject)) {
                            resources.put(name, processedImages[cosObject])
                            continue
                        }

                        val bitmap = xObject.image
                        if (bitmap.isRecycled) continue

                        val out = ByteArrayOutputStream()
                        val androidQuality = (quality * 100).toInt().coerceIn(10, 100)
                        
                        // Perform compression
                        bitmap.compress(Bitmap.CompressFormat.JPEG, androidQuality, out)
                        
                        val compressedImage = PDImageXObject.createFromByteArray(
                            document, 
                            out.toByteArray(), 
                            name.name
                        )
                        
                        // Update the reference in resources and track it
                        resources.put(name, compressedImage)
                        processedImages[cosObject] = compressedImage
                        
                        // We don't manually recycle the bitmap here because PDFBox 
                        // might manage its lifecycle or share it across objects.
                        // Android's GC will handle it more safely.
                    }
                }
            }

            // Clean up metadata
            document.documentInformation.title = null
            document.documentInformation.producer = "PDFMenu"
            
            val compressedFile = File(cacheDir, "compressed_${System.currentTimeMillis()}.pdf")
            document.save(compressedFile)
            Result.success(compressedFile)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                document?.close()
            } catch (e: Exception) {}
        }
    }
}
