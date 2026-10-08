FROM eclipse-temurin:17-jre
WORKDIR /app
COPY poolpilot-demo/target/poolpilot-demo-0.1.0.jar app.jar
ENV SERVER_ADDRESS=0.0.0.0
RUN mkdir -p /app/data && chown 10001:10001 /app/data
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
