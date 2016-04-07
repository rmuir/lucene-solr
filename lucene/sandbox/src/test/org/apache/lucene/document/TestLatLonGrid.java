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

import org.apache.lucene.geo.GeoTestUtil;
import org.apache.lucene.geo.Polygon;
import org.apache.lucene.geo.Rectangle;
import org.apache.lucene.index.PointValues.Relation;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.TestUtil;

/** tests against LatLonGrid (avoiding indexing/queries) */
public class TestLatLonGrid extends LuceneTestCase {

  /** contains() should always be consistent with underlying polygon */
  public void testContainsRandom() throws Exception {
    for (int i = 0; i < 100; i++) {
      Polygon polygon = GeoTestUtil.nextPolygon();
      Rectangle box = Rectangle.fromPolygon(new Polygon[] { polygon });
      int minLat = LatLonPoint.encodeLatitude(box.minLat);
      int maxLat = LatLonPoint.encodeLatitude(box.maxLat);
      int minLon = LatLonPoint.encodeLongitude(box.minLon);
      int maxLon = LatLonPoint.encodeLongitude(box.maxLon);
      LatLonGrid grid = new LatLonGrid(minLat, maxLat, minLon, maxLon, polygon);
      // we are in integer space... but exhaustive testing is slow!
      // these checks are all inside the bounding box of the polygon!
      for (int j = 0; j < 5000; j++) {
        int lat = TestUtil.nextInt(random(), minLat, maxLat);
        int lon = TestUtil.nextInt(random(), minLon, maxLon);

        boolean expected = polygon.contains(LatLonPoint.decodeLatitude(lat), 
                                            LatLonPoint.decodeLongitude(lon));
        boolean actual = grid.contains(lat, lon);
        assertEquals(expected, actual);
      }
      // check some truly random points too
      for (int j = 0; j < 5000; j++) {
        int lat = random().nextInt();
        int lon = random().nextInt();

        boolean expected = polygon.contains(LatLonPoint.decodeLatitude(lat), 
                                            LatLonPoint.decodeLongitude(lon));
        boolean actual = grid.contains(lat, lon);
        assertEquals(expected, actual);
      }
    }
  }
  
  /** relate() should always be consistent with underlying polygon */
  public void testRelateRandom() throws Exception {
    for (int i = 0; i < 100; i++) {
      //System.out.println("\nTEST: iter=" + i);
      Polygon polygon = GeoTestUtil.nextPolygon();
      //System.out.println("  poly=" + polygon);
      Rectangle box = Rectangle.fromPolygon(new Polygon[] { polygon });
      int minLat = LatLonPoint.encodeLatitude(box.minLat);
      int maxLat = LatLonPoint.encodeLatitude(box.maxLat);
      int minLon = LatLonPoint.encodeLongitude(box.minLon);
      int maxLon = LatLonPoint.encodeLongitude(box.maxLon);
      LatLonGrid grid = new LatLonGrid(minLat, maxLat, minLon, maxLon, polygon);
      // we are in integer space... but exhaustive testing is slow!
      // these boxes are all inside the bounding box of the polygon!
      for (int j = 0; j < 5000; j++) {
        int lat1 = TestUtil.nextInt(random(), minLat, maxLat);
        int lat2 = TestUtil.nextInt(random(), minLat, maxLat);
        int lon1 = TestUtil.nextInt(random(), minLon, maxLon);
        int lon2 = TestUtil.nextInt(random(), minLon, maxLon);
        
        int docMinLat = Math.min(lat1, lat2);
        int docMaxLat = Math.max(lat1, lat2);
        int docMinLon = Math.min(lon1, lon2);
        int docMaxLon = Math.max(lon1, lon2);

        Relation expected = polygon.relate(LatLonPoint.decodeLatitude(docMinLat), LatLonPoint.decodeLatitude(docMaxLat), 
                                           LatLonPoint.decodeLongitude(docMinLon), LatLonPoint.decodeLongitude(docMaxLon));
        //System.out.println("  test lat=" + docMinLat + " TO " + docMaxLat + " lon=" + docMinLon + " TO " + docMaxLon);
        Relation actual = grid.relate(docMinLat, docMaxLat, docMinLon, docMaxLon);
        assertEquals(expected, actual);
      }
      // check some truly random boxes too
      for (int j = 0; j < 5000; j++) {
        int lat1 = random().nextInt();
        int lat2 = random().nextInt();
        int lon1 = random().nextInt();
        int lon2 = random().nextInt();
        
        int docMinLat = Math.min(lat1, lat2);
        int docMaxLat = Math.max(lat1, lat2);
        int docMinLon = Math.min(lon1, lon2);
        int docMaxLon = Math.max(lon1, lon2);

        Relation expected = polygon.relate(LatLonPoint.decodeLatitude(docMinLat), LatLonPoint.decodeLatitude(docMaxLat), 
                                           LatLonPoint.decodeLongitude(docMinLon), LatLonPoint.decodeLongitude(docMaxLon));
        Relation actual = grid.relate(docMinLat, docMaxLat, docMinLon, docMaxLon);
        assertEquals(expected, actual);
      }
    }
  }
}
