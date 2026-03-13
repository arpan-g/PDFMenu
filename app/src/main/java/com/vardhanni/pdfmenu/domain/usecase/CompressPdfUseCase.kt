package com.vardhanni.pdfmenu.domain.usecase

import com.vardhanni.pdfmenu.domain.repository.PdfRepository
import java.io.File

class CompressPdfUseCase(private val repository: PdfRepository) {
    suspend operator fun invoke(sourceFile: File, quality: Float): Result<File> {
        return repository.compressPdf(sourceFile, quality)
    }
}
