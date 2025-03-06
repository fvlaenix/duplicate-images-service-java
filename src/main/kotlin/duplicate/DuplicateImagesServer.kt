package duplicate

import io.grpc.Server
import io.grpc.ServerBuilder
import org.jetbrains.exposed.sql.Database

class DuplicateImagesServer(port: Int, database: Database) {
  private val server: Server = ServerBuilder.forPort(port).addService(DuplicateImagesService(database)).build()

  fun start() {
    server.start()
    Runtime.getRuntime().addShutdownHook(
      Thread {
        this@DuplicateImagesServer.stop()
      }
    )
  }

  private fun stop() {
    server.shutdown()
  }

  fun blockUntilShutdown() {
    server.awaitTermination()
  }
}