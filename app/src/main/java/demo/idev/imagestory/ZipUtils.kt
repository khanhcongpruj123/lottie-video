package demo.idev.imagestory

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

object ZipUtils {

    fun unzip(zipFilePath: String, destDir: String) {
        val dir = File(destDir)
        // create output directory if it doesn't exist
        if (!dir.exists()) dir.mkdirs()
        val buffer = ByteArray(1024)
        val zis = ZipInputStream(FileInputStream(zipFilePath))
        var zipEntry = zis.nextEntry
        while (zipEntry != null) {
            val newFile = File(destDir + File.separator + zipEntry.name)
            if (zipEntry.isDirectory) {
                newFile.mkdirs()
            } else {
                // create all non exists folders
                File(newFile.parent).mkdirs()
                val fos = FileOutputStream(newFile)
                var len: Int
                while (zis.read(buffer).also { len = it } > 0) {
                    fos.write(buffer, 0, len)
                }
                fos.close()
            }
            zipEntry = zis.nextEntry
        }
        zis.closeEntry()
        zis.close()
    }
}