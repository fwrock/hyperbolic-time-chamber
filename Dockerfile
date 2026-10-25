FROM eclipse-temurin:21-jdk

WORKDIR /app

ARG HTC_VERSION=3.2.0
ARG SCALA_VERSION=3.3.5

RUN mkdir -p /app/logs

COPY target/scala-${SCALA_VERSION}/hyperbolic-time-chamber-${HTC_VERSION}.jar app.jar

EXPOSE 1600-2700

#"-Djdk.attach.allowAttachSelf=true" if you want measure memory object storage
CMD ["java", "-XX:+ZGenerational", "-jar", "app.jar"]
