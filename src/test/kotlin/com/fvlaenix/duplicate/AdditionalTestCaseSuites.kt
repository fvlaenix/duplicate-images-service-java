package com.fvlaenix.duplicate

import duplicate.database.ImageHashConnector
import duplicate.image.ComparingPictures
import com.fvlaenix.duplicate.protobuf.addImageRequest
import com.google.protobuf.kotlin.toByteString
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.readLines
import kotlin.test.assertEquals

class AdditionalTestCaseSuites {
  private class AdditionalTestCase(val directory: Path, val testPath: Path) {

    val name: String
    val test: List<String>

    init {
      val testFile = testPath.readLines()
      name = testFile.first()
      test = testFile.drop(1)
    }

    fun test() {
      Context.withComparingMachine(
        pictureTolerance = ComparingPictures.TOLERANCE_PER_POINT,
        pixelTolerance = ImageHashConnector.REAL_PIXEL_DISTANCE
      ) { machine ->
        var globalInc = 0L
        test.forEachIndexed { index, line ->
          val (command, arguments) = run {
            val splitted = line.split(" ")
            splitted.first() to splitted.drop(1)
          }
          when (command) {
            "add-with-check" -> {
              val filename = arguments[0]
              val countCheck = arguments[1].toInt()
              val response = machine.addImageWithCheck(addImageRequest {
                this.group = "1"
                this.image = com.fvlaenix.image.protobuf.image {
                  this.fileName = filename
                  this.content = directory.resolve(filename).readBytes().toByteString()
                }
                this.additionalInfo = ""
                this.messageId = index.toString()
                this.timestamp = globalInc
              })
              assertEquals(
                countCheck,
                response.responseOk.imageInfo.imagesCount,
                "While processing command $index: $line"
              )
            }

            "increase-time" -> globalInc++

            else -> throw IllegalStateException("Can't find command $command")
          }
        }
      }
    }

    fun get(): DynamicTest {
      return DynamicTest.dynamicTest(name, ::test)
    }
  }

  @TestFactory
  fun `additional tests`(): List<DynamicTest> {
    val path = Path.of("src").resolve("testData")
    return path.toFile().listFiles()!!
      .filter { it.isDirectory }
      .flatMap { directory ->
        directory.listFiles()!!
          .filter { it.isFile && it.extension == "txt" }
          .map { AdditionalTestCase(directory.toPath(), it.toPath()) }
      }
      .map { it.get() }
  }
}