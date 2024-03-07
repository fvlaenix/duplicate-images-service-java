FROM gradle:8.4.0-jdk17-focal

COPY . working
WORKDIR working

RUN rm gradlew gradlew.bat
RUN gradle build check --console=plain --no-daemon

CMD gradle runServer --console=plain --no-daemon