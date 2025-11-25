FROM eclipse-temurin:21-jdk

WORKDIR /app

RUN mkdir -p /app/logs

COPY target/scala-3.3.5/hyperbolic-time-chamber-1.9.0.jar app.jar

# Expor porta da aplicação
EXPOSE 1600-2700

# Rodar aplicação
CMD ["java", "-jar", "app.jar"]
