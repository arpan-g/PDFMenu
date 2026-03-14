package com.vardhanni.pdfmenu.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
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

    override suspend fun compressPdf(sourceFile: File, quality: Float): Result<File> = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        val compressedFile = File(cacheDir, "compressed_${System.currentTimeMillis()}.pdf")
        
        try {
            val memorySetting = MemoryUsageSetting.setupTempFileOnly()
            document = PDDocument.load(sourceFile, memorySetting)
            
            val processedImages = mutableMapOf<COSBase, PDImageXObject>()
            val outputStream = ByteArrayOutputStream()
            
            for (page in document.pages) {
                if (!coroutineContext.isActive) throw Exception("Cancelled")
                
                val resources = page.resources ?: continue
                for (name in resources.xObjectNames) {
                    if (!coroutineContext.isActive) throw Exception("Cancelled")
                    
                    val xObject = resources.getXObject(name)
                    if (xObject is PDImageXObject) {
                        val cosObject = xObject.cosObject
                        
                        if (processedImages.containsKey(cosObject)) {
                            resources.put(name, processedImages[cosObject])
                            continue
                        }

                        val bitmap = xObject.image
                        if (bitmap.isRecycled) continue

                        outputStream.reset()
                        val androidQuality = (quality * 100).toInt().coerceIn(10, 100)
                        
                        bitmap.compress(Bitmap.CompressFormat.JPEG, androidQuality, outputStream)
                        
                        val compressedImage = PDImageXObject.createFromByteArray(
                            document, 
                            outputStream.toByteArray(), 
                            name.name
                        )
                        
                        resources.put(name, compressedImage)
                        processedImages[cosObject] = compressedImage
                    }
                }
            }

            document.documentInformation.title = null
            document.documentInformation.producer = "PDFMenu"
            
            document.save(compressedFile)
            Result.success(compressedFile)
        } catch (e: Exception) {
            if (compressedFile.exists()) compressedFile.delete()
            Result.failure(e)
        } finally {
            try {
                document?.close()
            } catch (e: Exception) {}
        }
    }

    override suspend fun lockPdf(sourceFile: File, password: String): Result<File> = withContext(Dispatchers.IO) {
        var document: PDDocument? = null
        val lockedFile = File(cacheDir, "locked_${System.currentTimeMillis()}.pdf")
        try {
            document = PDDocument.load(sourceFile)
            val ap = AccessPermission()
            val spp = StandardProtectionPolicy(password, password, ap)
            spp.encryptionKeyLength = 128
            document.protect(spp)
            document.save(lockedFile)
            Result.success(lockedFile)
        } catch (e: Exception) {
            if (lockedFile.exists()) lockedFile.delete()
            Result.failure(e)
        } finally {
            document?.close()
        }
    }
}
