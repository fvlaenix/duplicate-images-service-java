package duplicate.utils

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.sql.rowset.serial.SerialBlob
import kotlin.math.roundToInt

object ImageUtils {
  
  fun getByteArray(image: BufferedImage, extension: String): ByteArray {
    val os = ByteArrayOutputStream()
    ImageIO.write(image, extension, os)
    return os.toByteArray()
  }

  fun getImageBlob(image: BufferedImage, extension: String): SerialBlob {
    return SerialBlob(getByteArray(image, extension))
  }

  fun getImageFromBlob(serialBlob: SerialBlob?) : BufferedImage? =
    if (serialBlob == null) null
    else ImageIO.read(serialBlob.binaryStream)

  fun Int.getRed() = this shr 16 and 0xff
  fun Int.getGreen() = this shr 8 and 0xff
  fun Int.getBlue() = this shr 0 and 0xff

  fun Int.getGray(): Int {
    val r = getRed()
    val g = getGreen()
    val b = getBlue()
    return (r.toDouble() * 0.2989 + g.toDouble() * 0.5870 + b.toDouble() * 0.1140).roundToInt()
  }
}