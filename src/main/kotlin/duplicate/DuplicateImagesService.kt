package com.fvlaenix.duplicate

import com.fvlaenix.alive.protobuf.IsAliveRequest
import com.fvlaenix.alive.protobuf.IsAliveResponse
import com.fvlaenix.alive.protobuf.isAliveResponse
import com.fvlaenix.duplicate.image.ComparingMachine
import com.fvlaenix.duplicate.protobuf.*
import org.jetbrains.exposed.sql.Database

class DuplicateImagesService(database: Database) : DuplicateImagesServiceGrpcKt.DuplicateImagesServiceCoroutineImplBase() {

  private val comparingMachine = ComparingMachine(database)

  override suspend fun addImage(request: AddImageRequest): AddImageResponse =
    comparingMachine.addImage(request)

  override suspend fun checkImage(request: CheckImageRequest): CheckImageResponse =
    comparingMachine.checkImage(request)

  override suspend fun getImageCompressionSize(request: GetCompressionSizeRequest): GetCompressionSizeResponse =
    comparingMachine.getImageCompressionSize()

  override suspend fun isAlive(request: IsAliveRequest): IsAliveResponse {
    return isAliveResponse {  }
  }
}