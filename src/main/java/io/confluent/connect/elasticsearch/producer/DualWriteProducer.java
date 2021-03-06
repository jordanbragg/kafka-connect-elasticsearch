/**
 * Copyright 2016 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 **/

package io.confluent.connect.elasticsearch.producer;

import io.confluent.connect.avro.AvroData;
import io.confluent.connect.avro.AvroDataConfig;
import io.confluent.kafka.serializers.NonRecordContainer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.connect.connector.ConnectRecord;

import java.util.Deque;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingDeque;

public class DualWriteProducer<T extends ConnectRecord> {
  private Producer<Object, Object> passthroughProducer;
  private String outputTopic;
  private Deque<ConnectRecord> inOrderList = new LinkedBlockingDeque<>();
  private Deque<ProducerRecord<Object, Object>> currentBatchProcessed = new LinkedBlockingDeque<>();
  private final boolean isEnabled;
  private final AvroData avroData;


  public DualWriteProducer(boolean enabled, Properties config, String outputTopic) {
    isEnabled = enabled;
    AvroDataConfig.Builder builder = new AvroDataConfig.Builder()
            .with(AvroDataConfig.CONNECT_META_DATA_CONFIG, false);
    this.avroData = new AvroData(builder.build());

    if (isEnabled) {
      this.passthroughProducer = new KafkaProducer<>(config);
      this.outputTopic = outputTopic;
    }
  }

  public void addRecordForIndex(T record) {
    if (isEnabled) {
      inOrderList.addLast(record);
    }
  }

  public void addFirstForPassthrough() {
    if (isEnabled) {
      ConnectRecord connectRecord = inOrderList.removeFirst();


      Object value =
              avroData.fromConnectData(connectRecord.valueSchema(), connectRecord.value());
      ProducerRecord<Object, Object> producerRecord =
              new ProducerRecord<>(outputTopic,
                      null,
                      connectRecord.timestamp(),
                      connectRecord.key() instanceof NonRecordContainer
                              ? ((NonRecordContainer) connectRecord.key()).getValue()
                              : connectRecord.key(),
                      value);
      currentBatchProcessed.addLast(producerRecord);
    }
  }

  public void submitAll() {
    if (isEnabled) {
      ProducerRecord<Object, Object> currentRecord = null;
      do {
        currentRecord = currentBatchProcessed.pollFirst();
        if (currentRecord != null) {
          passthroughProducer.send(currentRecord);
        }
      }
      while (currentRecord != null);
      passthroughProducer.flush();
    }
  }
}
