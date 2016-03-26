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

import com.carrotsearch.randomizedtesting.annotations.Repeat;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.RandomIndexWriter;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.spatial.util.GeoRect;
import org.apache.lucene.spatial.util.GeoUtils;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.SloppyMath;
import org.apache.lucene.util.packed.PackedDataInput;

import static org.apache.lucene.util.SloppyMath.TO_RADIANS;
import static org.apache.lucene.util.SloppyMath.asin;
import static org.apache.lucene.util.SloppyMath.cos;

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

  //@Repeat(iterations = 100)
  public void testCircleOpto() throws Exception {
    for (int i = 0; i < 1000; i++) {
      // circle
      double centerLat = -90 + 180.0 * random().nextDouble();
      double centerLon = -180 + 360.0 * random().nextDouble();
      double radius = 50_000_000D * random().nextDouble();
      GeoRect box = GeoUtils.circleToBBox(centerLat, centerLon, radius);
      if (box.crossesDateline()) {
        --i; // try again...
        continue;
      }

      for (int k = 0; k < 1000; ++k) {

        double[] latBounds = {-90, box.minLat, centerLat, box.maxLat, 90};
        double[] lonBounds = {-180, box.minLon, centerLon, box.maxLon, 180};
        // first choose an upper left corner
        int maxLatRow = random().nextInt(4);
        double latMax = randomInRange(latBounds[maxLatRow], latBounds[maxLatRow + 1]);
        int minLonCol = random().nextInt(4);
        double lonMin = randomInRange(lonBounds[minLonCol], lonBounds[minLonCol + 1]);
        // now choose a lower right corner
        int minLatMaxRow = maxLatRow == 3 ? 3 : maxLatRow + 1; // make sure it will at least cross into the bbox
        int minLatRow = random().nextInt(minLatMaxRow);
        double latMin = randomInRange(latBounds[minLatRow], Math.min(latBounds[minLatRow + 1], latMax));
        int maxLonMinCol = Math.max(minLonCol, 1); // make sure it will at least cross into the bbox
        int maxLonCol = maxLonMinCol + random().nextInt(4 - maxLonMinCol);
        double lonMax = randomInRange(Math.max(lonBounds[maxLonCol], lonMin), lonBounds[maxLonCol + 1]);

        assert latMax >= latMin;
        assert lonMax >= lonMin;

        if (isDisjoint(centerLat, centerLon, radius, latMin, latMax, lonMin, lonMax)) {
          // intersects says false: test a ton of points
          for (int j = 0; j < 200; j++) {
            double lat = latMin + (latMax - latMin) * random().nextDouble();
            double lon = lonMin + (lonMax - lonMin) * random().nextDouble();

            if (random().nextBoolean()) {
              // explicitly test an edge
              int edge = random().nextInt(4);
              if (edge == 0) {
                lat = latMin;
              } else if (edge == 1) {
                lat = latMax;
              } else if (edge == 2) {
                lon = lonMin;
              } else if (edge == 3) {
                lon = lonMax;
              }
            }
            double distance = SloppyMath.haversinMeters(centerLat, centerLon, lat, lon);
            assertTrue(String.format("\nisDisjoint(\n" +
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
  }

  static double randomInRange(double min, double max) {
    return min + (max - min) * random().nextDouble();
  }

  public void testFailure() throws Exception {
    // circle
    double centerLat = 42.07639048470125d;
    double centerLon = 107.07274153895605d;
    double radius = 2975340.785331476;

    // box
    double latMin = 20.349435726973987;
    double latMax = 75.6212078954794;
    double lonMin = 72.85917553486915;
    double lonMax = 77.19077779277924;
    if (isDisjoint(centerLat, centerLon, radius, latMin, latMax, lonMin, lonMax)) {
      // intersects says false: test a ton of points
      for (int j = 0; j < 100; j++) {
        double lat = latMin + (latMax - latMin) * random().nextDouble();
        double lon = lonMin + (lonMax - lonMin) * random().nextDouble();
        double distance = SloppyMath.haversinMeters(centerLat, centerLon, lat, lon);
        /*if (distance <= radius) {
          toWebGLEarth(latMin, latMax, lonMin, lonMax, centerLat, centerLon, radius);
        }*/
        assertTrue(String.format("\nisDisjoint(\n" +
                "centerLat=%s\n" +
                "centerLon=%s\n" +
                "radius=%s\n" +
                "latMin=%s\n" +
                "latMax=%s\n" +
                "lonMin=%s\n" +
                "lonMax=%s) == true BUT\n" +
                "haversin(%s, %s, %s, %s) = %s\nbbox=%s",
            centerLat, centerLon, radius, latMin, latMax, lonMin, lonMax,
            centerLat, centerLon, lat, lon, distance, GeoUtils.circleToBBox(centerLat, centerLon, radius)),
            distance > radius);
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

  static boolean isDisjoint(double centerLat, double centerLon, double radius, double latMin, double latMax, double lonMin, double lonMax) {
    GeoRect box = GeoUtils.circleToBBox(centerLat, centerLon, radius);
    if (lonMax - centerLon < 90 && centerLon - lonMin < 90 && /* box is not wrapping around the world */
        box.maxLon - box.minLon < 90 && /* circle is not wrapping around the world */
        box.crossesDateline() == false) /* or crossing dateline! */ {
      // ok
    } else {
      return false;
    }

    boolean bottomOut = latMin < box.minLat || latMin > box.maxLat;
    boolean topOut = latMax > box.maxLat || latMax < box.minLat;
    boolean leftOut = lonMin < box.minLon || lonMin > box.maxLon;
    boolean rightOut = lonMax > box.maxLon || lonMax < box.minLon;
    int state = (leftOut ? 0 : 8) + (bottomOut ? 0 : 4) + (rightOut ? 0 : 2) + (topOut ? 0 : 1);
    if (state == 0) {
      return false;
    } else if (state == 1) {
      return false;
    } else if (state == 2) {
      return false;
    } else if (state == 3) {
      // return upper right not in and no axis crossing
      return latMax < GeoUtils.axisLat(centerLat, radius) && lonMax < centerLon &&
             pointInCircle(centerLat, centerLon, radius, latMax, lonMax) == false;
    } else if (state == 4) {
      return false;
    } else if (state == 5) {
      return false;
    } else if (state == 6) {
      // return lower right not in and no axis crossing
      return latMin > GeoUtils.axisLat(centerLat, radius) && lonMax < centerLon &&
             pointInCircle(centerLat, centerLon, radius, latMin, lonMax) == false;
    } else if (state == 7) {
      // return upper and lower right not in and no axis crossing
      double axisLat = GeoUtils.axisLat(centerLat, radius);
      return (latMin > axisLat || latMax < axisLat) && lonMax < centerLon &&
             pointInCircle(centerLat, centerLon, radius, latMin, lonMax) == false &&
             pointInCircle(centerLat, centerLon, radius, latMax, lonMax) == false;
    } else if (state == 8) {
      return false;
    } else if (state == 9) {
      // return upper left not in and no axis
      return latMax < GeoUtils.axisLat(centerLat, radius) && lonMin > centerLon &&
             pointInCircle(centerLat, centerLon, radius, latMax, lonMin) == false;
    } else if (state == 10) {
      return false;
    } else if (state == 11) {
      // return top left and right not in and no axis
      return (lonMax < centerLon || lonMin > centerLon) && latMax < GeoUtils.axisLat(centerLat, radius) &&
             pointInCircle(centerLat, centerLon, radius, latMax, lonMin) == false &&
             pointInCircle(centerLat, centerLon, radius, latMax, lonMax) == false;
    } else if (state == 12) {
      // return bottom left not in and no axis
      return latMin > GeoUtils.axisLat(centerLat, radius) && lonMin > centerLon &&
             pointInCircle(centerLat, centerLon, radius, latMin, lonMin) == false;
    } else if (state == 13) {
      // return top left and bottom left not in and no axis
      double axisLat = GeoUtils.axisLat(centerLat, radius);
      return (latMin > axisLat || latMax < axisLat) && lonMin > centerLon &&
             pointInCircle(centerLat, centerLon, radius, latMax, lonMin) == false &&
             pointInCircle(centerLat, centerLon, radius, latMin, lonMin) == false;
    } else if (state == 14) {
      // return bottom left and right not in and no axis
      return (lonMax < centerLon || lonMin > centerLon) && latMin > GeoUtils.axisLat(centerLat, radius) &&
             pointInCircle(centerLat, centerLon, radius, latMin, lonMin) == false &&
             pointInCircle(centerLat, centerLon, radius, latMin, lonMax) == false;
    } else { // state == 15
      return false; // TODO: axis crossing
      /*return pointInCircle(centerLat, centerLon, radius, latMax, lonMin) == false &&
             pointInCircle(centerLat, centerLon, radius, latMax, lonMax) == false &&
             pointInCircle(centerLat, centerLon, radius, latMin, lonMin) == false &&
             pointInCircle(centerLat, centerLon, radius, latMin, lonMax) == false;*/
    }
  }

  static boolean pointInCircle(double centerLat, double centerLon, double radius, double lat, double lon) {
    return SloppyMath.haversinMeters(centerLat, centerLon, lat, lon) <= radius;
  }


}
