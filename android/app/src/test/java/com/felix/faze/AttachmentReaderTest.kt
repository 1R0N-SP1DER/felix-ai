package com.felix.faze

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentReaderTest {
    private fun tmp(name: String, bytes: ByteArray): File {
        val f = File(Files.createTempDirectory("rd").toFile(), name)
        f.writeBytes(bytes)
        return f
    }

    private val seen = ArrayList<String>()
    private val reader = AttachmentReader(
        vision = { _, q -> seen.add(q); "a cat on a sofa" },
        pdfPages = { listOf(byteArrayOf(1), byteArrayOf(2)) },
    )

    @Test fun aTextFileIsReadWhole() {
        val f = tmp("todo.txt", "buy milk".toByteArray())
        val a = reader.read(Attachment("todo.txt", f.path, Attachment.FILE), "what is on my list?")
        assertEquals("buy milk", a.context)
    }

    @Test fun aLongFileIsCutAndSaysSo() {
        val f = tmp("big.csv", "x".repeat(AttachmentReader.MAX_CHARS + 500).toByteArray())
        val c = reader.read(Attachment("big.csv", f.path, Attachment.FILE), "").context
        assertTrue(c.contains("only the first"))
        assertTrue(c.length < AttachmentReader.MAX_CHARS + 200)
    }

    @Test fun aPictureGoesToTheVisionModelWithTheQuestion() {
        val f = tmp("p.jpg", byteArrayOf(9, 9))
        val a = reader.read(Attachment("p.jpg", f.path, Attachment.IMAGE), "is this a dog?")
        assertEquals("a cat on a sofa", a.context)
        assertTrue(seen.single().contains("is this a dog?"))
    }

    @Test fun aPdfIsReadPageByPage() {
        val f = tmp("doc.pdf", byteArrayOf(1, 2, 3))
        val c = reader.read(Attachment("doc.pdf", f.path, Attachment.FILE), "summarise").context
        assertTrue(c.contains("Page 1:") && c.contains("Page 2:"))
        assertEquals(2, seen.size)
    }

    @Test fun aWordFileGivesItsParagraphs() {
        val xml = "<w:document><w:body><w:p><w:r><w:t>Hello</w:t></w:r></w:p><w:p><w:r><w:t>A &amp; B</w:t></w:r></w:p></w:body></w:document>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("word/document.xml")); z.write(xml.toByteArray()); z.closeEntry() }
        val f = tmp("a.docx", out.toByteArray())
        assertEquals("Hello\nA & B", reader.read(Attachment("a.docx", f.path, Attachment.FILE), "").context)
    }

    @Test fun aBinaryFileIsRefusedPlainlyAndAFailureIsTheContext() {
        val bin = tmp("x.bin", byteArrayOf(0, 1, 2, 0, 0))
        assertTrue(reader.read(Attachment("x.bin", bin.path, Attachment.FILE), "").context.contains("cannot read"))
        val gone = reader.read(Attachment("gone.txt", "/no/such/file.txt", Attachment.FILE), "")
        assertTrue(gone.context.startsWith("This attachment could not be read"))
    }

    @Test fun anAlreadyReadAttachmentIsNotReadAgain() {
        val a = Attachment("p.jpg", "/x", Attachment.IMAGE, "done")
        assertEquals(a, reader.read(a, "q"))
        assertTrue(seen.isEmpty())
    }

    @Test fun imageNamesAreRecognised() {
        assertTrue(AttachmentReader.isImageName("IMG_1.JPG"))
        assertFalse(AttachmentReader.isImageName("notes.txt"))
    }
}
