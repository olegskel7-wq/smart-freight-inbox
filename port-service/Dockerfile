FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY target/port-service-*.jar app.jar

EXPOSE 9000

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
