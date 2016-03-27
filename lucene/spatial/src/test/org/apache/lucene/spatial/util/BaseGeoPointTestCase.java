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
package org.apache.lucene.spatial.util;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.lucene.codecs.FilterCodec;
import org.apache.lucene.codecs.PointsFormat;
import org.apache.lucene.codecs.PointsReader;
import org.apache.lucene.codecs.PointsWriter;
import org.apache.lucene.codecs.lucene60.Lucene60PointsReader;
import org.apache.lucene.codecs.lucene60.Lucene60PointsWriter;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiDocValues;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.index.RandomIndexWriter;
import org.apache.lucene.index.SegmentReadState;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SimpleCollector;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.SloppyMath;
import org.apache.lucene.util.TestUtil;
import org.apache.lucene.util.bkd.BKDWriter;
import org.junit.BeforeClass;

// TODO: cutover TestGeoUtils too?

public abstract class BaseGeoPointTestCase extends LuceneTestCase {

  protected static final String FIELD_NAME = "point";

  private static double originLat;
  private static double originLon;
  private static double lonRange;
  private static double latRange;

  @BeforeClass
  public static void beforeClassBase() throws Exception {
    // Between 1.0 and 3.0:
    lonRange = 2 * (random().nextDouble() + 0.5);
    latRange = 2 * (random().nextDouble() + 0.5);

    originLon = normalizeLon(GeoUtils.MIN_LON_INCL + lonRange + (GeoUtils.MAX_LON_INCL - GeoUtils.MIN_LON_INCL - 2 * lonRange) * random().nextDouble());
    originLat = normalizeLat(GeoUtils.MIN_LAT_INCL + latRange + (GeoUtils.MAX_LAT_INCL - GeoUtils.MIN_LAT_INCL - 2 * latRange) * random().nextDouble());
  }

  /** Puts longitude in range of -180 to +180. */
  public static double normalizeLon(double lon_deg) {
    if (lon_deg >= -180 && lon_deg <= 180) {
      return lon_deg; //common case, and avoids slight double precision shifting
    }
    double off = (lon_deg + 180) % 360;
    if (off < 0) {
      return 180 + off;
    } else if (off == 0 && lon_deg > 0) {
      return 180;
    } else {
      return -180 + off;
    }
  }

  /** Puts latitude in range of -90 to 90. */
  public static double normalizeLat(double lat_deg) {
    if (lat_deg >= -90 && lat_deg <= 90) {
      return lat_deg; //common case, and avoids slight double precision shifting
    }
    double off = Math.abs((lat_deg + 90) % 360);
    return (off <= 180 ? off : 360-off) - 90;
  }

  // A particularly tricky adversary for BKD tree:
  public void testSamePointManyTimes() throws Exception {
    int numPoints = atLeast(1000);
    boolean small = random().nextBoolean();

    // Every doc has 2 points:
    double theLat = randomLat(small);
    double theLon = randomLon(small);

    double[] lats = new double[numPoints];
    Arrays.fill(lats, theLat);

    double[] lons = new double[numPoints];
    Arrays.fill(lons, theLon);

    verify(small, lats, lons, false);
  }

  public void testAllLatEqual() throws Exception {
    int numPoints = atLeast(10000);
    boolean small = random().nextBoolean();
    double lat = randomLat(small);
    double[] lats = new double[numPoints];
    double[] lons = new double[numPoints];

    boolean haveRealDoc = false;

    for(int docID=0;docID<numPoints;docID++) {
      int x = random().nextInt(20);
      if (x == 17) {
        // Some docs don't have a point:
        lats[docID] = Double.NaN;
        if (VERBOSE) {
          System.out.println("  doc=" + docID + " is missing");
        }
        continue;
      }

      if (docID > 0 && x == 14 && haveRealDoc) {
        int oldDocID;
        while (true) {
          oldDocID = random().nextInt(docID);
          if (Double.isNaN(lats[oldDocID]) == false) {
            break;
          }
        }
            
        // Fully identical point:
        lons[docID] = lons[oldDocID];
        if (VERBOSE) {
          System.out.println("  doc=" + docID + " lat=" + lat + " lon=" + lons[docID] + " (same lat/lon as doc=" + oldDocID + ")");
        }
      } else {
        lons[docID] = randomLon(small);
        haveRealDoc = true;
        if (VERBOSE) {
          System.out.println("  doc=" + docID + " lat=" + lat + " lon=" + lons[docID]);
        }
      }
      lats[docID] = lat;
    }

    verify(small, lats, lons, false);
  }

  public void testAllLonEqual() throws Exception {
    int numPoints = atLeast(10000);
    boolean small = random().nextBoolean();
    double theLon = randomLon(small);
    double[] lats = new double[numPoints];
    double[] lons = new double[numPoints];

    boolean haveRealDoc = false;

    //System.out.println("theLon=" + theLon);

    for(int docID=0;docID<numPoints;docID++) {
      int x = random().nextInt(20);
      if (x == 17) {
        // Some docs don't have a point:
        lats[docID] = Double.NaN;
        if (VERBOSE) {
          System.out.println("  doc=" + docID + " is missing");
        }
        continue;
      }

      if (docID > 0 && x == 14 && haveRealDoc) {
        int oldDocID;
        while (true) {
          oldDocID = random().nextInt(docID);
          if (Double.isNaN(lats[oldDocID]) == false) {
            break;
          }
        }
            
        // Fully identical point:
        lats[docID] = lats[oldDocID];
        if (VERBOSE) {
          System.out.println("  doc=" + docID + " lat=" + lats[docID] + " lon=" + theLon + " (same lat/lon as doc=" + oldDocID + ")");
        }
      } else {
        lats[docID] = randomLat(small);
        haveRealDoc = true;
        if (VERBOSE) {
          System.out.println("  doc=" + docID + " lat=" + lats[docID] + " lon=" + theLon);
        }
      }
      lons[docID] = theLon;
    }

    verify(small, lats, lons, false);
  }

  public void testMultiValued() throws Exception {
    int numPoints = atLeast(10000);
    // Every doc has 2 points:
    double[] lats = new double[2*numPoints];
    double[] lons = new double[2*numPoints];
    Directory dir = newDirectory();
    IndexWriterConfig iwc = newIndexWriterConfig();
    initIndexWriterConfig(FIELD_NAME, iwc);

    // We rely on docID order:
    iwc.setMergePolicy(newLogMergePolicy());
    RandomIndexWriter w = new RandomIndexWriter(random(), dir, iwc);

    boolean small = random().nextBoolean();

    for (int id=0;id<numPoints;id++) {
      Document doc = new Document();
      lats[2*id] = randomLat(small);
      lons[2*id] = randomLon(small);
      doc.add(newStringField("id", ""+id, Field.Store.YES));
      addPointToDoc(FIELD_NAME, doc, lats[2*id], lons[2*id]);
      lats[2*id+1] = randomLat(small);
      lons[2*id+1] = randomLon(small);
      addPointToDoc(FIELD_NAME, doc, lats[2*id+1], lons[2*id+1]);

      if (VERBOSE) {
        System.out.println("id=" + id);
        System.out.println("  lat=" + lats[2*id] + " lon=" + lons[2*id]);
        System.out.println("  lat=" + lats[2*id+1] + " lon=" + lons[2*id+1]);
      }
      w.addDocument(doc);
    }

    // TODO: share w/ verify; just need parallel array of the expected ids
    if (random().nextBoolean()) {
      w.forceMerge(1);
    }
    IndexReader r = w.getReader();
    w.close();

    // We can't wrap with "exotic" readers because the BKD query must see the BKDDVFormat:
    IndexSearcher s = newSearcher(r, false);

    int iters = atLeast(75);
    for (int iter=0;iter<iters;iter++) {
      GeoRect rect = randomRect(small, small == false);

      if (VERBOSE) {
        System.out.println("\nTEST: iter=" + iter + " rect=" + rect);
      }

      Query query = newRectQuery(FIELD_NAME, rect);

      final FixedBitSet hits = new FixedBitSet(r.maxDoc());
      s.search(query, new SimpleCollector() {

          private int docBase;

          @Override
          public boolean needsScores() {
            return false;
          }

          @Override
          protected void doSetNextReader(LeafReaderContext context) throws IOException {
            docBase = context.docBase;
          }

          @Override
          public void collect(int doc) {
            hits.set(docBase+doc);
          }
        });

      boolean fail = false;

      for(int docID=0;docID<lats.length/2;docID++) {
        double latDoc1 = lats[2*docID];
        double lonDoc1 = lons[2*docID];
        double latDoc2 = lats[2*docID+1];
        double lonDoc2 = lons[2*docID+1];
        
        boolean result1 = rectContainsPoint(rect, latDoc1, lonDoc1);
        boolean result2 = rectContainsPoint(rect, latDoc2, lonDoc2);

        boolean expected = result1 || result2;

        if (hits.get(docID) != expected) {
          String id = s.doc(docID).get("id");
          if (expected) {
            System.out.println(Thread.currentThread().getName() + ": id=" + id + " docID=" + docID + " should match but did not");
          } else {
            System.out.println(Thread.currentThread().getName() + ": id=" + id + " docID=" + docID + " should not match but did");
          }
          System.out.println("  rect=" + rect);
          System.out.println("  lat=" + latDoc1 + " lon=" + lonDoc1 + "\n  lat=" + latDoc2 + " lon=" + lonDoc2);
          System.out.println("  result1=" + result1 + " result2=" + result2);
          fail = true;
        }
      }

      if (fail) {
        fail("some hits were wrong");
      }
    }
    r.close();
    dir.close();
  }

  public void testRandomTiny() throws Exception {
    // Make sure single-leaf-node case is OK:
    doTestRandom(10, false);
  }

  public void testRandomMedium() throws Exception {
    doTestRandom(10000, false);
  }

  public void testRandomWithThreads() throws Exception {
    doTestRandom(10000, true);
  }

  @Nightly
  public void testRandomBig() throws Exception {
    assumeFalse("Direct codec can OOME on this test", TestUtil.getDocValuesFormat(FIELD_NAME).equals("Direct"));
    assumeFalse("Memory codec can OOME on this test", TestUtil.getDocValuesFormat(FIELD_NAME).equals("Memory"));
    doTestRandom(200000, false);
  }

  private void doTestRandom(int count, boolean useThreads) throws Exception {

    int numPoints = atLeast(count);

    if (VERBOSE) {
      System.out.println("TEST: numPoints=" + numPoints);
    }

    double[] lats = new double[numPoints];
    double[] lons = new double[numPoints];

    boolean small = random().nextBoolean();

    boolean haveRealDoc = false;

    for (int id=0;id<numPoints;id++) {
      int x = random().nextInt(20);
      if (x == 17) {
        // Some docs don't have a point:
        lats[id] = Double.NaN;
        if (VERBOSE) {
          System.out.println("  id=" + id + " is missing");
        }
        continue;
      }

      if (id > 0 && x < 3 && haveRealDoc) {
        int oldID;
        while (true) {
          oldID = random().nextInt(id);
          if (Double.isNaN(lats[oldID]) == false) {
            break;
          }
        }
            
        if (x == 0) {
          // Identical lat to old point
          lats[id] = lats[oldID];
          lons[id] = randomLon(small);
          if (VERBOSE) {
            System.out.println("  id=" + id + " lat=" + lats[id] + " lon=" + lons[id] + " (same lat as doc=" + oldID + ")");
          }
        } else if (x == 1) {
          // Identical lon to old point
          lats[id] = randomLat(small);
          lons[id] = lons[oldID];
          if (VERBOSE) {
            System.out.println("  id=" + id + " lat=" + lats[id] + " lon=" + lons[id] + " (same lon as doc=" + oldID + ")");
          }
        } else {
          assert x == 2;
          // Fully identical point:
          lats[id] = lats[oldID];
          lons[id] = lons[oldID];
          if (VERBOSE) {
            System.out.println("  id=" + id + " lat=" + lats[id] + " lon=" + lons[id] + " (same lat/lon as doc=" + oldID + ")");
          }
        }
      } else {
        lats[id] = randomLat(small);
        lons[id] = randomLon(small);
        haveRealDoc = true;
        if (VERBOSE) {
          System.out.println("  id=" + id + " lat=" + lats[id] + " lon=" + lons[id]);
        }
      }
    }

    verify(small, lats, lons, useThreads);
  }

  public double randomLat(boolean small) {
    double result;
    if (small) {
      result = normalizeLat(originLat + latRange * (random().nextDouble() - 0.5));
    } else {
      result = -90 + 180.0 * random().nextDouble();
    }
    return quantizeLat(result);
  }

  public double randomLon(boolean small) {
    double result;
    if (small) {
      result = normalizeLon(originLon + lonRange * (random().nextDouble() - 0.5));
    } else {
      result = -180 + 360.0 * random().nextDouble();
    }
    return quantizeLon(result);
  }

  /** Override this to quantize randomly generated lat, so the test won't fail due to quantization errors, which are 1) annoying to debug,
   *  and 2) should never affect "real" usage terribly. */
  protected double quantizeLat(double lat) {
    return lat;
  }

  /** Override this to quantize randomly generated lon, so the test won't fail due to quantization errors, which are 1) annoying to debug,
   *  and 2) should never affect "real" usage terribly. */
  protected double quantizeLon(double lon) {
    return lon;
  }
  
  protected double maxRadius(double latitude, double longitude) {
    return 50000000D; // bigger than earth, shouldnt matter
  }

  protected GeoRect randomRect(boolean small, boolean canCrossDateLine) {
    double lat0 = randomLat(small);
    double lat1 = randomLat(small);
    double lon0 = randomLon(small);
    double lon1 = randomLon(small);

    if (lat1 < lat0) {
      double x = lat0;
      lat0 = lat1;
      lat1 = x;
    }

    if (canCrossDateLine == false && lon1 < lon0) {
      double x = lon0;
      lon0 = lon1;
      lon1 = x;
    }

    return new GeoRect(lat0, lat1, lon0, lon1);
  }

  protected void initIndexWriterConfig(String field, IndexWriterConfig iwc) {
  }

  protected abstract void addPointToDoc(String field, Document doc, double lat, double lon);

  protected abstract Query newRectQuery(String field, GeoRect bbox);

  protected abstract Query newDistanceQuery(String field, double centerLat, double centerLon, double radiusMeters);

  protected abstract Query newDistanceRangeQuery(String field, double centerLat, double centerLon, double minRadiusMeters, double radiusMeters);

  protected abstract Query newPolygonQuery(String field, double[] lats, double[] lons);

  static final boolean rectContainsPoint(GeoRect rect, double pointLat, double pointLon) {
    assert Double.isNaN(pointLat) == false;

    if (rect.minLon < rect.maxLon) {
      return GeoRelationUtils.pointInRectPrecise(pointLat, pointLon, rect.minLat, rect.maxLat, rect.minLon, rect.maxLon);
    } else {
      // Rect crosses dateline:
      return GeoRelationUtils.pointInRectPrecise(pointLat, pointLon, rect.minLat, rect.maxLat, -180.0, rect.maxLon)
        || GeoRelationUtils.pointInRectPrecise(pointLat, pointLon, rect.minLat, rect.maxLat, rect.minLon, 180.0);
    }
  }
  
  static final boolean polyRectContainsPoint(GeoRect rect, double pointLat, double pointLon) {
    // TODO write better random polygon tests
    
    // note: logic must be slightly different than rectContainsPoint, to satisfy
    // insideness for cases exactly on boundaries.
    
    assert Double.isNaN(pointLat) == false;
    assert rect.crossesDateline() == false;
    double polyLats[] = new double[] { rect.minLat, rect.maxLat, rect.maxLat, rect.minLat, rect.minLat };
    double polyLons[] = new double[] { rect.minLon, rect.minLon, rect.maxLon, rect.maxLon, rect.minLon };

    // TODO: separately test this method is 100% correct, here treat it like a black box (like haversin)
    return GeoRelationUtils.pointInPolygon(polyLats, polyLons, pointLat, pointLon);
  }

  static final boolean circleContainsPoint(double centerLat, double centerLon, double radiusMeters, double pointLat, double pointLon) {
    double distanceMeters = SloppyMath.haversinMeters(centerLat, centerLon, pointLat, pointLon);
    boolean result = distanceMeters <= radiusMeters;
    //System.out.println("  shouldMatch?  centerLon=" + centerLon + " centerLat=" + centerLat + " pointLon=" + pointLon + " pointLat=" + pointLat + " result=" + result + " distanceMeters=" + (distanceKM * 1000));
    return result;
  }

  static final boolean distanceRangeContainsPoint(double centerLat, double centerLon, double minRadiusMeters, double radiusMeters, double pointLat, double pointLon) {
    final double d = SloppyMath.haversinMeters(centerLat, centerLon, pointLat, pointLon);
    return d >= minRadiusMeters && d <= radiusMeters;
  }

  private static abstract class VerifyHits {

    public void test(AtomicBoolean failed, boolean small, IndexSearcher s, NumericDocValues docIDToID, Set<Integer> deleted, Query query, double[] lats, double[] lons) throws Exception {
      int maxDoc = s.getIndexReader().maxDoc();
      final FixedBitSet hits = new FixedBitSet(maxDoc);
      s.search(query, new SimpleCollector() {

          private int docBase;

          @Override
          public boolean needsScores() {
            return false;
          }

          @Override
          protected void doSetNextReader(LeafReaderContext context) throws IOException {
            docBase = context.docBase;
          }

          @Override
          public void collect(int doc) {
            hits.set(docBase+doc);
          }
        });

      boolean fail = false;

      // Change to false to see all wrong hits:
      boolean failFast = true;

      for(int docID=0;docID<maxDoc;docID++) {
        int id = (int) docIDToID.get(docID);
        boolean expected;
        if (deleted.contains(id)) {
          expected = false;
        } else if (Double.isNaN(lats[id])) {
          expected = false;
        } else {
          expected = shouldMatch(lats[id], lons[id]);
        }

        if (hits.get(docID) != expected) {

          // Print only one failed hit; add a true || in here to see all failures:
          if (failFast == false || failed.getAndSet(true) == false) {
            if (expected) {
              System.out.println(Thread.currentThread().getName() + ": id=" + id + " should match but did not");
            } else {
              System.out.println(Thread.currentThread().getName() + ": id=" + id + " should not match but did");
            }
            System.out.println("  small=" + small + " query=" + query +
                               " docID=" + docID + "\n  lat=" + lats[id] + " lon=" + lons[id] +
                               "\n  deleted?=" + deleted.contains(id));
            if (Double.isNaN(lats[id]) == false) {
              describe(docID, lats[id], lons[id]);
            }
            if (failFast) {
              fail("wrong hit (first of possibly more)");
            } else {
              fail = true;
            }
          }
        }
      }

      if (fail) {
        failed.set(true);
        fail("some hits were wrong");
      }
    }

    /** Return true if we definitely should match, false if we definitely
     *  should not match, and null if it's a borderline case which might
     *  go either way. */
    protected abstract boolean shouldMatch(double lat, double lon);

    protected abstract void describe(int docID, double lat, double lon);
  }

  protected void verify(boolean small, double[] lats, double[] lons, boolean useThreads) throws Exception {
    IndexWriterConfig iwc = newIndexWriterConfig();
    // Else we can get O(N^2) merging:
    int mbd = iwc.getMaxBufferedDocs();
    if (mbd != -1 && mbd < lats.length/100) {
      iwc.setMaxBufferedDocs(lats.length/100);
    }
    Directory dir;
    if (lats.length > 100000) {
      dir = newFSDirectory(createTempDir(getClass().getSimpleName()));
    } else {
      dir = newDirectory();
    }

    Set<Integer> deleted = new HashSet<>();
    // RandomIndexWriter is too slow here:
    IndexWriter w = new IndexWriter(dir, iwc);
    for(int id=0;id<lats.length;id++) {
      Document doc = new Document();
      doc.add(newStringField("id", ""+id, Field.Store.NO));
      doc.add(new NumericDocValuesField("id", id));
      if (Double.isNaN(lats[id]) == false) {
        addPointToDoc(FIELD_NAME, doc, lats[id], lons[id]);
      }
      w.addDocument(doc);
      if (id > 0 && random().nextInt(100) == 42) {
        int idToDelete = random().nextInt(id);
        w.deleteDocuments(new Term("id", ""+idToDelete));
        deleted.add(idToDelete);
        if (VERBOSE) {
          System.out.println("  delete id=" + idToDelete);
        }
      }
    }

    if (random().nextBoolean()) {
      w.forceMerge(1);
    }
    final IndexReader r = DirectoryReader.open(w);
    w.close();

    // We can't wrap with "exotic" readers because the BKD query must see the BKDDVFormat:
    IndexSearcher s = newSearcher(r, false);

    // Make sure queries are thread safe:
    int numThreads;
    if (useThreads) {
      numThreads = TestUtil.nextInt(random(), 2, 5);
    } else {
      numThreads = 1;
    }

    List<Thread> threads = new ArrayList<>();
    final int iters = atLeast(75);

    final CountDownLatch startingGun = new CountDownLatch(1);
    final AtomicBoolean failed = new AtomicBoolean();

    for(int i=0;i<numThreads;i++) {
      Thread thread = new Thread() {
          @Override
          public void run() {
            try {
              _run();
            } catch (Exception e) {
              failed.set(true);
              throw new RuntimeException(e);
            }
          }

          private void _run() throws Exception {
            if (useThreads) {
              startingGun.await();
            }

            NumericDocValues docIDToID = MultiDocValues.getNumericValues(r, "id");

            for (int iter=0;iter<iters && failed.get() == false;iter++) {

              if (VERBOSE) {
                System.out.println("\n" + Thread.currentThread().getName() + ": TEST: iter=" + iter + " s=" + s);
              }
              Query query;
              VerifyHits verifyHits;

              if (random().nextBoolean()) {
                // Rect: don't allow dateline crossing when testing small:
                final GeoRect rect = randomRect(small, small == false);

                query = newRectQuery(FIELD_NAME, rect);

                verifyHits = new VerifyHits() {
                    @Override
                    protected boolean shouldMatch(double pointLat, double pointLon) {
                      return rectContainsPoint(rect, pointLat, pointLon);
                    }
                    @Override
                    protected void describe(int docID, double lat, double lon) {
                    }
                  };

              } else if (random().nextBoolean()) {
                // Distance
                final boolean rangeQuery = random().nextBoolean();
                final double centerLat = randomLat(small);
                final double centerLon = randomLon(small);

                double radiusMeters;
                double minRadiusMeters;

                if (small) {
                  // Approx 3 degrees lon at the equator:
                  radiusMeters = random().nextDouble() * 333000 + 1.0;
                } else {
                  // So the query can cover at most 50% of the earth's surface:
                  radiusMeters = random().nextDouble() * GeoUtils.SEMIMAJOR_AXIS * Math.PI / 2.0 + 1.0;
                }

                // generate a random minimum radius between 1% and 95% the max radius
                minRadiusMeters = (0.01 + 0.94 * random().nextDouble()) * radiusMeters;

                if (VERBOSE) {
                  final DecimalFormat df = new DecimalFormat("#,###.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
                  System.out.println("  radiusMeters = " + df.format(radiusMeters)
                      + ((rangeQuery == true) ? " minRadiusMeters = " + df.format(minRadiusMeters) : ""));
                }

                try {
                  if (rangeQuery == true) {
                    query = newDistanceRangeQuery(FIELD_NAME, centerLat, centerLon, minRadiusMeters, radiusMeters);
                  } else {
                    query = newDistanceQuery(FIELD_NAME, centerLat, centerLon, radiusMeters);
                  }
                } catch (IllegalArgumentException e) {
                  if (e.getMessage().contains("exceeds maxRadius")) {
                    continue;
                  }
                  throw e;
                }

                verifyHits = new VerifyHits() {
                    @Override
                    protected boolean shouldMatch(double pointLat, double pointLon) {
                      if (rangeQuery == false) {
                        return circleContainsPoint(centerLat, centerLon, radiusMeters, pointLat, pointLon);
                      } else {
                        return distanceRangeContainsPoint(centerLat, centerLon, minRadiusMeters, radiusMeters, pointLat, pointLon);
                      }
                    }

                    @Override
                    protected void describe(int docID, double pointLat, double pointLon) {
                      double distanceMeters = SloppyMath.haversinMeters(centerLat, centerLon, pointLat, pointLon);
                      System.out.println("  docID=" + docID + " centerLon=" + centerLon + " centerLat=" + centerLat
                          + " pointLon=" + pointLon + " pointLat=" + pointLat + " distanceMeters=" + distanceMeters
                          + " vs" + ((rangeQuery == true) ? " minRadiusMeters=" + minRadiusMeters : "") + " radiusMeters=" + radiusMeters);
                    }
                   };

              // TODO: get poly query working with dateline crossing too (how?)!
              } else {

                // TODO: poly query can't handle dateline crossing yet:
                final GeoRect bbox = randomRect(small, false);

                // Polygon
                double[] lats = new double[5];
                double[] lons = new double[5];
                lats[0] = bbox.minLat;
                lons[0] = bbox.minLon;
                lats[1] = bbox.maxLat;
                lons[1] = bbox.minLon;
                lats[2] = bbox.maxLat;
                lons[2] = bbox.maxLon;
                lats[3] = bbox.minLat;
                lons[3] = bbox.maxLon;
                lats[4] = bbox.minLat;
                lons[4] = bbox.minLon;
                query = newPolygonQuery(FIELD_NAME, lats, lons);

                verifyHits = new VerifyHits() {
                    @Override
                    protected boolean shouldMatch(double pointLat, double pointLon) {
                      return polyRectContainsPoint(bbox, pointLat, pointLon);
                    }

                    @Override
                    protected void describe(int docID, double lat, double lon) {
                    }
                  };
              }

              if (query != null) {

                if (VERBOSE) {
                  System.out.println("  query=" + query);
                }

                verifyHits.test(failed, small, s, docIDToID, deleted, query, lats, lons);
              }
            }
          }
      };
      thread.setName("T" + i);
      if (useThreads) {
        thread.start();
      } else {
        // Just run with main thread:
        thread.run();
      }
      threads.add(thread);
    }
    if (useThreads) {
      startingGun.countDown();
      for(Thread thread : threads) {
        thread.join();
      }
    }
    IOUtils.close(r, dir);
    assertFalse(failed.get());
  }

  public void testRectBoundariesAreInclusive() throws Exception {
    GeoRect rect = randomRect(random().nextBoolean(), false);
    Directory dir = newDirectory();
    IndexWriterConfig iwc = newIndexWriterConfig();
    RandomIndexWriter w = new RandomIndexWriter(random(), dir, iwc);
    for(int x=0;x<3;x++) {
      double lat;
      if (x == 0) {
        lat = rect.minLat;
      } else if (x == 1) {
        lat = quantizeLat((rect.minLat+rect.maxLat)/2.0);
      } else {
        lat = rect.maxLat;
      }
      for(int y=0;y<3;y++) {
        double lon;
        if (y == 0) {
          lon = rect.minLon;
        } else if (y == 1) {
          if (x == 1) {
            continue;
          }
          lon = quantizeLon((rect.minLon+rect.maxLon)/2.0);
        } else {
          lon = rect.maxLon;
        }

        Document doc = new Document();
        addPointToDoc(FIELD_NAME, doc, lat, lon);
        w.addDocument(doc);
      }
    }
    IndexReader r = w.getReader();
    IndexSearcher s = newSearcher(r, false);
    assertEquals(8, s.count(newRectQuery(FIELD_NAME, rect)));
    r.close();
    w.close();
    dir.close();
  }
  
  /** Run a few iterations with just 10 docs, hopefully easy to debug */
  public void testRandomDistance() throws Exception {
    for (int iters = 0; iters < 100; iters++) {
      doRandomDistanceTest(10, 100);
    }
  }
    
  /** Runs with thousands of docs */
  @Nightly
  public void testRandomDistanceHuge() throws Exception {
    for (int iters = 0; iters < 10; iters++) {
      doRandomDistanceTest(2000, 100);
    }
  }
    
  private void doRandomDistanceTest(int numDocs, int numQueries) throws IOException {
    Directory dir = newDirectory();
    IndexWriterConfig iwc = newIndexWriterConfig();
    int pointsInLeaf = 2 + random().nextInt(4);
    iwc.setCodec(new FilterCodec("Lucene60", TestUtil.getDefaultCodec()) {
      @Override
      public PointsFormat pointsFormat() {
        return new PointsFormat() {
          @Override
          public PointsWriter fieldsWriter(SegmentWriteState writeState) throws IOException {
            return new Lucene60PointsWriter(writeState, pointsInLeaf, BKDWriter.DEFAULT_MAX_MB_SORT_IN_HEAP);
          }
  
          @Override
          public PointsReader fieldsReader(SegmentReadState readState) throws IOException {
            return new Lucene60PointsReader(readState);
          }
        };
      }
    });
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir, iwc);
  
    for (int i = 0; i < numDocs; i++) {
      double latRaw = -90 + 180.0 * random().nextDouble();
      double lonRaw = -180 + 360.0 * random().nextDouble();
      // pre-normalize up front, so we can just use quantized value for testing and do simple exact comparisons
      double lat = quantizeLat(latRaw);
      double lon = quantizeLon(lonRaw);
      Document doc = new Document();
      addPointToDoc("field", doc, lat, lon);
      doc.add(new StoredField("lat", lat));
      doc.add(new StoredField("lon", lon));
      writer.addDocument(doc);
    }
    IndexReader reader = writer.getReader();
    IndexSearcher searcher = newSearcher(reader);
  
    for (int i = 0; i < numQueries; i++) {
      double lat = -90 + 180.0 * random().nextDouble();
      double lon = -180 + 360.0 * random().nextDouble();
      double radius = maxRadius(lat, lon) * random().nextDouble();
  
      BitSet expected = new BitSet();
      for (int doc = 0; doc < reader.maxDoc(); doc++) {
        double docLatitude = reader.document(doc).getField("lat").numericValue().doubleValue();
        double docLongitude = reader.document(doc).getField("lon").numericValue().doubleValue();
        double distance = SloppyMath.haversinMeters(lat, lon, docLatitude, docLongitude);
        if (distance <= radius) {
          expected.set(doc);
        }
      }
  
      TopDocs topDocs = searcher.search(newDistanceQuery("field", lat, lon, radius), reader.maxDoc(), Sort.INDEXORDER);
      BitSet actual = new BitSet();
      for (ScoreDoc doc : topDocs.scoreDocs) {
        actual.set(doc.doc);
      }
      
      try {
        assertEquals(expected, actual);
      } catch (AssertionError e) {
        System.out.println("center: (" + lat + "," + lon + "), radius=" + radius);
        for (int doc = 0; doc < reader.maxDoc(); doc++) {
          double docLatitude = reader.document(doc).getField("lat").numericValue().doubleValue();
          double docLongitude = reader.document(doc).getField("lon").numericValue().doubleValue();
          double distance = SloppyMath.haversinMeters(lat, lon, docLatitude, docLongitude);
          System.out.println("" + doc + ": (" + docLatitude + "," + docLongitude + "), distance=" + distance);
        }
        throw e;
      }
    }
    reader.close();
    writer.close();
    dir.close();
  }

  /** Returns {polyLats, polyLons} double[] array */
  private double[][] surpriseMePolygon() {
    // repeat until we get a poly that doesn't cross dateline:
    newPoly:
    while (true) {
      //System.out.println("\nPOLY ITER");
      double centerLat = randomLat(false);
      double centerLon = randomLon(false);

      double radius = 0.1 + 20 * random().nextDouble();
      double radiusDelta = random().nextDouble();

      List<Double> lats = new ArrayList<>();
      List<Double> lons = new ArrayList<>();
      double angle = 0.0;
      while (true) {
        angle += random().nextDouble()*40.0;
        //System.out.println("  angle " + angle);
        if (angle > 360) {
          break;
        }
        double len = radius * (1.0 - radiusDelta + radiusDelta * random().nextDouble());
        //System.out.println("    len=" + len);
        double lat = wrapLat(centerLat + len * Math.cos(Math.toRadians(angle)));
        double lon = centerLon + len * Math.sin(Math.toRadians(angle));
        if (lon <= GeoUtils.MIN_LON_INCL || lon >= GeoUtils.MAX_LON_INCL) {
          // cannot cross dateline: try again!
          continue newPoly;
        }
        lats.add(wrapLat(lat));
        lons.add(wrapLon(lon));

        //System.out.println("    lat=" + lats.get(lats.size()-1) + " lon=" + lons.get(lons.size()-1));
      }

      // close it
      lats.add(lats.get(0));
      lons.add(lons.get(0));

      double[] latsArray = new double[lats.size()];
      double[] lonsArray = new double[lons.size()];
      for(int i=0;i<lats.size();i++) {
        latsArray[i] = lats.get(i);
        lonsArray[i] = lons.get(i);
      }
      return new double[][] {latsArray, lonsArray};
    }
  }

  // nocommit not necessarily interesting yet:
  /** Tests points just inside and just outside of the requested surface distance */
  public void testDistanceQueryBoundaries() throws Exception {
    int iters = atLeast(100);
    for(int iter=0;iter<iters;iter++) {
      System.out.println("iter " + iter);

      // Make a random distance query:
      double centerLat = randomLat(false);
      double centerLon = randomLon(false);
      double radiusMeters;

      // So the query can cover at most 50% of the earth's surface:
      radiusMeters = random().nextDouble() * GeoUtils.SEMIMAJOR_AXIS * Math.PI / 2.0 + 1.0;

      Directory dir = newDirectory();
      RandomIndexWriter writer = new RandomIndexWriter(random(), dir);

      // Index a bunch of points right near the border:
      int numPoints = atLeast(10000);
      List<double[]> points = new ArrayList<>();
      for(int id=0;id<numPoints;id++) {
        Document document = new Document();
        document.add(new NumericDocValuesField("id", id));
        double[] point = randomPointNearCircleBorder(centerLat, centerLon, radiusMeters);
        points.add(point);
        addPointToDoc("field", document, point[0], point[1]);
        writer.addDocument(document);
      }

      IndexReader reader = writer.getReader();
      IndexSearcher searcher = newSearcher(reader);
      int maxDoc = searcher.getIndexReader().maxDoc();
      final FixedBitSet hits = new FixedBitSet(maxDoc);

      Query query = newDistanceQuery("field", centerLat, centerLon, radiusMeters);
      searcher.search(query, new SimpleCollector() {

          private int docBase;

          @Override
          public boolean needsScores() {
            return false;
          }

          @Override
          protected void doSetNextReader(LeafReaderContext context) throws IOException {
            docBase = context.docBase;
          }

          @Override
          public void collect(int doc) {
            hits.set(docBase+doc);
          }
        });

      NumericDocValues docIDToID = MultiDocValues.getNumericValues(reader, "id");

      for(int docID=0;docID<numPoints;docID++) {
        int id = (int) docIDToID.get(docID);
        double[] point = points.get(id);
        boolean expected = circleContainsPoint(centerLat, centerLon, radiusMeters, point[0], point[1]);
        if (expected != hits.get(docID)) {
          //System.out.println(toWebGLEarth(centerLat, centerLon, radiusMeters, point[0], point[1]));
          StringBuilder b = new StringBuilder();
          if (expected) {
            b.append("point should match but failed to:\n");
          } else {
            b.append("point should not match but did:\n");
          }
          b.append("  point: lat=" + point[0] + " lon=" + point[1]);
          b.append("  center: lat=" + centerLat + " lon=" + centerLon);
          b.append("  radius: " + radiusMeters + " meters");
          b.append("  haversin distance: " + SloppyMath.haversinMeters(centerLat, centerLon, point[0], point[1]));
          fail(b.toString());
        }
      }

      IOUtils.close(reader, writer, dir);
    }
  }

  private static double wrapLat(double lat) {
    //System.out.println("wrapLat " + lat);
    if (lat > 90) {
      //System.out.println("  " + (180 - lat));
      return 180 - lat;
    } else if (lat < -90) {
      //System.out.println("  " + (-180 - lat));
      return -180 - lat;
    } else {
      //System.out.println("  " + lat);
      return lat;
    }
  }

  private static double wrapLon(double lon) {
    //System.out.println("wrapLon " + lon);
    if (lon > 180) {
      //System.out.println("  " + (lon - 360));
      return lon - 360;
    } else if (lon < -180) {
      //System.out.println("  " + (lon + 360));
      return lon + 360;
    } else {
      //System.out.println("  " + lon);
      return lon;
    }
  }

  /** Returns a point as {lat, lon} double array */
  private double[] randomPointNearCircleBorder(double centerLat, double centerLon, double radiusMeters) {
    //System.out.println("random: lat=" + centerLat + " lon=" + centerLon + " radius=" + radiusMeters);

    newAngle:
    while (true) {
      double angle = 360.0 * random().nextDouble();
      //System.out.println("  angle=" + angle);
      double x = Math.cos(Math.toRadians(angle));
      double y = Math.sin(Math.toRadians(angle));
      double factor = 2.0;
      double step = 1.0;
      double lastDistanceMeters = 0.0;
      int last = 0;
      while (true) {
        double rawLat = centerLat + y * factor;
        if (rawLat >= 180 || rawLat <= -180) {
          // For large enough circles, some angles are not possible:
          //System.out.println("  done: give up on angle " + angle);
          continue newAngle;
        }

        double rawLon = centerLon + x * factor;
        if (rawLon >= 360 || rawLon <= -360) {
          // For large enough circles, some angles are not possible:
          //System.out.println("  done: give up on angle " + angle);
          continue newAngle;
        }

        double lat = quantizeLat(wrapLat(rawLat));
        double lon = quantizeLon(wrapLon(rawLon));
        double distanceMeters = SloppyMath.haversinMeters(centerLat, centerLon, lat, lon);

        if (last == 1 && distanceMeters < lastDistanceMeters) {
          // For large enough circles, some angles are not possible:
          //System.out.println("  done: give up on angle " + angle);
          continue newAngle;
        }
        if (last == -1 && distanceMeters > lastDistanceMeters) {
          // For large enough circles, some angles are not possible:
          //System.out.println("  done: give up on angle " + angle);
          continue newAngle;
        }

        lastDistanceMeters = distanceMeters;
        //System.out.println("    iter " + distanceMeters);
        if (Math.abs(distanceMeters - radiusMeters) < 0.1) {
          //System.out.println("done: lat=" + lat + " lon=" + lon + " angle=" + angle);
          return new double[] {lat, lon};
        }
        if (distanceMeters > radiusMeters) {
          // too big
          factor -= step;
          if (last == 1) {
            step /= 2.0;
          }
          last = -1;
        } else if (distanceMeters < radiusMeters) {
          // too small
          factor += step;
          if (last == -1) {
            step /= 2.0;
          }
          last = 1;
        }
      }
    }
  }

  private static void drawRectApproximatelyOnEarthSurface(String name, String color, double minLat, double maxLat, double minLon, double maxLon) {
    int steps = 20;
    System.out.println("        var " + name + " = WE.polygon([");
    System.out.println("          // min -> max lat, min lon");
    for(int i=0;i<steps;i++) {
      System.out.println("          [" + (minLat + (maxLat - minLat) * i / steps) + ", " + minLon + "],");
    }
    System.out.println("          // max lat, min -> max lon");
    for(int i=0;i<steps;i++) {
      System.out.println("          [" + (maxLat + ", " + (minLon + (maxLon - minLon) * i / steps)) + "],");
    }
    System.out.println("          // max -> min lat, max lon");
    for(int i=0;i<steps;i++) {
      System.out.println("          [" + (minLat + (maxLat - minLat) * (steps-i) / steps) + ", " + maxLon + "],");
    }
    System.out.println("          // min lat, max -> min lon");
    for(int i=0;i<steps;i++) {
      System.out.println("          [" + minLat + ", " + (minLon + (maxLon - minLon) * (steps-i) / steps) + "],");
    }
    System.out.println("          // min lat, min lon");
    System.out.println("          [" + minLat + ", " + minLon + "]");
    System.out.println("        ], {color: \"" + color + "\", fillColor: \"" + color + "\"});");
    System.out.println("        " + name + ".addTo(earth);");
  }

  public void testFoo() throws Exception {
    /*
    toWebGLEarth(34.36653373591446, 69.9501119915648,
                 21.57567258590899, 24.932828252405756,
                 33.446926576806234, -18.702673123352326,
                 3672421.3834118056);
    toWebGLEarth(20.349435726973987, 75.6212078954794,
                 72.85917553486915, 77.19077779277924,
                 42.07639048470125, 107.07274153895605,
                 2975340.785331476);
    */
    List<double[][]> polys = new ArrayList<>();
    for(int i=0;i<100;i++) {
      polys.add(surpriseMePolygon());
    }
    polysToWebGLEarth(polys);
  }

  private static void plotLatApproximatelyOnEarthSurface(String name, String color, double lat, double minLon, double maxLon) {
    System.out.println("        var " + name + " = WE.polygon([");
    double lon;
    for(lon = minLon;lon<=maxLon;lon += (maxLon-minLon)/36) {
      System.out.println("          [" + lat + ", " + lon + "],");
    }
    System.out.println("          [" + lat + ", " + maxLon + "],");
    lon -= (maxLon-minLon)/36;
    for(;lon>=minLon;lon -= (maxLon-minLon)/36) {
      System.out.println("          [" + lat + ", " + lon + "],");
    }
    System.out.println("        ], {color: \"" + color + "\", fillColor: \"#ffffff\", opacity: " + (color.equals("#ffffff") ? "0.3" : "1") + ", fillOpacity: 0.0001});");
    System.out.println("        " + name + ".addTo(earth);");
  }

  private static void plotLonApproximatelyOnEarthSurface(String name, String color, double lon, double minLat, double maxLat) {
    System.out.println("        var " + name + " = WE.polygon([");
    double lat;
    for(lat = minLat;lat<=maxLat;lat += (maxLat-minLat)/36) {
      System.out.println("          [" + lat + ", " + lon + "],");
    }
    System.out.println("          [" + maxLat + ", " + lon + "],");
    lat -= (maxLat-minLat)/36;
    for(;lat>=minLat;lat -= (maxLat-minLat)/36) {
      System.out.println("          [" + lat + ", " + lon + "],");
    }
    System.out.println("        ], {color: \"" + color + "\", fillColor: \"#ffffff\", opacity: " + (color.equals("#ffffff") ? "0.3" : "1") + ", fillOpacity: 0.0001});");
    System.out.println("        " + name + ".addTo(earth);");
  }

  // http://www.webglearth.org has API details:
  public static void polysToWebGLEarth(List<double[][]> polys) {
    System.out.println("<!DOCTYPE HTML>");
    System.out.println("<html>");
    System.out.println("  <head>");
    System.out.println("    <script src=\"http://www.webglearth.com/v2/api.js\"></script>");
    System.out.println("    <script>");
    System.out.println("      function initialize() {");
    System.out.println("        var earth = new WE.map('earth_div');");

    int count = 0;
    for (double[][] poly : polys) {
      System.out.println("        var poly" + count + " = WE.polygon([");
      for(int i=0;i<poly[0].length;i++) {
        double lat = poly[0][i];
        double lon = poly[1][i];
        System.out.println("          [" + lat + ", " + lon + "],");
      }
      System.out.println("        ], {color: '#00ff00'});");    
      System.out.println("        poly" + count + ".addTo(earth);");
    }

    System.out.println("        WE.tileLayer('http://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{");
    System.out.println("          attribution: '© OpenStreetMap contributors'");
    System.out.println("        }).addTo(earth);");
    System.out.println("      }");
    System.out.println("    </script>");
    System.out.println("    <style>");
    System.out.println("      html, body{padding: 0; margin: 0;}");
    System.out.println("      #earth_div{top: 0; right: 0; bottom: 0; left: 0; position: absolute !important;}");
    System.out.println("    </style>");
    System.out.println("    <title>WebGL Earth API: Hello World</title>");
    System.out.println("  </head>");
    System.out.println("  <body onload=\"initialize()\">");
    System.out.println("    <div id=\"earth_div\"></div>");
    System.out.println("  </body>");
    System.out.println("</html>");
  }

  // http://www.webglearth.org has API details:
  public static void toWebGLEarth(double rectMinLatitude, double rectMaxLatitude,
                                   double rectMinLongitude, double rectMaxLongitude,
                                   double centerLatitude, double centerLongitude,
                                   double radiusMeters) {
    GeoRect box = GeoUtils.circleToBBox(centerLatitude, centerLongitude, radiusMeters);
    System.out.println("<!DOCTYPE HTML>");
    System.out.println("<html>");
    System.out.println("  <head>");
    System.out.println("    <script src=\"http://www.webglearth.com/v2/api.js\"></script>");
    System.out.println("    <script>");
    System.out.println("      function initialize() {");
    System.out.println("        var earth = new WE.map('earth_div', {center: [" + centerLatitude + ", " + centerLongitude + "]});");
    System.out.println("        var marker = WE.marker([" + centerLatitude + ", " + centerLongitude + "]).addTo(earth);");
    drawRectApproximatelyOnEarthSurface("cell", "#ff0000", rectMinLatitude, rectMaxLatitude, rectMinLongitude, rectMaxLongitude);
    System.out.println("        var polygonB = WE.polygon([");
    StringBuilder b = new StringBuilder();
    inverseHaversin(b, centerLatitude, centerLongitude, radiusMeters);
    System.out.println(b);
    System.out.println("        ], {color: '#00ff00'});");    
    System.out.println("        polygonB.addTo(earth);");
    drawRectApproximatelyOnEarthSurface("bbox", "#00ff00", box.minLat, box.maxLat, box.minLon, box.maxLon);
    System.out.println("        WE.tileLayer('http://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{");
    System.out.println("          attribution: '© OpenStreetMap contributors'");
    System.out.println("        }).addTo(earth);");
    plotLatApproximatelyOnEarthSurface("lat0", "#ffffff", 4.68, 0.0, 360.0);
    plotLatApproximatelyOnEarthSurface("lat1", "#ffffff", 180-93.09, 0.0, 360.0);
    plotLatApproximatelyOnEarthSurface("axisLat", "#00ff00", GeoUtils.axisLat(centerLatitude, radiusMeters), box.minLon, box.maxLon);
    plotLonApproximatelyOnEarthSurface("axisLon", "#00ff00", centerLongitude, box.minLat, box.maxLat);
    System.out.println("      }");
    System.out.println("    </script>");
    System.out.println("    <style>");
    System.out.println("      html, body{padding: 0; margin: 0;}");
    System.out.println("      #earth_div{top: 0; right: 0; bottom: 0; left: 0; position: absolute !important;}");
    System.out.println("    </style>");
    System.out.println("    <title>WebGL Earth API: Hello World</title>");
    System.out.println("  </head>");
    System.out.println("  <body onload=\"initialize()\">");
    System.out.println("    <div id=\"earth_div\"></div>");
    System.out.println("  </body>");
    System.out.println("</html>");
  }

  private static void inverseHaversin(StringBuilder b, double centerLat, double centerLon, double radiusMeters) {
    double angle = 0;
    int steps = 100;

    newAngle:
    while (angle < 360) {
      double x = Math.cos(Math.toRadians(angle));
      double y = Math.sin(Math.toRadians(angle));
      double factor = 2.0;
      double step = 1.0;
      int last = 0;
      double lastDistanceMeters = 0.0;
      //System.out.println("angle " + angle + " slope=" + slope);
      while (true) {
        double lat = wrapLat(centerLat + y * factor);
        double lon = wrapLon(centerLon + x * factor);
        double distanceMeters = SloppyMath.haversinMeters(centerLat, centerLon, lat, lon);

        if (last == 1 && distanceMeters < lastDistanceMeters) {
          // For large enough circles, some angles are not possible:
          //System.out.println("  done: give up on angle " + angle);
          angle += 360./steps;
          continue newAngle;
        }
        if (last == -1 && distanceMeters > lastDistanceMeters) {
          // For large enough circles, some angles are not possible:
          //System.out.println("  done: give up on angle " + angle);
          angle += 360./steps;
          continue newAngle;
        }
        lastDistanceMeters = distanceMeters;

        //System.out.println("  iter lat=" + lat + " lon=" + lon + " distance=" + distanceMeters + " vs " + radiusMeters);
        if (Math.abs(distanceMeters - radiusMeters) < 0.1) {
          b.append("          [" + lat + ", " + lon + "],\n");
          break;
        }
        if (distanceMeters > radiusMeters) {
          // too big
          //System.out.println("    smaller");
          factor -= step;
          if (last == 1) {
            //System.out.println("      half-step");
            step /= 2.0;
          }
          last = -1;
        } else if (distanceMeters < radiusMeters) {
          // too small
          //System.out.println("    bigger");
          factor += step;
          if (last == -1) {
            //System.out.println("      half-step");
            step /= 2.0;
          }
          last = 1;
        }
      }
      angle += 360./steps;
    }
  }
}
