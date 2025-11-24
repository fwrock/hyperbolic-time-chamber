FROM eclipse-temurin:21-jdk

WORKDIR /app

RUN mkdir -p /app/logs

COPY target/scala-3.3.5/hyperbolic-time-chamber-1.8.9.jar app.jar

# Expor porta da aplicação
EXPOSE 1600-2700

# Rodar aplicação com entrypoint que limpa snapshots
# Configurações JVM otimizadas para máquina com 1TB RAM
# Heap: 64GB inicial, 128GB máximo (deixando espaço para off-heap e sistema)
# G1GC otimizado para throughput e baixa latência
CMD ["java", \
    "-Xms64g", \
    "-Xmx128g", \
    "-XX:+UseG1GC", \
    "-XX:MaxGCPauseMillis=200", \
    "-XX:ParallelGCThreads=56", \
    "-XX:ConcGCThreads=14", \
    "-XX:G1HeapRegionSize=32m", \
    "-XX:InitiatingHeapOccupancyPercent=45", \
    "-XX:+UseStringDeduplication", \
    "-XX:+ParallelRefProcEnabled", \
    "-XX:+AlwaysPreTouch", \
    "-Dpekko.coordinated-shutdown.exit-jvm=on", \
    "-jar", "app.jar"]
