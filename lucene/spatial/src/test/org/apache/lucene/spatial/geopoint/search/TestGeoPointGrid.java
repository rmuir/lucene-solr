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
package org.apache.lucene.spatial.geopoint.search;

import org.apache.lucene.geo.GeoTestUtil;
import org.apache.lucene.geo.Polygon;
import org.apache.lucene.geo.Rectangle;
import org.apache.lucene.index.PointValues.Relation;
import org.apache.lucene.spatial.geopoint.search.GeoPointGrid;
import org.apache.lucene.spatial.util.GeoEncodingUtils;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.TestUtil;

/** tests against GeoPointGrid (avoiding indexing/queries) */
public class TestGeoPointGrid extends LuceneTestCase {

  /** contains() should always be consistent with underlying polygon */
  public void testContainsRandom() throws Exception {
    for (int i = 0; i < 100; i++) {
      Polygon polygon = GeoTestUtil.nextPolygon();
      Rectangle box = Rectangle.fromPolygon(new Polygon[] { polygon });
      long minLat = GeoEncodingUtils.scaleLat(box.minLat);
      long maxLat = GeoEncodingUtils.scaleLat(box.maxLat);
      long minLon = GeoEncodingUtils.scaleLon(box.minLon);
      long maxLon = GeoEncodingUtils.scaleLon(box.maxLon);
      GeoPointGrid grid = new GeoPointGrid(minLat, maxLat, minLon, maxLon, polygon);
      // we are in integer space... but exhaustive testing is slow!
      // these checks are all inside the bounding box of the polygon!
      for (int j = 0; j < 5000; j++) {
        long lat = TestUtil.nextLong(random(), minLat, maxLat);
        long lon = TestUtil.nextLong(random(), minLon, maxLon);

        boolean expected = polygon.contains(GeoEncodingUtils.unscaleLat(lat), 
                                            GeoEncodingUtils.unscaleLon(lon));
        boolean actual = grid.contains(lat, lon);
        assertEquals(expected, actual);
      }
      // check some truly random points too
      for (int j = 0; j < 5000; j++) {
        long lat = TestUtil.nextLong(random(), 0, Integer.MAX_VALUE);
        long lon = TestUtil.nextLong(random(), 0, Integer.MAX_VALUE);

        boolean expected = polygon.contains(GeoEncodingUtils.unscaleLat(lat), 
                                            GeoEncodingUtils.unscaleLon(lon));
        boolean actual = grid.contains(lat, lon);
        assertEquals(expected, actual);
      }
    }
  }
  
  /** relate() should always be consistent with underlying polygon */
  public void testRelateRandom() throws Exception {
    for (int i = 0; i < 100; i++) {
      Polygon polygon = GeoTestUtil.nextPolygon();
      Rectangle box = Rectangle.fromPolygon(new Polygon[] { polygon });
      long minLat = GeoEncodingUtils.scaleLat(box.minLat);
      long maxLat = GeoEncodingUtils.scaleLat(box.maxLat);
      long minLon = GeoEncodingUtils.scaleLon(box.minLon);
      long maxLon = GeoEncodingUtils.scaleLon(box.maxLon);
      GeoPointGrid grid = new GeoPointGrid(minLat, maxLat, minLon, maxLon, polygon);
      // we are in integer space... but exhaustive testing is slow!
      // these checks are all inside the bounding box of the polygon!
      for (int j = 0; j < 5000; j++) {
        long lat1 = TestUtil.nextLong(random(), minLat, maxLat);
        long lat2 = TestUtil.nextLong(random(), minLat, maxLat);
        long lon1 = TestUtil.nextLong(random(), minLon, maxLon);
        long lon2 = TestUtil.nextLong(random(), minLon, maxLon);
        
        long cellMinLat = Math.min(lat1, lat2);
        long cellMaxLat = Math.max(lat1, lat2);
        long cellMinLon = Math.min(lon1, lon2);
        long cellMaxLon = Math.max(lon1, lon2);

        Relation expected = Polygon.relate(new Polygon[] { polygon }, GeoEncodingUtils.unscaleLat(cellMinLat), 
                                                                      GeoEncodingUtils.unscaleLat(cellMaxLat), 
                                                                      GeoEncodingUtils.unscaleLon(cellMinLon), 
                                                                      GeoEncodingUtils.unscaleLon(cellMaxLon));
        Relation actual = grid.relate(cellMinLat, cellMaxLat, cellMinLon, cellMaxLon);
        if (actual != Relation.CELL_CROSSES_QUERY) {
          assertEquals(expected, actual);
        }
      }
      // check some truly random points too
      for (int j = 0; j < 5000; j++) {
        long lat1 = TestUtil.nextLong(random(), 0, Integer.MAX_VALUE);
        long lat2 = TestUtil.nextLong(random(), 0, Integer.MAX_VALUE);
        long lon1 = TestUtil.nextLong(random(), 0, Integer.MAX_VALUE);
        long lon2 = TestUtil.nextLong(random(), 0, Integer.MAX_VALUE);
        
        long cellMinLat = Math.min(lat1, lat2);
        long cellMaxLat = Math.max(lat1, lat2);
        long cellMinLon = Math.min(lon1, lon2);
        long cellMaxLon = Math.max(lon1, lon2);

        Relation expected = Polygon.relate(new Polygon[] { polygon }, GeoEncodingUtils.unscaleLat(cellMinLat), 
                                                                      GeoEncodingUtils.unscaleLat(cellMaxLat), 
                                                                      GeoEncodingUtils.unscaleLon(cellMinLon), 
                                                                      GeoEncodingUtils.unscaleLon(cellMaxLon));
        Relation actual = grid.relate(cellMinLat, cellMaxLat, cellMinLon, cellMaxLon);
        if (actual != Relation.CELL_CROSSES_QUERY) {
          assertEquals(expected, actual);
        }
      }
    }
  }
}
