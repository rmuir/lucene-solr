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
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.TestUtil;

/** tests against LatLonGrid (avoiding indexing/queries) */
public class TestLatLonGrid extends LuceneTestCase {

  /** If the grid returns true, then any point in that cell should return true as well */
  public void testRandom() throws Exception {
    for (int i = 0; i < 100; i++) {
      Polygon polygon = GeoTestUtil.nextPolygon();
      Rectangle box = Rectangle.fromPolygon(new Polygon[] { polygon });
      int minLat = LatLonPoint.encodeLatitude(box.minLat);
      int maxLat = LatLonPoint.encodeLatitude(box.maxLat);
      int minLon = LatLonPoint.encodeLongitude(box.minLon);
      int maxLon = LatLonPoint.encodeLongitude(box.maxLon);
      LatLonGrid grid = new LatLonGrid(minLat, maxLat, minLon, maxLon, polygon);
      // we are in integer space... but exhaustive testing is slow!
      for (int j = 0; j < 10000; j++) {
        int lat = TestUtil.nextInt(random(), minLat, maxLat);
        int lon = TestUtil.nextInt(random(), minLon, maxLon);

        boolean expected = polygon.contains(LatLonPoint.decodeLatitude(lat), 
                                            LatLonPoint.decodeLongitude(lon));
        int index = grid.index(lat, lon);
        if (index == -1) {
          // outside bounding box range
          assertFalse(expected);
        } else if (grid.haveAnswer.get(index)) {
          if (grid.answer.get(index)) {
            // contains
            assertTrue(expected);
          } else {
            // disjoint
            assertFalse(expected);
          }
        }
      }
    }
  }
}
