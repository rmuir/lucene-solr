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

import org.apache.lucene.geo.Polygon;
import org.apache.lucene.index.PointValues.Relation;
import org.apache.lucene.util.FixedBitSet;

/**
 * This is a temporary hack, until some polygon methods have better performance!
 * <p>
 * When this file is removed then we have made good progress! In general we don't call
 * the point-in-polygon algorithm that much, because of how BKD divides up the data. But
 * today the method is very slow (general to all polygons, linear with the number of vertices).
 * At the same time polygon-rectangle relation operations are also slow in the same way, this
 * just really ensures they are the bottleneck by removing most of the point-in-polygon calls.
 * <p>
 * See the "grid" algorithm description here: http://erich.realtimerendering.com/ptinpoly/
 * A few differences:
 * <ul>
 *   <li> We work in an integer encoding, so edge cases are simpler.
 *   <li> We classify each grid cell as "contained", "not contained", or "don't know".
 *   <li> We form a grid over a potentially complex multipolygon with holes.
 *   <li> Construction is less efficient because we do not do anything "smart" such
 *        as following polygon edges. 
 *   <li> Instead we construct a baby tree to reduce the number of relation operations,
 *        which are currently expensive.
 * </ul>
 */
// TODO: just make a more proper tree (maybe in-ram BKD)? then we can answer most 
// relational operations as rectangle <-> rectangle relations in integer space in log(n) time..
class LatLonGrid {
  // must be a power of two!
  static final int GRID_SIZE = 1<<5;
  final int minLat;
  final int maxLat;
  final int minLon;
  final int maxLon;
  final FixedBitSet haveAnswer = new FixedBitSet(GRID_SIZE * GRID_SIZE);
  final FixedBitSet answer = new FixedBitSet(GRID_SIZE * GRID_SIZE);
  
  final long latPerCell;
  final long lonPerCell;
  
  LatLonGrid(int minLat, int maxLat, int minLon, int maxLon, Polygon... polygons) {
    this.minLat = minLat;
    this.maxLat = maxLat;
    this.minLon = minLon;
    this.maxLon = maxLon;
    if (minLon > maxLon) {
      // maybe make 2 grids if you want this? 
      throw new IllegalArgumentException("Grid cannot cross the dateline");
    }
    if (minLat > maxLat) {
      throw new IllegalArgumentException("bogus grid");
    }
    long latitudeRange = maxLat - (long) minLat;
    long longitudeRange = maxLon - (long) minLon;
    latPerCell = latitudeRange / (GRID_SIZE - 1);
    lonPerCell = longitudeRange / (GRID_SIZE - 1);
    fill(polygons, 0, GRID_SIZE, 0, GRID_SIZE);
  }
  
  void fill(Polygon[] polygons, int minLatIndex, int maxLatIndex, int minLonIndex, int maxLonIndex) {    
    long cellMinLat = minLat + (minLatIndex * latPerCell);
    long cellMaxLat = Math.min(maxLat, minLat + (maxLatIndex * latPerCell) - 1);
    long cellMinLon = minLon + (minLonIndex * lonPerCell);
    long cellMaxLon = Math.min(maxLon, minLon + (maxLonIndex * lonPerCell) - 1);
    assert cellMaxLat >= cellMinLat;
    assert cellMaxLon >= cellMinLon;
    
    final Relation relation;
    if (cellMinLat > maxLat || cellMinLon > maxLon) {
      relation = Relation.CELL_CROSSES_QUERY;
    } else {
      relation = Polygon.relate(polygons, LatLonPoint.decodeLatitude((int) cellMinLat), 
                                          LatLonPoint.decodeLatitude((int) cellMaxLat), 
                                          LatLonPoint.decodeLongitude((int) cellMinLon), 
                                          LatLonPoint.decodeLongitude((int) cellMaxLon));
    }
    if (relation != Relation.CELL_CROSSES_QUERY) {
      for (int i = minLatIndex; i < maxLatIndex; i++) {
        for (int j = minLonIndex; j < maxLonIndex; j++) {
          int index = i * GRID_SIZE + j;
          assert haveAnswer.get(index) == false;
          haveAnswer.set(index);
          if (relation == Relation.CELL_INSIDE_QUERY) {
            answer.set(index);
          }
        }
      }
    } else if (minLatIndex == maxLatIndex - 1) {
      // nothing more to do
    } else {
      // recurse
      int midLatIndex = (minLatIndex + maxLatIndex) / 2;
      int midLonIndex = (minLonIndex + maxLonIndex) / 2;
      fill(polygons, minLatIndex, midLatIndex, minLonIndex, midLonIndex);
      fill(polygons, minLatIndex, midLatIndex, midLonIndex, maxLonIndex);
      fill(polygons, midLatIndex, maxLatIndex, minLonIndex, midLonIndex);
      fill(polygons, midLatIndex, maxLatIndex, midLonIndex, maxLonIndex);
    }
  }
  
  int index(int latitude, int longitude) {
    if (latitude < minLat || latitude > maxLat || longitude < minLon || longitude > maxLon) {
      return -1;
    }
    
    long latRel = latitude - (long) minLat;
    long lonRel = longitude - (long) minLon;
    
    int latIndex = (int) (latRel / latPerCell);
    int lonIndex = (int) (lonRel / lonPerCell);
    return latIndex * GRID_SIZE + lonIndex;
  }
}
