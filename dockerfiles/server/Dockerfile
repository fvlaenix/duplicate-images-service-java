FROM gradle:8.4.0-jdk17-focal as builder

COPY . /working
WORKDIR /working

RUN rm gradlew gradlew.bat
RUN gradle check jar --console=plain --no-daemon

FROM openjdk:latest
COPY --from=builder /working/build/libs/runServer.jar runServer.jar

CMD java -jar runServer.jar