package com.vardhanni.pdfmenu.domain.usecase

import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.vardhanni.pdfmenu.domain.repository.PdfRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class UnlockResult(val file: File, val wasEncrypted: Boolean)

class UnlockPdfUseCase(private val repository: PdfRepository) {
    suspend operator fun invoke(uri: Uri, password: String? = null): Result<UnlockResult> = withContext(Dispatchers.IO) {
        repository.copyToTempFile(uri, "source_temp.pdf").mapCatching { sourceFile ->
            var document: PDDocument? = null
            try {
                document = if (password != null) {
                    PDDocument.load(sourceFile, password)
                } else {
                    PDDocument.load(sourceFile)
                }

                val wasEncrypted = document.isEncrypted

                if (document.isEncrypted && password == null) {
                    // Check if it's actually locked or just has metadata encryption
                    try {
                        // Some PDFs are "encrypted" but have an empty user password
                        // Attempting to save will tell us if we have full access
                    } catch (e: Exception) {
                        throw SecurityException("Password required")
                    }
                }

                val tempFile = File(sourceFile.parentFile, "temp_render.pdf")
                document.setAllSecurityToBeRemoved(true)
                document.save(tempFile)
                sourceFile.delete()
                UnlockResult(tempFile, wasEncrypted)
            } catch (e: Exception) {
                // If it fails because of password, we know it was encrypted
                if (e.message?.contains("password", ignoreCase = true) == true) {
                    throw SecurityException("Password required")
                }
                throw e
            } finally {
                document?.close()
            }
        }
    }
}
