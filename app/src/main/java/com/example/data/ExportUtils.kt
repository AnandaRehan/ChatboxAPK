package com.example.data

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object ExportUtils {
    private const val TAG = "ExportUtils"

    /**
     * Converts a session and list of messages into Markdown format
     */
    fun exportToMarkdown(sessionTitle: String, messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        sb.append("# Riwayat Percakapan: $sessionTitle\n\n")
        sb.append("*Ekspor otomatis dari Chatbox AI*\n\n")
        sb.append("---\n\n")
        
        for (msg in messages) {
            val senderLabel = if (msg.role.lowercase() == "user") "**Kamu (User)**" else "**AI Assistant**"
            sb.append("$senderLabel:\n")
            sb.append("${msg.content}\n\n")
            if (msg.imagePath != null) {
                sb.append("*(Disertai lampiran gambar)*\n\n")
            }
            sb.append("---\n\n")
        }
        return sb.toString()
    }

    /**
     * Generates a fully formatted multi-page PDF locally on the device and returns its File path.
     */
    fun exportToPdf(context: Context, sessionTitle: String, messages: List<ChatMessage>): File? {
        val pdfDocument = PdfDocument()
        
        // A4 page size dimensions: 595 x 842 points (at 72 dpi)
        val pageWidth = 595
        val pageHeight = 842
        val margin = 40
        val maxTextWidth = pageWidth - (margin * 2)

        val paint = Paint().apply {
            isAntiAlias = true
        }

        var pageNumber = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
        var page = pdfDocument.startPage(pageInfo)
        var canvas = page.canvas
        
        var y = 60f

        // Draw Document Header
        paint.textSize = 18f
        paint.isFakeBoldText = true
        canvas.drawText("Chatbox AI - Riwayat Chat", margin.toFloat(), y, paint)
        y += 24f

        paint.textSize = 12f
        paint.isFakeBoldText = false
        paint.color = 0xFF777777.toInt()
        canvas.drawText("Sesi: $sessionTitle", margin.toFloat(), y, paint)
        y += 30f

        paint.color = 0xFF000000.toInt() // Reset to black

        for (msg in messages) {
            // Draw Speaker Label
            val label = if (msg.role.lowercase() == "user") "Kamu:" else "AI Assistant:"
            
            // Check spacing for label (at least 50 points needed or start new page)
            if (y > pageHeight - margin - 30) {
                pdfDocument.finishPage(page)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                page = pdfDocument.startPage(pageInfo)
                canvas = page.canvas
                y = 60f
            }

            paint.textSize = 12f
            paint.isFakeBoldText = true
            paint.color = if (msg.role.lowercase() == "user") 0xFF1D4ED8.toInt() else 0xFF0F766E.toInt() // Blue for user, teal for assistant
            canvas.drawText(label, margin.toFloat(), y, paint)
            y += 18f

            paint.color = 0xFF000000.toInt() // Reset text color
            paint.isFakeBoldText = false
            paint.textSize = 11f

            // Handle newline breaks and wrapping
            val lines = wrapText(msg.content, maxTextWidth, paint)
            for (line in lines) {
                // Check if current page is full
                if (y > pageHeight - margin - 15) {
                    pdfDocument.finishPage(page)
                    pageNumber++
                    pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas
                    y = 60f
                }
                canvas.drawText(line, margin.toFloat(), y, paint)
                y += 16f
            }

            if (msg.imagePath != null) {
                y += 5f
                paint.color = 0xFF777777.toInt()
                paint.textSize = 9f
                canvas.drawText("[Lampiran Gambar Disimpan Lokal]", margin.toFloat(), y, paint)
                y += 12f
            }

            y += 20f // Message spacing margin
        }

        pdfDocument.finishPage(page)

        return try {
            val cacheFile = File(context.cacheDir, "chat_export_${System.currentTimeMillis()}.pdf")
            val fos = FileOutputStream(cacheFile)
            pdfDocument.writeTo(fos)
            pdfDocument.close()
            fos.close()
            cacheFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write PDF file to cache", e)
            pdfDocument.close()
            null
        }
    }

    /**
     * Splits a text string into multi-line paragraphs that fit the given pixel width constraints.
     */
    private fun wrapText(text: String, maxWidth: Int, paint: Paint): List<String> {
        val lines = mutableListOf<String>()
        // Split by hard newlines first
        val paragraphs = text.split("\n")
        
        for (paragraph in paragraphs) {
            if (paragraph.trim().isEmpty()) {
                lines.add("")
                continue
            }
            
            val words = paragraph.split(" ")
            var currentLine = ""
            
            for (word in words) {
                if (word.isEmpty()) continue
                val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
                val width = paint.measureText(testLine)
                if (width <= maxWidth) {
                    currentLine = testLine
                } else {
                    if (currentLine.isNotEmpty()) {
                        lines.add(currentLine)
                    }
                    currentLine = word
                    
                    // If a single word itself exceeds maxWidth, chop it down
                    while (paint.measureText(currentLine) > maxWidth && currentLine.length > 1) {
                        var charIndex = currentLine.length - 1
                        while (charIndex > 0 && paint.measureText(currentLine.substring(0, charIndex)) > maxWidth) {
                            charIndex--
                        }
                        if (charIndex > 0) {
                            lines.add(currentLine.substring(0, charIndex))
                            currentLine = currentLine.substring(charIndex)
                        } else {
                            break
                        }
                    }
                }
            }
            if (currentLine.isNotEmpty()) {
                lines.add(currentLine)
            }
        }
        return lines
    }
}
