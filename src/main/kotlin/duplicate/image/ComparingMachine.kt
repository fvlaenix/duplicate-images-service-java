package com.fvlaenix.duplicate.image

import com.fvlaenix.duplicate.database.Connector
import com.fvlaenix.duplicate.database.ImageConnector
import com.fvlaenix.duplicate.protobuf.*
import com.fvlaenix.image.protobuf.Image
import com.luciad.imageio.webp.WebPReadParam
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import net.coobird.thumbnailator.Thumbnails
import org.jetbrains.exposed.sql.Database
import java.awt.image.BufferedImage
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Level
import java.util.logging.Logger
import javax.imageio.IIOException
import javax.imageio.ImageIO
import kotlin.io.path.Path
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.notExists
import kotlin.math.min

private val LOGGER: Logger = Logger.getLogger(ComparingMachine::class.java.simpleName)

private val ENV_PROPERTIES = System.getenv("PATH_TO_COMPARING_PROPERTIES")?.let {
  val path = Path(it)
  if (path.notExists()) {
    throw IllegalStateException("Path should be accessible: $path")
  }
  Properties().apply { load(path.inputStream()) }
}

private val RESERVE_PROPERTIES = Connector::class.java.getResourceAsStream("/comparing.properties")?.let {
  Properties().apply { load(it) }
}

private val PROPERTIES = ENV_PROPERTIES?.apply { LOGGER.info("Using env properties") } ?: 
  RESERVE_PROPERTIES?.apply { LOGGER.info("Using git properties") } ?: 
  throw IllegalStateException("Can't find env path ${System.getenv("PATH_TO_COMPARING_PROPERTIES")} and local path")

private val WIDTH = PROPERTIES.getProperty("width")?.toInt() ?: throw IllegalStateException("width should exists in properties")
private val HEIGHT = PROPERTIES.getProperty("height")?.toInt() ?: throw IllegalStateException("height should exists in properties")

class ComparingMachine(database: Database) {
  
  private val connector = ImageConnector(database)

  companion object {
    // it is const, but you can change it between launches anyway
    val SIZE_MAX_WIDTH: Int? = if (WIDTH <= 0) null else WIDTH
    val SIZE_MAX_HEIGHT: Int? = if (HEIGHT <= 0) null else HEIGHT
    
    private val COMPARING_COUNT = 32

    @OptIn(DelicateCoroutinesApi::class)
    val newThreadContext = newFixedThreadPoolContext(16, "Comparing machine context")

    init {
      if (SIZE_MAX_WIDTH != null && SIZE_MAX_HEIGHT != null) {
        throw IllegalStateException("Can't be both max")
      }
    }
  }

  private fun readImage(image: Image): BufferedImage? {
    val byteString = image.content
    val fileName = image.fileName
    val extension = Path(fileName).extension
    val bufferedImage = try {
      if (extension == "webp") {
        val reader = ImageIO.getImageReadersByMIMEType("image/webp").next()
        val readParam = WebPReadParam()
        readParam.isBypassFiltering = true
        byteString.toByteArray().inputStream().use { inputStream ->
          ImageIO.createImageInputStream(inputStream).use { imageInputStream ->
            reader.input = imageInputStream
            reader.read(0, readParam)
          }
        }
      } else {
        byteString.toByteArray().inputStream().use { stream ->
          ImageIO.read(stream)
        }
      }
    } catch (e: IIOException) {
      LOGGER.log(Level.SEVERE, "Exception while trying read image with name $fileName", e)
      return null
    }
    val maxWidth = SIZE_MAX_WIDTH
    val maxHeight = SIZE_MAX_HEIGHT
    if (maxWidth != null && bufferedImage.width != maxWidth) {
      LOGGER.log(Level.WARNING, "Can't read image with incompatible size")
      return Thumbnails.of(bufferedImage).width(maxWidth).asBufferedImage()
    }
    if (maxHeight != null && bufferedImage.height != maxHeight) {
      LOGGER.log(Level.WARNING, "Can't read image with incompatible size")
      return Thumbnails.of(bufferedImage).height(maxHeight).asBufferedImage()
    }
    return bufferedImage
  }
  
  fun addImageWithCheck(request: AddImageRequest): AddImageResponse {
    val image = readImage(request.image) ?: return addImageResponse { this.error = "Can't read image" }
    val added = connector.addImageWithCheck(
      request.group,
      request.imageId,
      request.additionalInfo,
      image,
      request.image.fileName,
      request.timestamp
    )
    val checkResult = checkImageCandidates(image, added.similarIds)
    return addImageResponse {
      this.responseOk = addImageResponseOk { 
        this.isAdded = added.isAdded
        this.imageInfo = CheckImageResponseImageInfo.newBuilder().addAllImageInfo(checkResult).build()
      }
    }
  }

  fun existsImage(request: ExistsImageRequest): ExistsImageResponse {
    return existsImageResponse { this.isExists = connector.isImageExistsById(request.imageInfo) }
  }

  private fun checkImageCandidates(image: BufferedImage, ids: List<String>): List<String> {
    var it = 0
    val result = ConcurrentLinkedQueue<String>()
    while (it < ids.size) {
      val startIndex = it
      val finishIndex = startIndex + COMPARING_COUNT
      val subList = ids.subList(startIndex, min(finishIndex, ids.size))
      val images = subList.associateWith { connector.getImageById(it) }
      runBlocking {
        images.forEach { (id, candidate) ->
          if (candidate == null) return@forEach
          launch(newThreadContext) {
            val checkResult = ComparingPictures.comparePictures(candidate.image, image)
            if (checkResult != null) {
              result.add(id)
            }
          }
        }
      }
      it += COMPARING_COUNT
    }
    return result.toList()
  }
  
  fun checkImage(request: CheckImageRequest): CheckImageResponse {
    val image = readImage(request.image) ?: return checkImageResponse { this.error = "Can't read image" }
    val ids = connector.getSimilarImages(image, request.group, request.timestamp)
    val result = checkImageCandidates(image, ids)
    return checkImageResponse { this.imageInfo = CheckImageResponseImageInfo.newBuilder().addAllImageInfo(result).build() }
  }

  fun getImageCompressionSize(): GetCompressionSizeResponse {
    return getCompressionSizeResponse {
      this.x = SIZE_MAX_WIDTH ?: -1
      this.y = SIZE_MAX_HEIGHT ?: -1
    }
  }
}