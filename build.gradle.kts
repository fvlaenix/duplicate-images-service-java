import com.google.protobuf.gradle.id

plugins {
    kotlin("jvm") version "1.9.22"
    id("com.google.protobuf") version "0.9.4"
}

group = "com.fvlaenix"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.grpc:grpc-kotlin-stub:1.4.0")
    implementation("com.google.protobuf:protobuf-java:3.16.3")
    implementation("com.google.protobuf:protobuf-kotlin:3.24.4")
    runtimeOnly("io.grpc:grpc-netty-shaded:1.59.0")
    implementation("io.grpc:grpc-protobuf:1.59.0")
    implementation("io.grpc:grpc-stub:1.59.0")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")
    protobuf(files("discord-bots-rpc/duplicate-image-request.proto", "discord-bots-rpc/is-alive.proto", "discord-bots-rpc/image.proto"))

    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("org.slf4j:slf4j-simple:2.0.9")

    implementation("org.jsoup:jsoup:1.15.3")
    implementation("mysql:mysql-connector-java:8.0.30")
    implementation("com.h2database:h2:1.4.199")
    implementation("org.jetbrains.exposed:exposed:0.17.14")

    implementation("com.twelvemonkeys.imageio:imageio:3.9.3")
    implementation("com.twelvemonkeys.imageio:imageio-pict:3.9.3")
    implementation("com.twelvemonkeys.imageio:imageio-core:3.9.3")
    implementation("com.twelvemonkeys.imageio:imageio-iff:3.9.3")
    implementation("com.twelvemonkeys.imageio:imageio-jpeg-jep262-interop:3.9.3")
    implementation("com.twelvemonkeys.imageio:imageio-jpeg-jai-interop:3.9.3")
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.9.3")
    implementation("com.twelvemonkeys.imageio:imageio-metadata:3.9.3")
    implementation("com.twelvemonkeys.imageio:imageio-bmp:3.9.3")
    implementation("com.twelvemonkeys.imageio:imageio-tiff-jdk-interop:3.9.3")
    implementation("org.sejda.imageio:webp-imageio:0.1.6")
    implementation("net.coobird:thumbnailator:0.4.18")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

task<JavaExec>("runServer") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.fvlaenix.RunServerKt")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(11)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.24.4"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.59.0"
        }
        create("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.0:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                id("grpc")
                id("grpckt")
            }
            it.builtins {
                create("kotlin")
            }
        }
    }
}
