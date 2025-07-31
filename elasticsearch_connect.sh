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
    "key.ignore": "false",
    "schema.ignore": "false",
    "consumer.auto.offset.reset": "earliest",

    "transforms": "unwrap,extractId,extractKeyField,dropId",
    "transforms.unwrap.type": "io.debezium.transforms.ExtractNewRecordState",
    "transforms.unwrap.drop.tombstones": "true",
    "transforms.unwrap.delete.handling.mode": "rewrite",

    "transforms.extractId.type": "org.apache.kafka.connect.transforms.ValueToKey",
    "transforms.extractId.fields": "id",
    "transforms.extractKeyField.type": "org.apache.kafka.connect.transforms.ExtractField\$Key",
    "transforms.extractKeyField.field": "id",
    "transforms.dropId.type": "org.apache.kafka.connect.transforms.ReplaceField\$Value",
    "transforms.dropId.blacklist": "id",
  }
}
EOF
)

curl -X POST http://localhost:8083/connectors -H "Content-Type: application/json" -d "$JSON_PAYLOAD"