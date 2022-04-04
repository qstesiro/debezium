# 编译并安装
{
    mvn clean package -DskipITs -DskipTests
    mvn package -pl debezium-connector-mysql -am -DskipITs -DskipTests

    mvn compile install -pl debezium-core -pl debezium-connector-mysql -Dquick
    mvn compile install -pl debezium-connector-mysql -Dquick
}

# docker
{
    docker build ./ -f .dbg/Dockerfile -t debezium/connect-modified:1.7
}

# misc
{
    flush tables with read lock;
    unlock tables;

    flush tables customers with read lock;
    unlock tables;
}
