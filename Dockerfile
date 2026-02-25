# Stage 1: Build the Java application
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /app
COPY gradlew gradlew.bat settings.gradle build.gradle ./
COPY gradle/ gradle/
COPY src/ src/

RUN chmod +x gradlew && ./gradlew installDist --no-daemon

# Stage 2: Runtime image with Java + Python (for geometry extraction)
FROM eclipse-temurin:21-jre

RUN apt-get update && \
    apt-get install -y --no-install-recommends python3 python3-pip && \
    pip3 install --no-cache-dir --break-system-packages ifcopenshell numpy && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy built distribution from builder stage
COPY --from=builder /app/build/install/ifc-to-citygml3/ ./

# Copy Python geometry extractor into the distribution
COPY src/main/python/extract_geometry.py ./python/

ENTRYPOINT ["./bin/ifc-to-citygml3"]
CMD ["--help"]