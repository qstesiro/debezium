mvn clean package -DskipITs -DskipTests
mvn package -pl debezium-connector-mysql -am -DskipITs -DskipTests

docker build ./ -f .dbg/Dockerfile -t debezium/connect-modified:1.7


