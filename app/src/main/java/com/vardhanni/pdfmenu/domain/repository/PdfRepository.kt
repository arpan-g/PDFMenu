package com.vardhanni.pdfmenu.domain.repository

import android.net.Uri
import java.io.File

interface PdfRepository {
    suspend fun copyToTempFile(uri: Uri, fileName: String): Result<File>
    suspend fun savePdf(sourceFile: File, targetUri: Uri): Result<Unit>
    suspend fun compressPdf(sourceFile: File, quality: Float): Result<File>
}
