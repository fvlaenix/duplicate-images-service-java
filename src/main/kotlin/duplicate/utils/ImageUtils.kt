package com.fvlaenix.duplicate.utils

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.sql.rowset.serial.SerialBlob

object ImageUtils {
  
  fun getByteArray(image: BufferedImage, extension: String): ByteArray {
    val os = ByteArrayOutputStream()
    ImageIO.write(image, extension, os)
    return os.toByteArray()
  }
  
  fun getImageBlob(image: BufferedImage?, extension: String) : SerialBlob? {
    if (image == null) return null
    val os = ByteArrayOutputStream()
    ImageIO.write(image, extension, os)
    return SerialBlob(os.toByteArray())
  }

  fun getImageFromBlob(serialBlob: SerialBlob?) : BufferedImage? =
    if (serialBlob == null) null
    else ImageIO.read(serialBlob.binaryStream)

  fun Int.getRed() = this shr 16 and 0xff
  fun Int.getGreen() = this shr 8 and 0xff
  fun Int.getBlue() = this shr 0 and 0xff
}