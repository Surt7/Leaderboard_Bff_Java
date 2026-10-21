# Stage 1: Build with Maven (Java 21)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Copy Maven wrapper and pom.xml
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copy full source and build executable JAR
COPY src ./src
RUN ./mvnw package -DskipTests -B

# Stage 2: Minimal hardened runtime image (JRE 21 Alpine)
FROM eclipse-temurin:21-jre-alpine AS final
WORKDIR /app

# Run as non-root user
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

COPY --from=build /workspace/target/*.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
