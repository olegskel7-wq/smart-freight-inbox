FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY target/inbox-*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
