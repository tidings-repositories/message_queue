#!/bin/bash

JSON_PAYLOAD=$(cat <<EOF
{
  "name": "mongodb-source-connector",
  "config": {
    "connector.class": "io.debezium.connector.mongodb.MongoDbConnector",
    "snapshot.mode": "initial",
    "mongodb.connection.string": "mongodb://$MongoDB_USER:$MongoDB_PASSWORD@$MongoDB_URL/?authSource=$MongoDB_DATABASE&replicaSet=rs0",
    "mongodb.name": "mongodb_cdc",
    "topic.prefix": "mongodb_cdc",
    "database.include.list": "$MongoDB_DATABASE",
    "collection.include.list": "$MongoDB_DATABASE.posts",
    "key.converter.schemas.enable": false,
    "value.converter.schemas.enable": false,

    "flush.synchronously": "true",
    "transforms": "JsonToStruct,route",
    "transforms.JsonToStruct.type": "com.delivalue.tidings.JsonStringToStruct",
    "transforms.route.type": "org.apache.kafka.connect.transforms.RegexRouter",
    "transforms.route.regex": "mongodb_cdc\\\\..*",
    "transforms.route.replacement": "post-index"
  }
}
EOF
)

curl -X POST http://localhost:8083/connectors -H "Content-Type: application/json" -d "$JSON_PAYLOAD"