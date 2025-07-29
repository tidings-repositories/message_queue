#!/bin/bash

JSON_PAYLOAD=$(cat <<EOF
{
  "name": "es-sink-connector",
  "config": {
    "connector.class": "io.confluent.connect.elasticsearch.ElasticsearchSinkConnector",
    "topics": "member-index, post-index",
    "connection.url": "http://$Elasticsearch_URL",
    "connection.username": "$Elasticsearch_USER",
    "connection.password": "$Elasticsearch_PASSWORD",
    "key.ignore": "true",
    "schema.ignore": "false",
    "consumer.auto.offset.reset": "earliest",

    "transforms": "unwrap",
    "transforms.unwrap.type": "io.debezium.transforms.ExtractNewRecordState",
    "transforms.unwrap.drop.tombstones": "true",
    "transforms.unwrap.delete.handling.mode": "rewrite",
  }
}
EOF
)

curl -X POST http://localhost:8083/connectors -H "Content-Type: application/json" -d "$JSON_PAYLOAD"