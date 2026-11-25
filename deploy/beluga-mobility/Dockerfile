FROM eclipse-temurin:25-jre
ARG HTC_COMMIT
LABEL org.opencontainers.image.revision=$HTC_COMMIT
WORKDIR /app
COPY engine.jar /app/engine.jar
COPY launch.sh /app/launch.sh
ENTRYPOINT ["sh", "/app/launch.sh"]
