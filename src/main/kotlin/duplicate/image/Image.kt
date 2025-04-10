package duplicate.image

import java.awt.image.BufferedImage

data class Image(
  val id: Long,
  val group: String,
  val messageId: String,
  val numberInMessage: Int,
  val additionalInfo: String,
  val fileName: String,
  val image: BufferedImage
)