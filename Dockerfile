FROM eclipse-temurin:21-jdk

WORKDIR /app

RUN mkdir -p /app/logs

COPY target/scala-3.3.5/hyperbolic-time-chamber-1.12.0.jar app.jar

EXPOSE 1600-2700

CMD ["java", "-XX:+ZGenerational",  "-jar", "app.jar"]