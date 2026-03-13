package com.vardhanni.pdfmenu.domain.usecase

import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.vardhanni.pdfmenu.domain.repository.PdfRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class UnlockPdfUseCase(private val repository: PdfRepository) {
    suspend operator fun invoke(uri: Uri, password: String? = null): Result<File> = withContext(Dispatchers.IO) {
        repository.copyToTempFile(uri, "source_temp.pdf").mapCatching { sourceFile ->
            val document = try {
                if (password != null) PDDocument.load(sourceFile, password) 
                else PDDocument.load(sourceFile)
            } catch (e: Exception) {
                throw e
            }

            if (document.isEncrypted && password == null) {
                document.close()
                throw SecurityException("Password required")
            }

            val tempFile = File(sourceFile.parentFile, "temp_render.pdf")
            document.setAllSecurityToBeRemoved(true)
            document.save(tempFile)
            document.close()
            sourceFile.delete()
            tempFile
        }
    }
}
