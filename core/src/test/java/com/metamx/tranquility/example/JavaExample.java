/*
 * Licensed to Metamarkets Group Inc. (Metamarkets) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Metamarkets licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.metamx.tranquility.example;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.metamx.common.Granularity;
import com.metamx.common.logger.Logger;
import com.metamx.tranquility.beam.ClusteredBeamTuning;
import com.metamx.tranquility.druid.DruidBeams;
import com.metamx.tranquility.druid.DruidDimensions;
import com.metamx.tranquility.druid.DruidLocation;
import com.metamx.tranquility.druid.DruidRollup;
import com.metamx.tranquility.tranquilizer.Tranquilizer;
import com.metamx.tranquility.typeclass.Timestamper;
import com.twitter.util.Await;
import com.twitter.util.Future;
import io.druid.data.input.impl.TimestampSpec;
import io.druid.granularity.QueryGranularity;
import io.druid.query.aggregation.AggregatorFactory;
import io.druid.query.aggregation.CountAggregatorFactory;
import io.druid.query.aggregation.LongSumAggregatorFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.joda.time.DateTime;
import org.joda.time.Period;
import scala.runtime.BoxedUnit;

import java.util.List;
import java.util.Map;

public class JavaExample
{
  private static final Logger log = new Logger(JavaExample.class);

  public static void main(String[] args)
  {
    final String indexService = "druid/overlord"; // Your overlord's druid.service
    final String discoveryPath = "/druid/discovery"; // Your overlord's druid.discovery.curator.path
    final String dataSource = "foo";
    final List<String> dimensions = ImmutableList.of("bar", "qux");
    final List<AggregatorFactory> aggregators = ImmutableList.of(
        new CountAggregatorFactory("cnt"),
        new LongSumAggregatorFactory("baz", "baz")
    );

    // Tranquility needs to be able to extract timestamps from your object type (in this case, Map<String, Object>).
    final Timestamper<Map<String, Object>> timestamper = new Timestamper<Map<String, Object>>()
    {
      @Override
      public DateTime timestamp(Map<String, Object> theMap)
      {
        return new DateTime(theMap.get("timestamp"));
      }
    };

    // Tranquility uses ZooKeeper (through Curator) for coordination.
    final CuratorFramework curator = CuratorFrameworkFactory
        .builder()
        .connectString("zk.example.com:2181")
        .retryPolicy(new ExponentialBackoffRetry(1000, 20, 30000))
        .build();
    curator.start();

    // The JSON serialization of your object must have a timestamp field in a format that Druid understands. By default,
    // Druid expects the field to be called "timestamp" and to be an ISO8601 timestamp.
    final TimestampSpec timestampSpec = new TimestampSpec("timestamp", "auto", null);

    // Tranquility needs to be able to serialize your object type to JSON for transmission to Druid. By default this is
    // done with Jackson. If you want to provide an alternate serializer, you can provide your own via ```.objectWriter(...)```.
    // In this case, we won't provide one, so we're just using Jackson.
    final Tranquilizer<Map<String, Object>> druidService = DruidBeams
        .builder(timestamper)
        .curator(curator)
        .discoveryPath(discoveryPath)
        .location(DruidLocation.create(indexService, dataSource))
        .timestampSpec(timestampSpec)
        .rollup(DruidRollup.create(DruidDimensions.specific(dimensions), aggregators, QueryGranularity.MINUTE))
        .tuning(
            ClusteredBeamTuning
                .builder()
                .segmentGranularity(Granularity.HOUR)
                .windowPeriod(new Period("PT10M"))
                .partitions(1)
                .replicants(1)
                .build()
        )
        .buildTranquilizer();

    druidService.start();

    try {
      // Build a sample event to send; make sure we use a current date
      Map<String, Object> obj = ImmutableMap.<String, Object>of(
          "timestamp", new DateTime().toString(),
          "bar", "barVal",
          "baz", 3
      );

      // Send event to Druid:
      final Future<BoxedUnit> future = druidService.send(obj);

      // Wait for confirmation:
      Await.result(future);
    }
    catch (Exception e) {
      log.warn(e, "Failed to send message");
    }
    finally {
      // Close objects:
      druidService.stop();
      curator.close();
    }
  }
}
