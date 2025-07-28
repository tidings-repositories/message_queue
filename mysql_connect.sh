#!/bin/bash

JSON_PAYLOAD=$(cat <<EOF
{
  "name": "mysql-source-connector",
  "config": {
    "connector.class": "io.debezium.connector.mysql.MySqlConnector",
    "snapshot.mode": "initial",
    "database.hostname": "$MySQL_HOST",
    "database.port": "3306",
    "database.user": "$MySQL_USER",
    "database.password": "$MySQL_PASSWORD",
    "database.server.id": "202507271",
    "topic.prefix": "mysql_cdc",
    "database.include.list": "$MySQL_DATABASE",
    "table.include.list": "$MySQL_DATABASE.member",
    "database.history.kafka.bootstrap.servers": "kafka:9092",
    "database.history.kafka.topic": "schema-changes.mysql",
    "schema.history.internal.kafka.bootstrap.servers": "kafka:9092",
    "schema.history.internal.kafka.topic": "schema-changes.inventory",

    "flush.synchronously": "true",
    "transforms": "route",
    "transforms.route.type": "org.apache.kafka.connect.transforms.RegexRouter",
    "transforms.route.regex": "mysql_cdc\\\\..*",
    "transforms.route.replacement": "member-index"
  }
}
EOF
)

curl -X POST http://localhost:8083/connectors -H "Content-Type: application/json" -d "$JSON_PAYLOAD"