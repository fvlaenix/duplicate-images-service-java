import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.google.protobuf.gradle.id

plugins {
    kotlin("jvm") version "1.9.22"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("com.google.protobuf") version "0.9.4"
    kotlin("plugin.serialization") version "2.0.0"
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

    implementation("com.twelvemonkeys.imageio:imageio:3.10.1")
    implementation("com.twelvemonkeys.imageio:imageio-bmp:3.10.1")
    implementation("com.twelvemonkeys.imageio:imageio-hdr:3.10.1")
    implementation("com.twelvemonkeys.imageio:imageio-icns:3.10.1")
    implementation("com.twelvemonkeys.imageio:imageio-iff:3.10.1")
    implementation("com.twelvemonkeys.imageio:imageio-jpeg:3.10.1")
    implementation("com.twelvemonkeys.imageio:imageio-jpeg-jep262-interop:3.10.1")
    implementation("com.twelvemonkeys.imageio:imageio-jpeg-jai-interop:3.10.1")
    implementation("com.twelvemonkeys.imageio:imageio-pcx:3.10.1")
    implementation("com.twelvemonkeys.imageio:imageio-pcx:3.10.1")
    implementation("com.twelvemonkeys.imageio:imageio-pict:3.10.1")
    implementation("com.twelvemonkeys.imageio:imageio-pnm:3.10.1")
    implementation("com.twelvemonkeys.imageio:imageio-psd:3.10.1")
    implementation("com.twelvemonkeys.imageio:imageio-sgi:3.10.1")
    implementation("com.twelvemonkeys.imageio:imageio-tga:3.10.1")
    implementation("com.twelvemonkeys.imageio:imageio-thumbsdb:3.10.1")
    implementation("com.twelvemonkeys.imageio:imageio-tiff:3.10.1")
    implementation("com.twelvemonkeys.imageio:imageio-tiff-jdk-interop:3.10.1")
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.10.1")
    implementation("com.twelvemonkeys.imageio:imageio-xwd:3.10.1")
    implementation("com.twelvemonkeys.imageio:imageio-core:3.10.1")
    implementation("com.twelvemonkeys.imageio:imageio-metadata:3.10.1")
    implementation("net.coobird:thumbnailator:0.4.18")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

kotlin {
    jvmToolchain(11)
}

task<JavaExec>("runServer") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.fvlaenix.RunServerKt")
}

fun createJarTaskByJavaExec(name: String) = tasks.create<ShadowJar>("${name}Jar") {
    mergeServiceFiles()
    group = "shadow"
    description = "Run server $name"

    from(sourceSets.main.get().output)
    from(project.configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.RSA", "META-INF/*.SF", "META-INF/*.DSA")
    configurations = listOf(project.configurations.runtimeClasspath.get())

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveFileName.set("${name}.jar")
    manifest {
        attributes["Main-Class"] = (tasks.findByName(name) as JavaExec).mainClass.get()
    }
}.apply task@ { tasks.named("jar") { dependsOn(this@task) } }

createJarTaskByJavaExec("runServer")

tasks.test {
    useJUnitPlatform()
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
