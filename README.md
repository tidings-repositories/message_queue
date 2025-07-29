# Kafka로 CDC 파이프라인 구성하기

## 서론

현재 유저 정보와 포스트 정보들을 각각 MySQL 및 MongoDB에 보관하고 있기 때문에, Dual Write 방식으로 Elasticsearch에 데이터를 보내는 것도 가능하지만,

나름의 확장성을 고려하고, 또한 나중엔 로그 수집도 진행해야 하기 때문에 Kafka + Kafka Connect (Debezium)로 CDC 파이프라인을 구축하려고 합니다.

## Docker 구성

Kafka에도 Docker를 설치하는 이유는 Kafka Connect와 서버 리소스 경쟁을 하지 않도록 격리하기 위함입니다.

### 디스크 스왑 공간 할당

Docker에서 공식 문서에 따르면 최소 4GB의 RAM을 사용하길 권장하는데, Elasticsearch 때와 마찬가지로 현재 그럴 수 있는 상황이 아니라서 가상 메모리 스왑 공간을 4GB로 설정해줬습니다.

```bash
sudo dd if=/dev/zero of=/swapfile bs=128M count=32

sudo chmod 600 /swapfile

sudo mkswap /swapfile

sudo swapon /swapfile

echo /swapfile swap swap defaults 0 0 | sudo tee -a /etc/fstab

echo 'vm.swappiness=10' | sudo tee -a /etc/sysctl.conf
```

### Docker 설치

이후 Docker를 설치해줬고,

```bash
sudo yum install -y docker
```

실행과 함께 시스템 리부팅에도 자동 실행될 수 있도록 설정했습니다.

```bash
sudo systemctl start docker

sudo systemctl enable docker
```

그리고 현재 사용하는 Linux 유저를 docker 그룹에 추가하여 루트 권한(sudo)을 이용하지 않아도 docker 명령을 실행할 수 있도록 설정해줬습니다.

```bash
sudo usermod -aG docker ec2-user

newgrp docker
```

### Docker Compose Plugin 설치

Docker를 사용하게된 만큼 Compose를 통해 쉽게 Kafka + Kafka Connect를 구성하고 이식하기 위하여 Compose Plugin을 설치해줬습니다.

```bash
sudo mkdir -p /usr/local/lib/docker/cli-plugins/

sudo curl -SL https://github.com/docker/compose/releases/download/v2.39.1/docker-compose-linux-x86_64 -o /usr/local/lib/docker/cli-plugins/docker-compose

sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
```

## Docker Compose로 Kafka, Kafka connect 구성하기

Docker hub에서 [kafka image](https://hub.docker.com/r/bitnami/kafka)와 Kafka Connect를 포함하는 [Debezium image](https://hub.docker.com/r/debezium/connect)를 활용해 구성했습니다.

```yaml
services:
  kafka:
    image: bitnami/kafka:4.0.0
    networks:
      - default
    ports:
      - "9092:9092"
    environment:
      # KRaft
      - KAFKA_CFG_NODE_ID=0
      - KAFKA_CFG_PROCESS_ROLES=controller,broker
      - KAFKA_CFG_CONTROLLER_QUORUM_VOTERS=0@kafka:9093
      # Listenser
      - KAFKA_CFG_CONTROLLER_LISTENER_NAMES=CONTROLLER
      - KAFKA_CFG_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093
      - KAFKA_CFG_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      - KAFKA_CFG_ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092
      - KAFKA_KRAFT_CLUSTER_ID=424b415e-d4e0-480c-ada1-5836e3d891f6
      - ALLOW_PLAINTEXT_LISTENER=yes
    volumes:
      - kafka-data:/bitnami/kafka

  connect:
    image: debezium/connect:2.7.3.Final
    networks:
      - default
    depends_on:
      - kafka
    ports:
      - "8083:8083"
    environment:
      - BOOTSTRAP_SERVERS=kafka:9092
      - GROUP_ID=connect-cluster
      - CONFIG_STORAGE_TOPIC=my_connect_configs
      - OFFSET_STORAGE_TOPIC=my_connect_offsets
      - STATUS_STORAGE_TOPIC=my_connect_statuses
      - KEY_CONVERTER=org.apache.kafka.connect.json.JsonConverter
      - VALUE_CONVERTER=org.apache.kafka.connect.json.JsonConverter
      - INTERNAL_KEY_CONVERTER=org.apache.kafka.connect.json.JsonConverter
      - INTERNAL_VALUE_CONVERTER=org.apache.kafka.connect.json.JsonConverter
      - REST_ADVERTISED_HOST_NAME=connect
      - REST_PORT=8083
    volumes:
      - ./debezium-plugins:/kafka/connect

networks:
  default:
    driver: bridge

volumes:
  kafka-data:
```

이후 실행할 때 프로젝트 이름을 환경 변수로 지정하고, 데몬으로 실행해줬습니다.

```
# .env

COMPOSE_PROJECT_NAME=tidings_messagequeue
```

## Connector plugin 설치

설치해야 하는 Plugin은 총 3종류로 [debezium-connector-mysql](https://central.sonatype.com/artifact/io.debezium/debezium-connector-mysql), [ debezium-connector-mongodb](https://central.sonatype.com/artifact/io.debezium/debezium-connector-mongodb), [Elasticsearch-sink-connector](https://www.confluent.io/hub/confluentinc/kafka-connect-elasticsearch)

Kafka connect를 compose에서 정의할 때 저장 주소로 Host OS의 `debezium-plugins/`를 지정했기 때문에 해당 주소로 이동하여 다운받아줬습니다.

```bash
cd debezium-plugins/

# mysql connector
sudo wget https://repo1.maven.org/maven2/io/debezium/debezium-connector-mysql/3.2.0.Final/debezium-connector-mysql-3.2.0.Final-plugin.tar.gz

# mongodb connector
sudo wget https://repo1.maven.org/maven2/io/debezium/debezium-connector-mongodb/3.2.0.Final/debezium-connector-mongodb-3.2.0.Final-plugin.tar.gz

# elasticsearch connector
sudo wget https://hub-downloads.confluent.io/api/plugins/confluentinc/kafka-connect-elasticsearch/versions/15.0.1/confluentinc-kafka-connect-elasticsearch-15.0.1.zip
```

이후 받은 tar.gz 파일과, zip 파일들을 압축 해제 해줬습니다.

```bash
sudo tar -xvzf debezium-connector-mysql-3.2.0.Final-plugin.tar.gz

sudo tar -xvzf debezium-connector-mongodb-3.2.0.Final-plugin.tar.gz

sudo unzip confluentinc-kafka-connect-elasticsearch-15.0.1.zip
```

이제 컨테이너를 재실행하고, plugin이 적용되었는지 확인하면 잘 적용되어있음을 확인할 수 있었습니다.

```bash
docker restart {connect 컨테이너 ID}

docker exec -it {connect 컨테이너 ID} curl -s http://localhost:8083/connector-plugins | jq
```

## Connector를 이용한 CDC 파이프라인 구축

목적은 아래와 같습니다.

1. MySQL과 MongoDB에서 데이터 변화를 로그 기반으로 감지해 Kafka로 전달
2. 메시지를 통해 Elasticsearch와 연결하여 데이터 복제

우선 Connector 등록에 사용될 환경 변수 (DB User & PWD, HOST 등..)를 등록해줬고, 이후 아래 순서대로 스크립트를 생성해 DB Connector를 등록해줬습니다.

굳이 스크립트를 사용한 이유는 정적 JSON 파일에서 환경 변수에 접근할 수 없기 때문에 스크립트로 REST API를 호출하는 방식으로 구성했습니다.

### MySQL Connector 등록

MySQL Connector에서 중요한 점은 사용할 계정에서 Global grant를 가지고 있어야 한다는 점이었습니다.

```
GRANT RELOAD, FLUSH_TABLES, REPLICATION SLAVE, REPLICATION CLIENT
```

따라서 위와 같은 Grant를 부여해야 했습니다.

```bash
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
```

### MongoDB Connector 등록

MongoDB에서 중요한 점은 Connector가 MongoDB Replica Set의 oplog를 기반으로 데이터를 복제하기 때문에

MongoDB가 Replica Set으로 구성되어 있어야 한다는 점이었습니다.

현재는 MongoDB가 단일 노드 실행 상태 (StandAlone)라서, Replica Set을 구성하는 작업을 먼저 해줘야 했습니다.

```config
sudo vi /etc/mongod.conf

# mongod.conf

replication:
  replSetName: rs0
```

mongod.conf에서 replication 설정을 해줬고, Replica Set을 초기화해줬습니다.

```js
mongosh;

rs.initiate({
  _id: "rs0",
  members: [{ _id: 0, host: "{host Ip}:27017" }],
});
```

이 과정에서 헤맨 것이 있는데 aws에서만 그런진 모르겠지만, MongoDB가 자신의 public Id를 스스로라고 인식하지 못해 host ip 부분에 public ip를 넣었을 경우 에러가 발생하던 문제가 있었습니다.

이 문제는 aws의 보안 그룹에서 MongoDB 인스턴스의 IP로 27017 포트에 연결할 수 있도록 인바운드 설정을 수행했을 때 해결되었습니다.

```bash
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
    "transforms.route.regex": "mongodb_cdc\\..*",
    "transforms.route.replacement": "post-index"
  }
}
EOF
)

curl -X POST http://localhost:8083/connectors -H "Content-Type: application/json" -d "$JSON_PAYLOAD"
```

MongoDB가 정말 CDC 파이프라인을 구성하며 시행 착오가 많았던 것 같습니다.

1. JSON String을 Struct로 교체

MongoDB가 내부적으로 BSON으로 자료를 저장하기 때문에 key, value converter가 JsonConverter를 사용하더라도 값을 Json String으로 직렬화 하여 보냈는데,

처음에는 이 사실을 몰랐어서 단순히 Elasticsearch에 저장했다가 검색할 수 없는 문제가 발생했었습니다.

2. Elasticsearch의 Document 정책 `_id` 금지

또한 MongoDB도 내부적으로 Id를 `_id` 필드로 사용하는데, JSON String을 Struct로 변환하여 전송하니 `_id` 필드를 사용할 수 없어 Elasticsearch 커넥터가 다운되는 문제를 경험하게 되었습니다.

이 문제를 해결하기 위해 MongoDB Connector에서 Custom SMT (Single Message Transform)을 만들어 커넥터가 Kafka 토픽에 메시지를 삽입하기 전 데이터 변조를 수행했습니다.

```java
package com.delivalue.tidings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.transforms.Transformation;
import org.apache.kafka.connect.connector.ConnectRecord;
import org.apache.kafka.connect.errors.DataException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class JsonStringToStruct<R extends ConnectRecord<R>> implements Transformation<R> {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public R apply(R record) {
        Object value = record.value();

        if (!(value instanceof Struct message)) {
            return record;
        }

        Object afterValue = message.get("after");

        // after 필드가 String 타입 JSON인 경우만 변환 수행
        if (!(afterValue instanceof String)) {
            return record;
        }

        try {
            JsonNode jsonNode = mapper.readTree((String) afterValue);
            if (jsonNode == null || !jsonNode.isObject()) {
                throw new DataException("Invalid JSON content: not an object");
            }

            ObjectNode node = mapper.createObjectNode();
            jsonNode.fields().forEachRemaining(entry -> {
                String k = entry.getKey();
                JsonNode v = entry.getValue();

                if("_id".equals(k)) {
                    node.set("id", v);
                } else {
                    node.set(k, v);
                }
            });

            // JSON을 기반 동적 스키마 생성
            Schema afterSchema = buildSchema(node);
            Struct afterStruct = buildStruct(node, afterSchema);

            Schema newSchema = createUpdatedSchema(record.valueSchema(), "after", afterSchema);
            Struct newStruct = new Struct(newSchema);
            for (Field field : newSchema.fields()) {
                if ("after".equals(field.name())) {
                    newStruct.put(field, afterStruct); // after 필드에 Struct 넣기
                } else {
                    newStruct.put(field, message.get(field));
                }
            }

            return record.newRecord(
                    record.topic(),
                    record.kafkaPartition(),
                    record.keySchema(),
                    record.key(),
                    newSchema,
                    newStruct,
                    record.timestamp()
            );
        } catch (Exception e) {
            throw new DataException("Failed to transform JSON string into Struct", e);
        }
    }

    public Schema createUpdatedSchema(Schema originalSchema, String targetField, Schema newFieldSchema) {
        SchemaBuilder builder = SchemaBuilder.struct().name(originalSchema.name()).optional();

        for (Field field : originalSchema.fields()) {
            if (field.name().equals(targetField)) {
                builder.field(field.name(), newFieldSchema);
            } else {
                builder.field(field.name(), field.schema());
            }
        }

        return builder.build();
    }

    private Schema buildSchema(JsonNode jsonNode) {
        SchemaBuilder builder = SchemaBuilder.struct().optional();

        Iterator<Map.Entry<String, JsonNode>> fields = jsonNode.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            String fieldName = field.getKey();
            JsonNode valueNode = field.getValue();

            Schema fieldSchema = inferSchema(valueNode);
            builder.field(fieldName, fieldSchema);
        }

        return builder.build();
    }

    private Schema inferSchema(JsonNode node) {
        if (node.isTextual()) {
            return Schema.OPTIONAL_STRING_SCHEMA;
        } else if (node.isInt()) {
            return Schema.OPTIONAL_INT32_SCHEMA;
        } else if (node.isLong()) {
            return Schema.OPTIONAL_INT64_SCHEMA;
        } else if (node.isBoolean()) {
            return Schema.OPTIONAL_BOOLEAN_SCHEMA;
        } else if (node.isDouble() || node.isFloat()) {
            return Schema.OPTIONAL_FLOAT64_SCHEMA;
        } else if (node.isObject()) {
            return buildSchema(node); // 재귀적으로 처리
        } else if (node.isArray()) {
            if (node.isEmpty() || node.get(0).isNull()) {
                return SchemaBuilder.array(Schema.OPTIONAL_STRING_SCHEMA).optional().build();
            }
            JsonNode firstElement = node.get(0);
            Schema elementSchema = inferSchema(firstElement);
            return SchemaBuilder.array(elementSchema).optional().build();
        } else if (node.isNull()) {
            return Schema.OPTIONAL_STRING_SCHEMA; // 기본 타입
        } else {
            return Schema.OPTIONAL_STRING_SCHEMA;
        }
    }

    private Struct buildStruct(JsonNode jsonNode, Schema schema) {
        Struct struct = new Struct(schema);
        for (Field field : schema.fields()) {
            JsonNode valueNode = jsonNode.get(field.name());
            if (valueNode == null || valueNode.isNull()) {
                struct.put(field.name(), null);
            } else {
                struct.put(field.name(), extractValue(valueNode, field.schema()));
            }
        }
        return struct;
    }

    private Object extractValue(JsonNode node, Schema schema) {
        switch (schema.type()) {
            case STRING:
                return node.asText();
            case INT32:
                return node.asInt();
            case INT64:
                return node.asLong();
            case BOOLEAN:
                return node.asBoolean();
            case FLOAT64:
                return node.asDouble();
            case STRUCT:
                return buildStruct(node, schema);
            case ARRAY:
                if (!node.isArray()) return null;
                List<Object> values = new ArrayList<>();
                Schema itemSchema = schema.valueSchema();  // 요소 스키마 사용

                for (JsonNode element : node) {
                    values.add(extractValue(element, itemSchema));
                }
                return values;
            default:
                return null;
        }
    }

    @Override
    public ConfigDef config() {
        return new ConfigDef();
    }

    @Override
    public void configure(Map<String, ?> configs) {}

    @Override
    public void close() {}
}

```

Custom SMT를 만들기 위해서 Confluent에서 제공하는 [docs](https://docs.confluent.io/platform/current/connect/transforms/custom.html) 및  [Apache Kafka GitHub project](https://github.com/apache/kafka/tree/trunk/connect/transforms/src/test/java/org/apache/kafka/connect/transforms/)를 참고해 구성했고,

그나마 중요하게 생각해야 할 점으로

- Transformation을 구현해야 한다는 점과
- Kafka Connect가 플러그인을 인식할 수 있도록 `META-INF/services` 경로로 `org.apache.kafka.connect.transforms.Transformation`을 만들어 내부에 Custom SMT 패키지 명을 포함시켜야 한다는 점이었습니다.

만든 SMT java 파일은 gradle을 통해 .jar로 변환해줬고, Kafka connect의 plugin 위치에 디렉토리로 포함시켜줬습니다.

```bash
./gradlew clean build

scp ./build/libs/kafka-0.0.1-SMT.jar {유저}@{Kafka 서버}:~

# Kafka 서버
sudo mkdir /debezium-plugins/jsonstring-to-struct-smt
sudo mv kafka-0.0.1-SMT.jar /debezium-plugins/jsonstring-to-struct-smt

docker restart {kafka connect 컨테이너}
```

### Elasticsearch Connector 등록

우선은 따로 Kafka Connector가 Elasticsearch에서 가지는 역할과 책임을 구분할 수 있도록 접근 권한과 함께 유저를 생성해줬습니다.

```bash
# Elasticsearch role 생성
curl -u elastic:{비밀번호} -X POST "localhost:9200/_security/role/es_sink_connector_role?pretty" -H 'Content-Type: application/json' -d'
{
  "cluster": ["monitor"],
  "indices": [
    {
      "names": [ "*" ],
      "privileges": ["create_index", "read", "write", "view_index_metadata"]
    }
  ]
}'

# Elasticsearch Kafka connect 접속용 유저 생성
curl -u elastic:{비밀번호} -X POST "localhost:9200/_security/user/es_sink_connector_user?pretty" -H 'Content-Type: application/json' -d'
{
  "password" : "{비밀번호}",
  "roles" : [ "es_sink_connector_role" ]
}'
```

그리고 해당 유저와 비밀번호를 이용해 Elasticsaerch sink connector 설정도 함께 해줬습니다.

```sh
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
```

여기서 좀 막혔던 부분으로 기존 es-sink-connector에서 `topic.index.map`을 통해 토픽과 인덱스를 매핑할 수 있었는데, 이게 최신 버전에선 deprecated 되어서

flush.synchronously를 true로 변경하고, SMT 방식으로 인덱스 명을 매핑하라는 공식 문서를 참고할 수 있었습니다.

### CDC 파이프라인이 잘 구성되어 있는지 확인

첫번째로 커넥터 구성이 잘 되어있는지 확인하기 위해 커넥터가 Running 상태인지 전부 확인해줬습니다.

```bash
curl -X GET http://localhost:8083/connectors/{커넥터 이름}/status
```

그리고, 두 번째로 Kafka가 설치된 컨테이너에 접속해 큐가 정상적으로 생성되었는지 확인했습니다.

```bash
docker exec -it {컨테이너 ID} /bin/bash

kafka-topics.sh --bootstrap-server localhost:9092 --list
```

세 번째로 확인한 건 MySQL과 MongoDB 커넥터를 연결할 때 초기 데이터를 할당할 수 있도록 snapshot.mode를 initial로 설정했기 때문에

아래 명령어로 초기 데이터가 들어갔다가 소비되었는지 확인했습니다.

```bash
kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic {토픽 이름} \
  --from-beginning
```

이제 마지막으로 Elasticsearch에 데이터가 잘 저장되었는지 확인했습니다.

```bash
curl -u {유저}:{비밀번호} -X GET localhost:9200/_cat/indices?v
```

```bash
# _cat/indices?v 결과:
health status index            uuid                   pri rep docs.count docs.deleted store.size pri.store.size
yellow open   member-index     oBkv9FNuS4-ezKOTOQGv5Q   1   1        365            0     64.1kb         64.1kb
yellow open   post-index       oy2h2DWLQe6aM_5Gn0opzg   1   1        751            0    236.1kb        236.1kb
```
