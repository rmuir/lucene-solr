/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.document;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.RandomIndexWriter;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.spatial.util.GeoRect;
import org.apache.lucene.spatial.util.GeoUtils;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.SloppyMath;

/** Simple tests for {@link LatLonPoint#newDistanceQuery} */
public class TestLatLonPointDistanceQuery extends LuceneTestCase {

  /** test we can search for a point */
  public void testBasics() throws Exception {
    Directory dir = newDirectory();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);

    // add a doc with a location
    Document document = new Document();
    document.add(new LatLonPoint("field", 18.313694, -65.227444));
    writer.addDocument(document);
    
    // search within 50km and verify we found our doc
    IndexReader reader = writer.getReader();
    IndexSearcher searcher = newSearcher(reader);
    assertEquals(1, searcher.count(LatLonPoint.newDistanceQuery("field", 18, -65, 50_000)));

    reader.close();
    writer.close();
    dir.close();
  }
  
  /** negative distance queries are not allowed */
  public void testNegativeRadius() {
    IllegalArgumentException expected = expectThrows(IllegalArgumentException.class, () -> {
      LatLonPoint.newDistanceQuery("field", 18, 19, -1);
    });
    assertTrue(expected.getMessage().contains("radiusMeters"));
    assertTrue(expected.getMessage().contains("is invalid"));
  }
  
  /** NaN distance queries are not allowed */
  public void testNaNRadius() {
    IllegalArgumentException expected = expectThrows(IllegalArgumentException.class, () -> {
      LatLonPoint.newDistanceQuery("field", 18, 19, Double.NaN);
    });
    assertTrue(expected.getMessage().contains("radiusMeters"));
    assertTrue(expected.getMessage().contains("is invalid"));
  }
  
  /** Inf distance queries are not allowed */
  public void testInfRadius() {
    IllegalArgumentException expected;
    
    expected = expectThrows(IllegalArgumentException.class, () -> {
      LatLonPoint.newDistanceQuery("field", 18, 19, Double.POSITIVE_INFINITY);
    });
    assertTrue(expected.getMessage().contains("radiusMeters"));
    assertTrue(expected.getMessage().contains("is invalid"));
    
    expected = expectThrows(IllegalArgumentException.class, () -> {
      LatLonPoint.newDistanceQuery("field", 18, 19, Double.NEGATIVE_INFINITY);
    });
    assertTrue(expected.getMessage().contains("radiusMeters"));
    assertTrue(expected.getMessage().contains("is invalid"));
  }
  
  public void testCircleOpto() throws Exception {
    for (int i = 0; i < 1000000; i++) {
      // circle
      double centerLat = -90 + 180.0 * random().nextDouble();
      double centerLon = -180 + 360.0 * random().nextDouble();
      double radius = 50_000_000D * random().nextDouble();
      
      // box
      double latMin = -90 + 180.0 * random().nextDouble();
      double latMax = -90 + 180.0 * random().nextDouble();
      double lonMin = -180 + 360.0 * random().nextDouble();
      double lonMax = -180 + 360.0 * random().nextDouble();

      if (latMax < latMin) {
        double tmp = latMin;
        latMin = latMax;
        latMax = tmp;
      }

      if (lonMax < lonMin) {
        double tmp = lonMin;
        lonMin = lonMax;
        lonMax = tmp;
      }

      if (intersects(centerLat, centerLon, radius, latMin, latMax, lonMin, lonMax) == false) {
        // intersects says false: test a ton of points
        for (int j = 0; j < 100; j++) {
          double lat = latMin + (latMax - latMin) * random().nextDouble();
          double lon = lonMin + (lonMax - lonMin) * random().nextDouble();
          double distance = SloppyMath.haversinMeters(centerLat, centerLon, lat, lon);
          assertTrue(String.format("\nintersects(\n" +
                                   "centerLat=%s\n" +
                                   "centerLon=%s\n" +
                                   "radius=%s\n" +
                                   "latMin=%s\n" +
                                   "latMax=%s\n" +
                                   "lonMin=%s\n" + 
                                   "lonMax=%s) == false BUT\n" +
                                   "haversin(%s, %s, %s, %s) = %s\nbbox=%s", 
                                    centerLat, centerLon, radius, latMin, latMax, lonMin, lonMax, 
                                    centerLat, centerLon, lat, lon, distance, GeoUtils.circleToBBox(centerLat, centerLon, radius)),
                     distance > radius);
        }
      }
    }
  }
  
  static boolean intersects(double centerLat, double centerLon, double radius, double latMin, double latMax, double lonMin, double lonMax) {
    GeoRect box = GeoUtils.circleToBBox(centerLat, centerLon, radius);
    if (lonMax - centerLon < 90 && centerLon - lonMin < 90 && /* box is not wrapping around the world */
        box.maxLon - box.minLon < 90 && /* circle is not wrapping around the world */
        box.crossesDateline() == false) /* or crossing dateline! */ {
      // ok
    } else {
      return true;
    }

    if ((centerLat >= latMin && centerLat <= latMax) || (centerLon >= lonMin && centerLon <= lonMax)) {
      // e.g. circle itself fully inside
      return true;
    }
    double closestLat = Math.max(latMin, Math.min(centerLat, latMax));
    double closestLon = Math.max(lonMin, Math.min(centerLon, lonMax));
    return SloppyMath.haversinMeters(centerLat, centerLon, closestLat, closestLon) <= radius;
  }
}
