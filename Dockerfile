FROM eclipse-temurin:25-jdk
# JEP 519 (product flag in JDK 25): 8-byte object headers, ~25% less heap for small objects
ENV JDK_JAVA_OPTIONS="-XX:+UseCompactObjectHeaders"

WORKDIR /app

ARG HTC_VERSION=3.2.0
ARG SCALA_VERSION=3.3.5

RUN mkdir -p /app/logs

COPY target/scala-${SCALA_VERSION}/hyperbolic-time-chamber-${HTC_VERSION}.jar app.jar

EXPOSE 1600-2700

#"-Djdk.attach.allowAttachSelf=true" if you want measure memory object storage
CMD ["java", "-XX:+UseZGC", "-jar", "app.jar"]
